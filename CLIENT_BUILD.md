# Сборка клиента YogaRam

Клиент основан на Telegram для Android ([github.com/DrKLO/Telegram](https://github.com/DrKLO/Telegram), коммит `f32ffeec763f0bdbf4bec25b52d654f77bff733d`, версия 13.0.2). Лицензия GNU GPL версии 2, файл `LICENSE` не менялся. Это модифицированная программа: исходные копирайты Nikolai Kudashov в заголовках файлов сохранены.

## Что изменено

Подключение только к тестовому Opengram, без адресов Telegram.

| Параметр | Значение |
| --- | --- |
| ThisDcId | 1 |
| MediaDcId | 2 |
| DC1 | `46.8.46.56:20443` и имя `nandagram.myfbt.ru` |
| DC2 | `46.8.46.56:20543` |
| RSA | PKCS#1 ключ YogaRam, отпечаток `-7578694281596908264` (`0x96d311b45c21cd18`) |
| applicationId | `ru.myfbt.yogaram` |
| Имя | YogaRam (`AppName` / `AppNameBeta`) |
| Код входа | письмо на почту, указанную при регистрации |

Одинаковые адреса прописаны и для production, и для test backend. Официальные DC3–DC5 и IPv6 Telegram удалены. Домашний DC по умолчанию — 1. Медиа-соединения DC2 идут на порт 20543 (отдельный download-адрес и запасной общий адрес того же DC).

### Файлы сети

- `TMessagesProj/jni/tgnet/ConnectionsManager.cpp` — `initDatacenters()`, стартовый `currentDatacenterId = 1`. `help.getConfig` больше не подменяет адреса (`updateDcSettings` игнорирует `dc_options`). `applyDnsConfig` выбрасывает DNS/TXT simple-config.
- `TMessagesProj/jni/tgnet/Handshake.cpp` — единственный серверный RSA-ключ YogaRam и для обычного, и для test handshake.
- `TMessagesProj/jni/tgnet/Datacenter.cpp` — ключ расшифровки simple-config заменён, официальный ключ Telegram не расшифрует TXT-конфиг.
- `TMessagesProj/jni/tgnet/ConnectionSocket.cpp` — прямое соединение по имени хоста резолвится через системный DNS, не через Google.
- `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java` — не запускаются Google DNS TXT, Mozilla/Cloudflare DoH и domain fronting `https://www.google.com/resolve` с заголовком `Host: dns.google.com`.
- `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java` — `dcDomainName` пустой (не `apv3.stel.com` / `tapv3.stel.com` и не значение из `help.getConfig`). `webFileDatacenterId` только 1 или 2, иначе 2.

В этой версии исходников нет Firebase Remote Config, который подтягивает DC. Канал Firebase/Google, через который официальный клиент получает запасные DC, — это TXT через dns.google.com и domain fronting; оба отключены. `google-services.json` больше не указывает на проект Telegram `tmessages2`.

Флаг test backend в отладочном меню по-прежнему меняет номер DC внутри рукопожатия на `10000 + id` (для DC1 это 10001). Для этого сервера оставьте его выключенным: адреса и так наши, а идентификатор DC должен остаться 1.

Регистрация: номер телефона, затем почта, затем код из письма. После подтверждения клиент сразу вызывает `auth.signIn` с этим же кодом. Новый аккаунт задаёт пароль облачной защиты, почта восстановления — та, что подтвердили. Вход в существующий аккаунт присылает код на эту почту. «Забыли пароль» вызывает `auth.requestPasswordRecovery`: код приходит на ту же почту, `auth.recoverPassword` ставит новый пароль. Серверное наложение `server/opengram-overlay` собрано для Opengram `e64a35e`. Его накладывает `server/apply-opengram-overlay.sh`, SMTP берётся из `server/email.appsettings.sample.json` (`App:Email` и `App:EnableEmailLogin`). Файл `App:Email:StorePath` должен быть доступен query-серверу: в нём лежат привязки почты. Пока SMTP не задан, отправка отвечает `EMAIL_SEND_FAILED`.

### Ребренд и секреты

- `gradle.properties` — `APP_PACKAGE=ru.myfbt.yogaram`, `YOGARAM_APP_ID`, `YOGARAM_APP_HASH`.
- `TMessagesProj/build.gradle` — `BuildConfig.APP_ID` / `APP_HASH` из этих свойств. Значения по умолчанию — публичные тестовые `4` / `014b35b6184100b085b0d0572f9b5103`, не боевые ключи Telegram.
- `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java` — читает `BuildConfig`. SafetyNet, Google OAuth, Huawei App Id и ссылки на магазины Telegram очищены. Passkeys и Play Billing выключены.
- `TMessagesProj_App/build.gradle` — у debug-сборки снят суффикс `.beta`, пакет ровно `ru.myfbt.yogaram`.
- `google-services.json` во всех модулях — заглушка проекта `yogaram-stub`, без ключей Telegram.
- `TMessagesProj_AppHuawei/agconnect-services.json` — заглушка, секрет Huawei Telegram удалён.
- `TMessagesProj/config/*/AndroidManifest*.xml` — ключ Google Maps Telegram убран.
- Строки `AppName` и `AppNameBeta` во всех `strings.xml` — YogaRam. Остальной текст интерфейса местами всё ещё говорит «Telegram».

## Как собрать debug APK

Нужны JDK 17 или новее, Android SDK 36, Build-Tools 36.0.0, NDK `27.2.12479018`, CMake 3.22.1. Нативные библиотеки ffmpeg/boringssl/opus и другие уже лежат в `TMessagesProj/jni/prebuild`, но заголовки и часть исходников (absl, libyuv, tgcalls, opus, boringssl include, media3, jlatexmath) приходят сабмодулями.

```bash
sudo apt-get install -y openjdk-17-jdk git unzip wget
# Android command-line tools: https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME="$HOME/Android/Sdk"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
bash scripts/init-submodules.sh
chmod +x gradlew
./gradlew :TMessagesProj_App:assembleAfatDebug
```

APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`.

Проверенная сборка в этой среде: `assembleAfatDebug` завершилась успешно. Пакет `ru.myfbt.yogaram`, имя YogaRam, versionName `13.0.2`, native ABI `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. Заглушка `google-services.json` содержит и `ru.myfbt.yogaram`, и `org.telegram.messenger`: плагин Google Services в библиотечном модуле сверяет package с namespace библиотеки.

Свой `api_id` / `api_hash` задаются в `gradle.properties` (`YOGARAM_APP_ID`, `YOGARAM_APP_HASH`) до сборки.

## Если сборка в этой среде не дошла до APK

Исходники уже переключены на Opengram. Чтобы получить APK локально, остаются только шаги выше: поставить SDK/NDK/CMake, выполнить `scripts/init-submodules.sh` (сабмодули в git не вложены, пины в `submodule-pins.txt`) и запустить `assembleAfatDebug`. Нативная сборка tgcalls, webrtc и absl долгая и требует несколько гигабайт диска и памяти Gradle (`org.gradle.jvmargs` уже `-Xmx8g`).

## Брендинг Nandagram Goloka Messenger (ветка feature/nandagram-branding)

- `scripts/nandagram/branding.py` — полный `values-ru/strings.xml` из официального экспорта translations.telegram.org (`scripts/nandagram/ru_android_export.xml`), замена пользовательского «Telegram» на «Nandagram Goloka Messenger» во всех `values*/strings.xml` (плейсхолдеры, URL, @-имена не трогаются), `AppName`/`AppNameBeta` = `Nandagram`.
- `scripts/nandagram/gen_icons.py` — иконка (золотая тилака урдхва-пундра на тёмно-синем фоне) генерируется из SVG: адаптивные fg/bg, legacy и round mipmap, монохромная, уведомления, все альтернативные иконки. Требует `pip install cairosvg pillow`.
- `IntroActivity` — заголовок первой страницы: «Nandagram», ниже «Goloka Messenger» шрифтом в 3 раза меньше.
- Русский интерфейс включается автоматически при системном языке ru (встроенный ru LocaleInfo + values-ru); сервер Opengram тоже отдаёт ru/en langpack.

Сборка на сервере 46.8.46.56: `/srv/nandagram-build/build.sh` (Docker-образ `nandagram-builder:1`).
