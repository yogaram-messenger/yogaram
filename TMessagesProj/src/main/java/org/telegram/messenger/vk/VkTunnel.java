package org.telegram.messenger.vk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local SOCKS5 (127.0.0.1) that carries MTProto bytes to the VK gateway.
 * tgnet is pointed at this proxy; the phone never dials 46.8.46.56.
 * Frames use the VKVPN_MT prefix and the same HMAC session as HTTP pairing.
 */
public final class VkTunnel {
    private static final String TAG = "VkTunnel";
    public static final String PREFIX_MT = "VKVPN_MT:";
    public static final String PREFIX_PAIR = "VKVPN_PAIR:";
    private static final String PREFS = "vk_tunnel";
    private static final int INLINE = 2400;
    private static final int MAX_FRAME = 512 * 1024;
    private static final long SEND_GAP_MS = 200;
    private static final int PORT_API = 20443;
    private static final int PORT_MEDIA = 20543;

    private static final Object LIFECYCLE = new Object();
    private static final Object SEND = new Object();
    private static final Object DISPATCH = new Object();

    private static Context appCtx;
    private static ServerSocket server;
    private static ExecutorService conns;
    private static volatile boolean running;
    private static Thread pollThread;
    private static final Map<String, Conn> connections = new HashMap<>();
    private static final Set<Long> seenMessages = new HashSet<>();
    private static final AtomicInteger randomIds = new AtomicInteger();
    private static long lastSendAt;

    private VkTunnel() {
    }

    public static boolean isPaired() {
        SharedPreferences p = prefs();
        return p != null
                && p.getLong("group_id", 0L) != 0L
                && !p.getString("user_token", "").isEmpty()
                && !p.getString("session_key", "").isEmpty()
                && !p.getString("device_id", "").isEmpty();
    }

    public static boolean allowNetwork() {
        synchronized (LIFECYCLE) {
            return isPaired() && server != null && !server.isClosed() && running;
        }
    }

    /** @return SOCKS port when paired, otherwise 9 (nothing listens there). */
    public static int prepare(Context ctx) {
        synchronized (LIFECYCLE) {
            appCtx = ctx.getApplicationContext();
            if (!isPaired()) {
                return 9;
            }
            if (server != null && !server.isClosed() && running) {
                return server.getLocalPort();
            }
            startLocked();
            return server.getLocalPort();
        }
    }

    public static void applyToConnections(Context ctx) {
        int port = prepare(ctx);
        int n = UserConfig.MAX_ACCOUNT_COUNT;
        for (int a = 0; a < n; a++) {
            ConnectionsManager.native_setProxySettings(a, "127.0.0.1", port, "", "", "");
            ConnectionsManager.getInstance(a).checkConnection();
        }
    }

    public static void unpair(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs().edit().remove("session_key").remove("vk_user_id").apply();
        stopLocked();
        applyToConnections(ctx);
    }

    public static void pair(Context ctx, long groupId, String token, String code, String appId, String miniUrl) throws Exception {
        appCtx = ctx.getApplicationContext();
        stopLocked();
        if (groupId <= 0) {
            throw new IOException("Укажите ID сообщества");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Сначала войдите через VK");
        }
        code = code == null ? "" : code.trim().toUpperCase(Locale.US).replace(" ", "");
        if (code.isEmpty()) {
            throw new IOException("Введите код из Mini App");
        }
        String deviceId = deviceId();
        prefs().edit()
                .putLong("group_id", groupId)
                .putString("user_token", token.trim())
                .putString("vk_app_id", appId == null ? "" : appId.trim())
                .putString("mini_url", miniUrl == null ? "" : miniUrl.trim())
                .putString("device_id", deviceId)
                .remove("session_key")
                .apply();

        JSONObject body = new JSONObject();
        body.put("v", 1);
        body.put("type", "pair");
        body.put("pair_code", code);
        body.put("device_id", deviceId);
        body.put("device_name", "nandagram-vk");
        api().send(peerId(), PREFIX_PAIR + body.toString(), null);

        long deadline = System.currentTimeMillis() + 120_000L;
        Lp lp = null;
        while (System.currentTimeMillis() < deadline) {
            JSONObject found = findPairOk(deviceId);
            if (found != null) {
                prefs().edit()
                        .putString("session_key", found.getString("session_key"))
                        .putLong("vk_user_id", found.getLong("vk_user_id"))
                        .apply();
                return;
            }
            try {
                if (lp == null) {
                    lp = api().longPollServer();
                }
                Lp next = api().longPoll(lp, 15, updates -> {
                });
                lp = next;
                found = findPairOk(deviceId);
                if (found != null) {
                    prefs().edit()
                            .putString("session_key", found.getString("session_key"))
                            .putLong("vk_user_id", found.getLong("vk_user_id"))
                            .apply();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "pair poll: " + e.getMessage());
                lp = null;
                Thread.sleep(1000);
            }
        }
        throw new IOException("Нет ответа бота. Проверьте код и что диалог с сообществом открыт.");
    }

