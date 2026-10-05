# FatAarPlugin

Gradle-плагин и Android-полигон для сборки одной библиотеки из нескольких модулей.
Один `:sdk` содержит фасад, исходники, Manifest, ресурсы и публикацию. Плагин уже
создаёт объединённый AAR; демо и отдельный Maven consumer получают классы и ресурсы
внутренних модулей из него.

## Окружение

- Gradle **9.8.0**, AGP **9.4.0**, Android compile/target SDK **36**, min SDK **24**.
- JDK **21** для Gradle; JDK **17** для toolchain плагина/JVM fixtures.
- Kotlin для Android встроен в AGP.
- Build cache и configuration cache включены; проблемы configuration cache — ошибка сборки.
- Android SDK: `local.properties` с `sdk.dir` либо `ANDROID_HOME`.
- Для JNI/Prefab-полигона: NDK **25.1.8937393**, CMake **3.22.1**.

Версии закреплены, дистрибутив Gradle проверяется по SHA-256. На macOS ARM64 проверена
матрица Gradle 9.6.0/9.8.0 × JDK 17/21; [сценарий и границы проверки](docs/scale-and-compatibility.md).

## Подключение

SDK применяет `com.android.library` и `com.fataar`. Внутренние Android/JVM проекты
применяют `com.fataar.embeddable`, который предоставляет полный архив выбранного варианта.
В полигоне это делает convention plugin.

```kotlin
// sdk/build.gradle.kts
plugins {
    id("com.android.library")
    id("com.fataar")
    id("maven-publish")
    id("signing")
}
dependencies {
    add("embed", project(":fixtures:android-core"))
    api("androidx.constraintlayout:constraintlayout:2.2.1")
}
fatAar { withSourcesJar() }
```

Каждая внутренняя транзитивная зависимость должна быть явно учтена. Задача
`:sdk:addMissingDependencies` добавляет однозначные пропуски в registry;
неоднозначные решения остаются ошибкой с объяснением.
[Контракт валидатора](docs/dependency-validation.md).
BOM учитываются отдельно от библиотек; [политика и реальные проверки](docs/bom-validation.md).

Внутри этого проекта демо выбирает capability объединённого SDK:

```kotlin
implementation(project(":sdk")) {
    capabilities { requireCapability("com.fataar.playground:sdk-fat") }
}
```

Внешний consumer подключает `implementation("com.fataar.playground:sdk:0.2.0-merged")`.
Для Manifest fixture он также задаёт `android.defaultConfig.manifestPlaceholders["fataarChannel"]`;
[пример и контракт](docs/manifest-validation.md).
Публикация использует компонент `fatRelease`; `api` попадает в compile scope POM,
`implementation` — в runtime. Внутренние модули в метаданные не включаются.
Полный пример публикации находится в `sdk/build.gradle.kts`.

## Проверка

Полный автоматический прогон — одной командой:

```sh
python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35
```

Он создаёт реальные AAR/APK, проверяет их и запускает тесты на эмуляторе для project,
Maven metadata и POM-only consumer в debug/release. Эмулятор запускается и закрывается
автоматически. Дополнительно проверяются шесть flavor/build-type AAR и их project/Maven
consumers на устройстве; [контракт вариантов](docs/variant-validation.md). Для своего устройства используйте `--serial`. Итог:
`build/reports/full-validation/result.json`. [Матрица проверок](docs/automated-validation.md).

Отдельные этапы:

```sh
python3 scripts/verify-merged.py
python3 scripts/verify-variants.py
python3 scripts/verify-external-bindings.py
python3 scripts/verify-native.py
python3 scripts/verify-bom.py
python3 scripts/verify-sources.py
python3 scripts/verify-resource-linkage.py
python3 scripts/verify-scale.py
python3 scripts/verify-compatibility.py --gradle-version 9.8.0 --expected-jdk 21
python3 scripts/verify-device.py --serial emulator-5554 --consumer maven
```

Первый скрипт проверяет debug, R8 release, lint, тесты плагина, оба кеша,
воспроизводимость архива, подписанные AAR/POM/module, отдельного consumer в режимах
Gradle metadata и POM-only, наличие определений классов в debug APK и отрицательный
сценарии отсутствующего API, конфликта ресурсов и Manifest/minSdk. Repository содержит только SDK и не предоставляет внутренние модули.
Отчёт: `build/reports/merged-validation/result.json`.

