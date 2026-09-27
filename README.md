# RuRoad Кадастр — Android

Приложение-карта (WebView-обёртка https://ruroad.pik-sev.ru/karta/) + виджет поиска
по кадастровому номеру или адресу (НСПД) с построением маршрута.

## Сборка

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Обновления

Релизы публикуются в [Releases](../../releases). Приложение само проверяет
обновления через GitHub API и предлагает установить новую версию.
