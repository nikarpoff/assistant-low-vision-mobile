# assistant-low-vision-mobile

Мобильное приложение системы голосового описания сцены для людей с нарушениями зрения: по касанию снимает сцену камерой, по удержанию записывает вопрос и проигрывает ответ Шлюза голосом.

## Назначение

Приложение - клиент [Шлюза](https://github.com/nikarpoff/assistant-low-vision-platform) по протоколу `alv.v1` (WebSocket):
- касание экрана - описание сцены, удержание - вопрос голосом, свайп вверх - пассивный режим с предупреждениями об опасностях;
- ответ звучит голосом сервиса синтеза речи, системные фразы заранее записаны и лежат в приложении;
- экран для слабовидящих: чёрный фон, крупный белый и жёлтый текст;
- снимки и аудио живут только в памяти и никогда не пишутся на диск.

Требования - в [docs/spec.md](docs/spec.md).

Состояние: версия 0.1.0 в разработке. Готов каркас: Gradle-проект, пакеты MVC, классы предметной области, экран-заглушка с состоянием «Ожидание» и CI. Камера, микрофон, сессия, жесты и воспроизведение появятся следующими изменениями.

## Стек технологий

- **Язык**: Kotlin 2.4, Android 8.0+ (minSdk 26), targetSdk и compileSdk 37
- **Сборка**: Gradle 9.8 (Wrapper), Kotlin DSL, каталог версий `gradle/libs.versions.toml`, Android Gradle Plugin 9.5
- **Интерфейс**: Jetpack Compose (BOM 2026.09.00), Material 3
- **Камера**: CameraX, только анализ кадров
- **Сеть**: OkHttp (WebSocket), kotlinx.serialization
- **Настройки**: DataStore Preferences
- **Детектор речи**: WebRTC VAD `com.github.gkonovalov.android-vad:webrtc` (MIT, JitPack)
- **Тесты**: JUnit 4 на JVM

Google Play Services не используются: приложение работает и на устройствах без них.

## Сборка

Нужны JDK 17 и Android SDK с пакетами `platforms;android-37.0` и `build-tools;37.0.0`. На машине разработки их ставит `scripts/setup-dev-env.sh` из репозитория Шлюза, путь к SDK лежит в `local.properties` (файл вне git):

```properties
sdk.dir=/opt/android-sdk
```

Команды:

```bash
./gradlew testDebugUnitTest   # JVM-тесты
./gradlew lintDebug           # Android Lint, отчёт в app/build/reports/lint-results-debug.html
./gradlew assembleDebug       # APK в app/build/outputs/apk/debug/
./gradlew --stop              # остановить демон после работы
```

Те же три задачи выполняет CI на GitHub Actions (`.github/workflows/ci.yml`) на JDK 17.

### Память

Сборка рассчитана на машину с 3 ГБ ОЗУ. В `gradle.properties` проекта:

| Параметр | Значение | Зачем |
|---|---|---|
| `org.gradle.jvmargs` | `-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8` | потолок кучи демона Gradle |
| `org.gradle.workers.max` | `2` | не больше двух параллельных воркеров |
| `org.gradle.parallel` | `false` | модули собираются по очереди |
| `org.gradle.caching` | `true` | кэш сборки |
| `kotlin.daemon.jvmargs` | `-Xmx512m` | потолок кучи демона Kotlin |

Демон Gradle завершается после 10 минут простоя: `org.gradle.daemon.idletimeout=600000` в `~/.gradle/gradle.properties`.

## Структура

Код разложен по шаблону MVC внутри пакета `io.github.nikarpoff.alv`:

```
app/src/main/kotlin/io/github/nikarpoff/alv/
├── model/        # чистый Kotlin без android.*: Scene, SceneObject, Answer, Settings, ErrorCode
├── controller/   # AssistantController: связывает Model и View
└── view/         # MainActivity, AssistantScreen, Theme на Compose
app/src/test/
├── kotlin/       # JVM-тесты Model и чистой логики контроллеров
└── resources/protocol/vectors.json   # вектора протокола, побайтная копия из репозитория Шлюза
```

Model не зависит от `android.*`, поэтому проверяется JVM-тестами без эмулятора.

## Лицензия

[Apache License 2.0](LICENSE).