`verify-device.py` запускает семь smoke/resource/Manifest/generated-code тестов в каждом варианте на устройстве и проверяет
XML-результаты: нулевой прогон, пропуск или ошибка не считаются успехом.
Отчёт: `build/reports/<playground|maven|maven-pom>-device-validation/result.json`.
`verify-baseline.py` сохранён как совместимый вход в `verify-merged.py`.

```sh
./gradlew verifyPlayground :app:assembleDebugAndroidTest
./gradlew :sdk:inspectReleaseAar
./gradlew :sdk:validateDependencies
./gradlew :sdk:addMissingDependencies
./gradlew :sdk:mergeReleaseFatAarSources
```

Для подписи передайте `-PfatAar.signingKeyFile=/absolute/path/secret.asc`
и при необходимости `-PfatAar.signingPassword=...`. Проверочный скрипт создаёт временный
тестовый ключ под `build/`; это не ключ для выпуска библиотеки.

## Что объединяется

Классы и Java resources, ServiceLoader descriptors, Android resources, assets,
Manifest и consumer ProGuard rules. Ссылки на R внутренних namespace преобразуются
в R SDK, включая ссылки из самого SDK. Имена ресурсов остаются прежними, поэтому
строковый lookup продолжает работать. Одинаковые ресурсы объединяются, для разных
определений можно задать явный `resourceWinners`; несовместимые styleable отклоняются.

Плагин проверяет граф зависимостей, публичные/protected JVM-сигнатуры и ссылки на
классы/методы/поля из конечного байткода. Это ещё не полный JVM verifier и не проверка
Kotlin metadata или динамических ссылок.
[Конфликты ресурсов и диагностика бинарных ссылок](docs/resource-and-linkage-validation.md).
[Устройство merger и ограничения](docs/merged-aar.md).
[Проверки Manifest](docs/manifest-validation.md).
[AIDL, ViewBinding и DataBinding](docs/generated-code-validation.md).
[JNI/Prefab, CMake-потребитель и подпись конечного AAR](docs/native-validation.md).
[Объединённый sources JAR, варианты, кеши и локальная подпись](docs/sources-validation.md).
[Масштаб и совместимость сборки](docs/scale-and-compatibility.md).

## Структура и дальнейшая работа

| Путь | Назначение |
| --- | --- |
| `plugin-build/` | Плагин, convention plugins, JVM/TestKit проверки |
| `sdk/` | Единственный SDK: код, ресурсы, merge, публикация |
| `app/` | Демо с одной production-зависимостью на SDK |
| `fixtures/` | 16 модулей с разными файлами и графом зависимостей |
| `integration-tests/consumer-maven/` | Независимый Maven consumer |
| `integration-tests/variants/`, `variants-consumer/` | Изолированный полигон flavors, scoped embed и Maven-публикации |
| `integration-tests/external-bindings/`, `external-bindings-consumer/` | Внешний DataBinding AAR, транзитивный API SDK и независимый consumer |
| `integration-tests/native/`, `native-consumer/` | JNI, shared/static/header-only Prefab для четырёх ABI, подписанный SDK и CMake consumers |
| `integration-tests/bom/`, `bom-consumer/` | Вложенные BOM, версия встроенного JAR и отдельные metadata/POM consumers |
| `scripts/verify-scale.py` | Генератор/проверка настоящих проектов на 50/100/200 Android-модулях |
| `.github/workflows/validation.yml.example` | Неактивный шаблон CI: матрица версий, полный полигон, большие проекты |
| `docs/playground-matrix.md` | Покрытие и ещё не проверенные возможности |
| `docs/merger-candidate-results.md` | Сравнение Fused/UmbrellaAAR и выбор собственного merger |
| `docs/fat-aar-research-and-plan.md` | Исходное исследование и архитектурный план |

Следующие этапы: расширение отрицательных сценариев и поддержки Android payload
(external native/STL, дополнительные binding-сценарии), затем репакетирование классов с отдельной
политикой для reflection/JNI. Переименование ресурсов и KSP исключены из области работ.
Документы про baseline и сравнение кандидатов сохраняют исторические результаты.

Внешние DataBinding-зависимости проверяются отдельным Maven-полигоном: SDK экспортирует
их через `api`, consumer объявляет только SDK. [Контракт](docs/external-binding-validation.md).
