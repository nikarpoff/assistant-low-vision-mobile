# Changelog

### v0.1.0
- Добавлены вектора протокола `alv.v1` из репозитория Шлюза в `app/src/test/resources/protocol/vectors.json`.
- Добавлена выдержка из общей спецификации в `docs/spec.md`.
- Добавлен Gradle-проект на Kotlin DSL: Gradle Wrapper 9.8.2 с проверкой SHA-256, каталог версий `gradle/libs.versions.toml`, Android Gradle Plugin 9.5.0, Kotlin 2.4.21, Compose BOM 2026.09.00; `minSdk = 26`, compileSdk и targetSdk 37, build-tools 37.0.0, `applicationId` `io.github.nikarpoff.alv`, версия 0.1.0.
- Подключены зависимости приложения: Jetpack Compose и Material 3, CameraX, OkHttp, kotlinx.serialization, DataStore Preferences, WebRTC VAD с JitPack; без Google Play Services.
- В `gradle.properties` заданы лимиты памяти сборки: куча демона Gradle 1536 МБ, два воркера, без параллельной сборки модулей, демон Kotlin 512 МБ.
- Добавлены классы Model предметной области по KM-1 без `android.*`: `Scene`, `SceneObject`, `Answer`, `Settings` (пассивный режим по умолчанию выключен, токен скрыт в `toString`) и коды ошибок протокола `ErrorCode` (PROTO-11).
- Добавлен JVM-тест `SettingsTest`: значения настроек по умолчанию.
- Добавлены манифест (разрешения камеры, микрофона и сети, портретная ориентация, без резервного копирования), строки на русском, высококонтрастная тема и экран-заглушка на Compose: чёрный фон и состояние «Ожидание».
- Добавлен CI на GitHub Actions: JVM-тесты, Android Lint и отладочная сборка на JDK 17.
- README переписан на русском: назначение, стек, сборка, лимиты памяти, структура MVC.