    public static String savedToken() {
        SharedPreferences p = prefs();
        return p == null ? "" : p.getString("user_token", "");
    }

    public static String savedGroup() {
        SharedPreferences p = prefs();
        if (p == null) {
            return "";
        }
        long id = p.getLong("group_id", 0L);
        return id == 0L ? "" : Long.toString(id);
    }

    public static String savedAppId() {
        SharedPreferences p = prefs();
        return p == null ? "" : p.getString("vk_app_id", "");
    }

    public static String savedMiniUrl() {
        SharedPreferences p = prefs();
        return p == null ? "" : p.getString("mini_url", "");
    }

    private static void startLocked() {
        try {
            stopSocketsLocked();
            server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            running = true;
            conns = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "vk-mt-conn");
                t.setDaemon(false);
                return t;
            });
            Thread accept = new Thread(VkTunnel::acceptLoop, "vk-mt-accept");
            accept.setDaemon(false);
            accept.start();
            pollThread = new Thread(VkTunnel::pollLoop, "vk-mt-poll");
            pollThread.setDaemon(false);
            pollThread.start();
            Log.i(TAG, "SOCKS 127.0.0.1:" + server.getLocalPort());
        } catch (IOException e) {
            running = false;
            Log.e(TAG, "socks start failed", e);
        }
    }

    private static void stopLocked() {
        synchronized (LIFECYCLE) {
            stopSocketsLocked();
        }
    }

    private static void stopSocketsLocked() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        synchronized (DISPATCH) {
            for (Conn c : new ArrayList<>(connections.values())) {
                closeConn(c);
            }
            connections.clear();
        }
        if (conns != null) {
            conns.shutdownNow();
            conns = null;
        }
    }

    private static void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                ExecutorService pool = conns;
                if (pool == null) {
                    socket.close();
                    return;
                }
                pool.execute(() -> handleSocks(socket));
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
                return;
            }
        }
    }

    private static void handleSocks(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int ver = in.read();
            int nmethods = in.read();
            if (ver != 5 || nmethods <= 0) {
                socket.close();
                return;
            }
            byte[] methods = new byte[nmethods];
            readFully(in, methods);
            boolean noAuth = false;
            for (byte m : methods) {
                if (m == 0) {
                    noAuth = true;
                    break;
                }
            }
            if (!noAuth) {
                out.write(new byte[]{5, (byte) 0xFF});
                socket.close();
                return;
            }
            out.write(new byte[]{5, 0});
            int reqVer = in.read();
            int cmd = in.read();
            in.read();
            int atyp = in.read();
            if (reqVer != 5 || cmd != 1) {
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                socket.close();
                return;
            }
            String host;
            if (atyp == 1) {
                byte[] ip = new byte[4];
                readFully(in, ip);
                host = (ip[0] & 255) + "." + (ip[1] & 255) + "." + (ip[2] & 255) + "." + (ip[3] & 255);
            } else if (atyp == 3) {
                int len = in.read();
                byte[] name = new byte[len];
                readFully(in, name);
                host = new String(name, StandardCharsets.US_ASCII);
            } else {
                out.write(new byte[]{5, 8, 0, 1, 0, 0, 0, 0, 0, 0});
                socket.close();
                return;
            }
            int port = ((in.read() & 255) << 8) | (in.read() & 255);
            if (!allowedTarget(host, port)) {
                out.write(new byte[]{5, 2, 0, 1, 0, 0, 0, 0, 0, 0});
                socket.close();
                return;
            }
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            tunnel(socket, host, port);
        } catch (Exception e) {
            Log.w(TAG, "socks: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean allowedTarget(String host, int port) {
        if (port != PORT_API && port != PORT_MEDIA) {
            return false;
        }
        String h = host == null ? "" : host.toLowerCase(Locale.US).replaceAll("\\.$", "");
        return h.equals("46.8.46.56") || h.equals("nandagram.myfbt.ru");
    }

    private static void tunnel(Socket socket, String host, int port) throws Exception {
        Conn conn = new Conn(socket, host, port);
        synchronized (DISPATCH) {
            connections.put(conn.id, conn);
        }
        try {
            byte[] first = readCoalesced(socket.getInputStream(), 40);
            sendOpen(conn, first == null ? new byte[0] : first);
            long deadline = System.currentTimeMillis() + 90_000L;
            while (!conn.openAcked && !conn.closed && System.currentTimeMillis() < deadline) {
                synchronized (conn.lock) {
                    conn.lock.wait(500);
                }
            }
            if (!conn.openAcked || conn.closed) {
                closeConn(conn);
                return;
            }
            while (!conn.closed && running) {
                byte[] chunk = readCoalesced(socket.getInputStream(), 40);
                if (chunk == null) {
                    sendControl(conn, "close", "client");
                    closeConn(conn);
                    return;
                }
                if (chunk.length == 0) {
                    continue;
                }
                sendData(conn, chunk);
            }
        } catch (Exception e) {
            if (!conn.closed) {
                Log.w(TAG, "tunnel " + conn.id + ": " + e.getMessage());
            }
            closeConn(conn);
        }
    }

    private static void sendOpen(Conn conn, byte[] first) throws Exception {
        JSONObject obj = base(conn, "open");
        obj.put("host", conn.host);
        obj.put("port", conn.port);
        if (first.length > 0) {
            int seq;
            synchronized (conn.lock) {
                seq = conn.nextOut++;
            }
            obj.put("seq", seq);
            putPayload(obj, conn, first);
        }
        post(obj, conn, first.length > INLINE ? first : null);
    }

    private static void sendData(Conn conn, byte[] chunk) throws Exception {
        int offset = 0;
        while (offset < chunk.length && !conn.closed) {
            int n = Math.min(MAX_FRAME, chunk.length - offset);
            byte[] part = new byte[n];
            System.arraycopy(chunk, offset, part, 0, n);
            offset += n;
            JSONObject obj = base(conn, "data");
            int seq;
            synchronized (conn.lock) {
                seq = conn.nextOut++;
            }
            obj.put("seq", seq);
            putPayload(obj, conn, part);
            post(obj, conn, part.length > INLINE ? part : null);
        }
    }

    private static void sendControl(Conn conn, String type, String reason) {
        try {
            JSONObject obj = base(conn, type);
            if (reason != null) {
                obj.put("reason", reason);
            }
            post(obj, conn, null);
        } catch (Exception e) {
            Log.w(TAG, "control: " + e.getMessage());
        }
    }

    private static JSONObject base(Conn conn, String type) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("v", 1);
        obj.put("type", type);
        obj.put("conn_id", conn.id);
        obj.put("device_id", deviceId());
        obj.put("ts", System.currentTimeMillis() / 1000L);
        return obj;
    }

    private static void putPayload(JSONObject obj, Conn conn, byte[] payload) throws Exception {
        if (payload.length <= INLINE) {
            obj.put("payload_b64", b64(payload));
        } else {
            obj.put("blob", "doc");
            obj.put("len", payload.length);
        }
    }

    private static void post(JSONObject obj, Conn conn, byte[] docBytes) throws Exception {
        String attachment = null;
        if (docBytes != null && docBytes.length > INLINE) {
            attachment = api().uploadDoc(peerId(), "mt-" + conn.id + ".bin", docBytes);
        }
        String canonical = canonical(obj);
        String sig = hmac(canonical, sessionKey());
        String json = canonical.substring(0, canonical.length() - 1) + ",\"sig\":\"" + sig + "\"}";
        synchronized (SEND) {
            long wait = SEND_GAP_MS - (System.currentTimeMillis() - lastSendAt);
            if (wait > 0) {
                Thread.sleep(wait);
            }
            api().send(peerId(), PREFIX_MT + json, attachment);
            lastSendAt = System.currentTimeMillis();
        }
    }

    private static void pollLoop() {
        Api api;
        try {
            api = api();
        } catch (Exception e) {
            Log.e(TAG, "poll api", e);
            return;
        }
        Lp lp = null;
        while (running) {
            try {
                pollHistory();
                if (lp == null) {
                    lp = api.longPollServer();
                }
                lp = api.longPoll(lp, 20, VkTunnel::onUpdates);
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                Log.w(TAG, "poll: " + e.getMessage());
                lp = null;
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void onUpdates(JSONArray updates) {
        for (int i = 0; i < updates.length(); i++) {
            JSONArray u = updates.optJSONArray(i);
            if (u == null || u.length() < 6 || u.optInt(0) != 4) {
                continue;
            }
            int flags = u.optInt(2);
            if ((flags & 2) != 0) {
                continue;
            }
            long mid = u.optLong(1);
            handleText(u.optString(5), mid, null);
        }
    }

    private static void pollHistory() {
        if (!isPaired()) {
            return;
        }
        try {
            JSONArray items = api().history(peerId(), 30);
            for (int i = 0; i < items.length(); i++) {
                JSONObject msg = items.getJSONObject(i);
                if (msg.optInt("out") == 1) {
                    continue;
                }
                handleText(msg.optString("text"), msg.optLong("id"), msg.optJSONArray("attachments"));
            }
        } catch (Exception e) {
            Log.w(TAG, "history: " + e.getMessage());
        }
    }

    private static void handleText(String text, long messageId, JSONArray attachments) {
        if (text == null) {
            return;
        }
        text = text.trim();
        if (!text.startsWith(PREFIX_MT)) {
            return;
        }
        synchronized (DISPATCH) {
            if (messageId != 0 && !seenMessages.add(messageId)) {
                return;
            }
        }
        try {
            JSONObject obj = new JSONObject(text.substring(PREFIX_MT.length()));
            if (!verify(obj)) {
                Log.w(TAG, "bad sig");
                return;
            }
            String type = obj.optString("type");
            Conn conn;
            synchronized (DISPATCH) {
                conn = connections.get(obj.optString("conn_id"));
            }
            if (conn == null) {
                return;
            }
            if ("error".equals(type)) {
                Log.w(TAG, "gw error " + obj.optString("error"));
                closeConn(conn);
                return;
            }
            if ("close".equals(type)) {
                closeConn(conn);
                return;
            }
            byte[] payload = payloadOf(obj, messageId, attachments);
            if (payload.length > 0 && obj.has("seq")) {
                accept(conn, obj.getInt("seq"), payload);
            }
            if ("open_ok".equals(type)) {
                conn.openAcked = true;
                synchronized (conn.lock) {
                    conn.lock.notifyAll();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "frame: " + e.getMessage());
        }
    }

    private static byte[] payloadOf(JSONObject obj, long messageId, JSONArray attachments) throws Exception {
        if (obj.has("payload_b64") && !obj.isNull("payload_b64")) {
            return b64d(obj.getString("payload_b64"));
        }
        if (!"doc".equals(obj.optString("blob"))) {
            return new byte[0];
        }
        JSONArray atts = attachments;
        if (atts == null && messageId != 0) {
            JSONObject msg = api().messageById(messageId);
            if (msg != null) {
                atts = msg.optJSONArray("attachments");
            }
        }
        if (atts == null) {
            throw new IOException("doc missing");
        }
        for (int i = 0; i < atts.length(); i++) {
            JSONObject att = atts.getJSONObject(i);
            if (!"doc".equals(att.optString("type"))) {
                continue;
            }
            String url = att.getJSONObject("doc").optString("url");
            if (url != null && !url.isEmpty()) {
                return api().download(url);
            }
        }
        throw new IOException("doc url missing");
    }

    private static void accept(Conn conn, int seq, byte[] payload) throws Exception {
        synchronized (conn.lock) {
            if (conn.closed || seq < conn.nextIn || seq > conn.nextIn + 32) {
                return;
            }
            conn.inbox.put(seq, payload);
            OutputStream out = conn.socket.getOutputStream();
            while (conn.inbox.containsKey(conn.nextIn)) {
                byte[] chunk = conn.inbox.remove(conn.nextIn);
                conn.nextIn++;
                out.write(chunk);
            }
            out.flush();
        }
    }

    private static void closeConn(Conn conn) {
        if (conn == null) {
            return;
        }
        conn.closed = true;
        synchronized (DISPATCH) {
            connections.remove(conn.id);
        }
        try {
            conn.socket.close();
        } catch (IOException ignored) {
        }
        synchronized (conn.lock) {
            conn.lock.notifyAll();
        }
    }

    private static JSONObject findPairOk(String deviceId) throws Exception {
        JSONArray items = api().history(peerId(), 30);
        for (int i = 0; i < items.length(); i++) {
            JSONObject msg = items.getJSONObject(i);
            if (msg.optInt("out") == 1) {
                continue;
            }
            String text = msg.optString("text").trim();
            if (!text.startsWith(PREFIX_PAIR)) {
                continue;
            }
            JSONObject obj = new JSONObject(text.substring(PREFIX_PAIR.length()));
            if (!"pair_ok".equals(obj.optString("type"))) {
                continue;
            }
            if (!deviceId.equals(obj.optString("device_id"))) {
                continue;
            }
            return obj;
        }
        return null;
    }

    private static boolean verify(JSONObject obj) throws Exception {
        String provided = obj.optString("sig", "");
        if (provided.isEmpty()) {
            return false;
        }
        String expect = hmac(canonical(obj), sessionKey());
        return constantEquals(provided, expect);
    }

    static String canonical(JSONObject obj) throws Exception {
        ArrayList<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String key = it.next();
            if ("sig".equals(key) || obj.isNull(key)) {
                continue;
            }
            keys.add(key);
        }
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String key = keys.get(i);
            sb.append(quote(key)).append(':').append(jsonValue(obj.get(key)));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonValue(Object value) throws Exception {
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isInfinite(d) && d == Math.rint(d)) {
                return Long.toString((long) d);
            }
            throw new IOException("unexpected float");
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof JSONObject) {
            return canonical((JSONObject) value);
        }
        throw new IOException("unexpected json " + value.getClass().getName());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String hmac(String canonical, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    private static boolean constantEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static byte[] b64d(String data) {
        return Base64.decode(data, Base64.URL_SAFE);
    }

    private static byte[] readCoalesced(InputStream in, int waitMs) throws Exception {
        byte[] tmp = new byte[256 * 1024];
        int n = in.read(tmp);
        if (n < 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(n, 32));
        out.write(tmp, 0, n);
        long deadline = System.currentTimeMillis() + (n >= 32 * 1024 ? 5 : waitMs);
        while (out.size() < MAX_FRAME && System.currentTimeMillis() < deadline) {
            int avail;
            try {
                avail = in.available();
            } catch (IOException e) {
                break;
            }
            if (avail > 0) {
                n = in.read(tmp);
                if (n <= 0) {
                    break;
                }
                out.write(tmp, 0, n);
                if (out.size() >= 32 * 1024) {
                    deadline = System.currentTimeMillis() + 5;
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return out.toByteArray();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = appCtx != null ? appCtx : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String deviceId() {
        SharedPreferences p = prefs();
        String id = p.getString("device_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private static String sessionKey() {
        return prefs().getString("session_key", "");
    }

    private static long peerId() {
        return -Math.abs(prefs().getLong("group_id", 0L));
    }

    private static Api api() {
        return new Api(prefs().getString("user_token", ""));
    }

    interface Updates {
        void onUpdates(JSONArray updates);
    }

    static final class Lp {
        final String server;
        final String key;
        final String ts;

        Lp(String server, String key, String ts) {
            this.server = server;
            this.key = key;
            this.ts = ts;
        }
    }

    static final class Conn {
        final String id = UUID.randomUUID().toString();
        final Socket socket;
        final String host;
        final int port;
        final Object lock = new Object();
        final Map<Integer, byte[]> inbox = new HashMap<>();
        int nextOut = 1;
        int nextIn = 1;
        volatile boolean openAcked;
        volatile boolean closed;

        Conn(Socket socket, String host, int port) {
            this.socket = socket;
            this.host = host;
            this.port = port;
        }
    }

    static final class Api {
        private final String token;

        Api(String token) {
            this.token = token;
        }

        JSONObject call(String method, Map<String, String> params) throws Exception {
            StringBuilder form = new StringBuilder();
            form.append("access_token=").append(url(token));
            form.append("&v=").append(url("5.199"));
            for (Map.Entry<String, String> e : params.entrySet()) {
                form.append('&').append(url(e.getKey())).append('=').append(url(e.getValue()));
            }
            byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = open("https://api.vk.ru/method/" + method, 30000, 120000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            return parse(conn, method);
        }

        void send(long peerId, String message, String attachment) throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("peer_id", Long.toString(peerId));
            params.put("random_id", Integer.toString(randomIds.incrementAndGet() & 0x7fffffff));
            params.put("message", message);
            params.put("dont_parse_links", "1");
            if (attachment != null) {
                params.put("attachment", attachment);
            }
            call("messages.send", params);
        }

        JSONArray history(long peerId, int count) throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("peer_id", Long.toString(peerId));
            params.put("count", Integer.toString(count));
            JSONObject resp = responseObject(call("messages.getHistory", params));
            JSONArray items = resp.optJSONArray("items");
            return items == null ? new JSONArray() : items;
        }

        JSONObject messageById(long id) throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("message_ids", Long.toString(id));
            JSONObject resp = responseObject(call("messages.getById", params));
            JSONArray items = resp.optJSONArray("items");
            if (items != null && items.length() > 0) {
                return items.getJSONObject(0);
            }
            return null;
        }

        Lp longPollServer() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("lp_version", "3");
            params.put("need_pts", "0");
            JSONObject resp = responseObject(call("messages.getLongPollServer", params));
            return new Lp(resp.getString("server"), resp.getString("key"), resp.get("ts").toString());
        }

        Lp longPoll(Lp lp, int waitSec, Updates updates) throws Exception {
            String base = lp.server.startsWith("http") ? lp.server : "https://" + lp.server;
            String url = base + "?act=a_check&key=" + url(lp.key) + "&ts=" + url(lp.ts)
                    + "&wait=" + waitSec + "&mode=2&version=3";
            HttpURLConnection conn = open(url, 20000, (waitSec + 15) * 1000);
            conn.setRequestMethod("GET");
            JSONObject json = parse(conn, "longpoll");
            if (json.has("failed")) {
                throw new IOException("LP failed=" + json.optInt("failed"));
            }
            JSONArray arr = json.optJSONArray("updates");
            if (arr != null && updates != null) {
                updates.onUpdates(arr);
            }
            return new Lp(lp.server, lp.key, json.get("ts").toString());
        }

        String uploadDoc(long peerId, String filename, byte[] content) throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("type", "doc");
            params.put("peer_id", Long.toString(peerId));
            JSONObject server = responseObject(call("docs.getMessagesUploadServer", params));
            String uploadUrl = server.getString("upload_url");
            String boundary = "vk" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            raw.write(head.getBytes(StandardCharsets.UTF_8));
            raw.write(content);
            raw.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] multipart = raw.toByteArray();
            HttpURLConnection up = open(uploadUrl, 30000, 180000);
            up.setRequestMethod("POST");
            up.setDoOutput(true);
            up.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream os = up.getOutputStream()) {
                os.write(multipart);
            }
            JSONObject uploaded = parse(up, "upload");
            Map<String, String> save = new HashMap<>();
            save.put("file", uploaded.getString("file"));
            save.put("title", filename);
            JSONObject saved = responseObject(call("docs.save", save));
            JSONObject doc = saved.optJSONObject("doc");
            if (doc == null && saved.has("owner_id")) {
                doc = saved;
            }
            if (doc == null) {
                throw new IOException("docs.save");
            }
            return "doc" + doc.getLong("owner_id") + "_" + doc.getLong("id");
        }

        byte[] download(String url) throws Exception {
            HttpURLConnection conn = open(url, 30000, 180000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (code >= 400 || stream == null) {
                throw new IOException("download HTTP " + code);
            }
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }

        private JSONObject responseObject(JSONObject root) throws Exception {
            Object resp = root.opt("response");
            if (resp instanceof JSONObject) {
                return (JSONObject) resp;
            }
            throw new IOException("no response object");
        }

        private HttpURLConnection open(String url, int connectMs, int readMs) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            return conn;
        }

        private JSONObject parse(HttpURLConnection conn, String method) throws Exception {
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                throw new IOException(method + " HTTP " + code);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            if (code >= 400 && !text.startsWith("{")) {
                throw new IOException(method + " HTTP " + code);
            }
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException(method + ": " + e.getMessage());
            }
            if (json.has("error") && json.opt("error") instanceof JSONObject) {
                JSONObject err = json.getJSONObject("error");
                throw new IOException("VK " + method + " [" + err.optInt("error_code") + "]: " + err.optString("error_msg"));
            }
            return json;
        }

        private static String url(String s) {
            try {
                return URLEncoder.encode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }
    }
}
