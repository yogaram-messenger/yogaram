# Nandagram VK

Отдельная установка: `applicationId` `ru.myfbt.yogaram.vk`, имя «Nandagram VK», versionName `13.0.2-vk`. Рядом с `ru.myfbt.yogaram`.

Телефон не открывает TCP на `46.8.46.56`. tgnet ходит в локальный SOCKS5 `127.0.0.1`, байты MTProto уезжают сообщениями сообщества с префиксом `VKVPN_MT:` на шлюз VK_VPN. Шлюз держит обычный TCP только до `46.8.46.56:20443` (DC1) и `:20543` (DC2).

Сборка debug, только `arm64-v8a`:

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
bash scripts/init-submodules.sh
./gradlew :TMessagesProj_App:assembleAfatDebug
```

APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`.

Перед первым запуском — экран связки: вход VK (scope `messages`, `docs`, `offline`), код из Mini App. Пока связки нет, сеть мессенджера выключена. Что поднять на сервере и в VK: репозиторий шлюза, `docs/nandagram-vk.md`.
