package org.telegram.messenger.vk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VK login plus the Mini App pair code. Shown until {@link VkTunnel} has a session.
 */
public class VkPairActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText appId;
    private EditText groupId;
    private EditText miniUrl;
    private EditText token;
    private EditText code;
    private TextView status;
    private LinearLayout form;
    private WebView web;
    private ScrollView scroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);

        status = text(15);
        status.setTextColor(Color.DKGRAY);

        appId = field("ID приложения VK (для входа)");
        groupId = field("ID сообщества");
        groupId.setInputType(InputType.TYPE_CLASS_NUMBER);
        miniUrl = field("Ссылка Mini App");
        token = field("Токен VK (заполнится после входа)");
        code = field("Код из Mini App");

        appId.setText(VkTunnel.savedAppId());
        groupId.setText(VkTunnel.savedGroup());
        miniUrl.setText(VkTunnel.savedMiniUrl());
        token.setText(VkTunnel.savedToken());

        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);
        form.addView(title());
        form.addView(text(15));
        ((TextView) form.getChildAt(1)).setText(
                "Телефон ходит только на домены VK. Войдите в VK, откройте Mini App сообщества, "
                        + "введите код и нажмите «Связать».");
        form.addView(appId);
        form.addView(groupId);
        form.addView(miniUrl);
        form.addView(button("Войти через VK", v -> openLogin()));
        form.addView(token);
        form.addView(button("Открыть Mini App", v -> openMini()));
        form.addView(code);
        form.addView(button("Связать", v -> startPair()));
        if (VkTunnel.isPaired()) {
            form.addView(button("Продолжить", v -> {
                VkTunnel.applyToConnections(this);
                finish();
            }));
            form.addView(button("Отвязать", v -> {
                VkTunnel.unpair(this);
                status.setText("Связка сброшена");
            }));
            status.setText("Уже связано. Можно продолжить или ввести новый код.");
        }
        form.addView(status);

        scroller = new ScrollView(this);
        scroller.addView(form);

        web = new WebView(this);
        web.setVisibility(View.GONE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return capture(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return capture(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                capture(url);
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        Button closeWeb = button("Закрыть вход", v -> showForm());
        closeWeb.setVisibility(View.GONE);
        closeWeb.setTag("close-web");
        root.addView(closeWeb);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private boolean capture(String url) {
        if (url == null) {
            return false;
        }
        int i = url.indexOf("access_token=");
        if (i < 0) {
            return false;
        }
        String rest = url.substring(i + "access_token=".length());
        int amp = rest.indexOf('&');
        if (amp >= 0) {
            rest = rest.substring(0, amp);
        }
        token.setText(Uri.decode(rest));
        status.setText("Токен получен. Откройте Mini App и введите код.");
        showForm();
        return true;
    }

    private void showForm() {
        web.setVisibility(View.GONE);
        scroller.setVisibility(View.VISIBLE);
        View close = ((LinearLayout) scroller.getParent()).findViewWithTag("close-web");
        if (close != null) {
            close.setVisibility(View.GONE);
        }
    }

    private void openLogin() {
        String id = appId.getText().toString().trim();
        if (id.isEmpty()) {
            status.setText("Укажите ID приложения VK");
            return;
        }
        String url;
        try {
            url = "https://oauth.vk.com/authorize?client_id=" + URLEncoder.encode(id, "UTF-8")
                    + "&display=page&redirect_uri=" + URLEncoder.encode("https://oauth.vk.com/blank.html", "UTF-8")
                    + "&scope=" + URLEncoder.encode("messages,docs,offline", "UTF-8")
                    + "&response_type=token&v=5.199";
        } catch (Exception e) {
            status.setText(e.getMessage());
            return;
        }
        scroller.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        View close = ((LinearLayout) scroller.getParent()).findViewWithTag("close-web");
        if (close != null) {
            close.setVisibility(View.VISIBLE);
        }
        web.loadUrl(url);
    }

    private void openMini() {
        String url = miniUrl.getText().toString().trim();
        if (url.isEmpty()) {
            String group = groupId.getText().toString().trim();
            if (!group.isEmpty()) {
                url = "https://vk.com/im?sel=-" + group.replace("-", "");
            }
        }
        if (url.isEmpty()) {
            status.setText("Укажите ссылку Mini App");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            status.setText(e.getMessage());
        }
    }

    private void startPair() {
        status.setText("Связка…");
        final String app = appId.getText().toString().trim();
        final String groupText = groupId.getText().toString().trim();
        final String mini = miniUrl.getText().toString().trim();
        final String tok = token.getText().toString().trim();
        final String pairCode = code.getText().toString().trim();
        io.execute(() -> {
            try {
                long group = parseGroup(groupText);
                VkTunnel.pair(this, group, tok, pairCode, app, mini);
                runOnUiThread(() -> {
                    VkTunnel.applyToConnections(this);
                    status.setText("Связано");
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(e.getMessage() == null ? "ошибка связки" : e.getMessage()));
            }
        });
    }

    private static long parseGroup(String raw) {
        String s = raw.trim().toLowerCase();
        if (s.startsWith("club")) {
            s = s.substring(4);
        } else if (s.startsWith("public")) {
            s = s.substring(6);
        }
        if (s.startsWith("-")) {
            s = s.substring(1);
        }
        return Long.parseLong(s);
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE) {
            showForm();
            return;
        }
        finishAffinity();
    }

    private TextView title() {
        TextView t = text(22);
        t.setText("Nandagram VK");
        t.setTextColor(Color.BLACK);
        t.setGravity(Gravity.START);
        return t;
    }

    private TextView text(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Color.BLACK);
        e.setHintTextColor(Color.GRAY);
        return e;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
