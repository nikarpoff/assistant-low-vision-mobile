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
- Добавлен кодек протокола `alv.v1` в `model.protocol` (PROTO-3 - PROTO-5): бинарные кадры `Frame` с заголовком 22 байта, все сообщения приложения и Шлюза на kotlinx.serialization, `ProtocolCodec` с ошибкой `bad_request` на битые кадры и сообщения; JVM-тест `ProtocolCodecTest` проходит все разделы общих векторов `vectors.json`.
- Добавлен автомат состояний ассистента `AssistantStateMachine` (KM-2) с `State`, `Event` и `Action`: переходы T1-T21 с действиями, сигнал «недоступно» на игнорируемые жесты, пассивный режим только в S1; JVM-тест `AssistantStateMachineTest`.
- Добавлен `PlaybackClock` (MOB-7): начало звучания ответа по позиции воспроизведения и частоте, а не по записи в буфер; JVM-тест `PlaybackClockTest`.
- Добавлен `ClientMetricsBuilder` (MOB-13): `client.metrics` с метриками PLT-14 и триггером по виду запроса после ответа и ошибки, без сообщения после отмены и `passive.clear`; JVM-тест `ClientMetricsBuilderTest`.
- Добавлена чистая логика контроллеров без `android.*`: распознавание жестов по таблице MOB-3 (`GestureClassifier`: касание сразу по отпусканию, второе касание в пределах 300 мс - двойное), начало и конец речи (`SpeechEndpointer`: предзапись 300 мс, 700 мс тишины, окно `listen_window_ms`, лимит `max_question_ms`, удержание с речью длиннее 300 мс), размер снимка по `image_max_side` с поворотом (`FrameScaler`), паузы переподключения 0,5-8 с с разбросом ±20 % (`ReconnectPolicy`), последовательность «объяснение → запрос → результат» для разрешений (`PermissionFlow`) и пассивный режим, выключенный при первом запуске (`PassiveModeSetting`).
- Добавлены системные фразы: список `phrases/phrases.yaml`, WAV-заглушки `res/raw/phrase_*.wav` от `tts-service` на бэкенде `fake`, строки `phrase_*` и выбор фразы по коду ошибки PROTO-11 (`ErrorPhrases`, `SystemPhrases`).
- Добавлены JVM-тесты `GestureClassifierTest`, `SpeechEndpointerTest`, `FrameScalerTest`, `ReconnectPolicyTest`, `PermissionFlowTest`, `PassiveModeSettingTest` и `ErrorPhrasesTest`.
