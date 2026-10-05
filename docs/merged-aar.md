# Объединённый SDK

Текущая реализация использует один обычный Android library-модуль `:sdk`.
Проверяемая пара версий: Gradle 9.8.0 / AGP 9.4.0.

## Pipeline

1. `validateDependencies` проверяет, что runtime-граф полностью учтён в embed/API/runtime зависимостях.
2. ABI transform проверяет внешний публичный JVM API SDK и вложенных классов.
3. `merge<Variant>FatAar` преобразует `SingleArtifact.AAR` через публичный AGP Artifacts API.
4. `validate<Variant>Linkage` проверяет статические ссылки из конечного байткода на выбранный runtime.
5. `inspect<Variant>Aar` читает конечный объединённый архив.
6. Компонент `fatRelease` предоставляет AAR, POM и Gradle Module Metadata с внешними scopes.

Входы merger — собственный AAR и явный список AAR/JAR из `embed`.
Разрешение версий согласовано с runtime configuration. Внутренние project dependencies
должны применять `com.fataar.embeddable`: plugin предоставляет variant archive через
отдельную capability. Плагин не обращается к задачам соседних проектов по имени.

`MergeAarTask` и извлечение merged classes объявляют входы/выходы и поддерживают build cache.
TestKit проверяет повторное использование configuration cache, UP-TO-DATE,
FROM-CACHE, перенос каталога и изменение входных данных.

## Подключение к проекту и Maven

Project consumer выбирает `com.fataar.playground:sdk-fat`. Основной артефакт — merged AAR.
Runtime-артефакт `android-classes-jar` извлекается из того же конечного AAR и нужен
для dexing зависимостей между проектами. API-вариант предоставляет отдельный compile JAR
с non-final R stubs из объединённого R.txt; они не входят в runtime/AAR. Тип артефакта — соглашение AGP, покрытое
интеграционной проверкой закреплённой версии.

Один AAR без этого вторичного артефакта приводил к отсутствию классов SDK в debug APK.
Обычный вторичный `jar` исправлял dexing, но приводил к ошибке модели lint: AGP выбирал
JAR как Android project одновременно с exploded AAR. Тип `android-classes-jar` сохраняет
отдельные пути выбора для dexing и lint.

Maven consumer использует обычные координаты, без дополнительных capabilities.
Publication variants не содержат build-type attributes: опубликованный release SDK
подходит и debug-, и release-приложению. `api` публикуется как compile, `implementation`
как runtime; embed в POM/module не попадает. Сгенерированные артефакты можно подписывать
стандартным Gradle signing plugin.

## Политика объединения

- Классы объединяются в `classes.jar`; дубли имён классов запрещены.
- R-ссылки embedded namespace и SDK переводятся на R SDK. Проверяется наличие символов.
- Имена ресурсов не меняются. Меняются только имена файлов values XML внутри AAR,
  чтобы файлы разных модулей не перезаписывали друг друга.
- Одинаковые ресурсы объединяются; разные определения требуют явного выбора в
  `resourceWinners`. Несовместимые styleable остаются ошибкой из-за inlined indices.
  [Политика ресурсов и проверка бинарных ссылок](resource-and-linkage-validation.md).
- Manifest объединяется Android Manifest Merger. Embedded minSdk не может превышать SDK minSdk.
- Asset/JNI/Java resource с одинаковым путём и разным содержимым — ошибка.
- ServiceLoader descriptors объединяются; каждый provider должен находиться среди embedded classes.
- Consumer rules объединяются, Kotlin module-файлы получают уникальные имена.
- Порядок ZIP entries и timestamps фиксированы.

## Границы поддержки

Это проверенный первый merger, а не поддержка всех Android-возможностей.
Неизвестные AAR entries
отклоняются с `FATAAR_UNSUPPORTED_PAYLOAD`. Signed и multi-release JAR, packaged R
и preview minSdk отклоняются явно. JNI/Prefab проверяются реальными AAR/CMake consumers:
четыре ABI, loader dependencies, shared/static/header-only и runtime после R8.
[Контракт и границы native проверки](native-validation.md). Полного imported symbol linkage
пока нет. Крупные архивы обрабатываются в памяти. Нагрузочные проверки на
50/100/200 модулях пройдены; [результаты и расход памяти](scale-and-compatibility.md).

Объединённый [sources JAR](sources-validation.md) и обычные [BOM](bom-validation.md)
реализованы и проверены. Нет общей class relocation, переписывания Kotlin metadata
и полного JVM verifier. Статические ссылки на классы/методы/поля проверяются отдельным
валидатором финального AAR. Сложные capabilities/rich constraints требуют
дополнительного покрытия; enforcedPlatform явно отвергается.
Наличие классов не доказывает корректность reflection или JNI: для этого нужны runtime fixtures.

## Воспроизведение

```sh
python3 scripts/verify-merged.py
python3 scripts/verify-device.py --serial emulator-5554
```

Первый отчёт: `build/reports/merged-validation/result.json`. Проверки определений классов
читают DEX class_defs, а не список строк/ссылок на типы: отсутствующий класс может
присутствовать среди ссылок и всё равно приводить к ClassNotFoundException.
Поддержаны стандартные DEX 035–040; неизвестный формат завершается ошибкой.

Второй отчёт: `build/reports/playground-device-validation/result.json`. На каждом варианте
должны реально выполниться семь smoke/resource/Manifest/generated-code тестов: ресурсы, строковый lookup, SPI, reflection,
компоненты Manifest, XML custom View и применение styleable-атрибута после R8.

## Результаты 1 октября 2026

Полный автоматический прогон `verify-all.py` завершился со статусом PASS.
[Матрица проверок](automated-validation.md) и [контракт Manifest](manifest-validation.md).

- 29 JVM/TestKit тестов плагина и 2 теста чтения DEX: без ошибок и пропусков.
- 30 проверок реальных SDK-артефактов, публикации и кешей завершились успешно.
- Определения всех 22 классов SDK найдены в merged AAR и debug APK consumers.
- Все 19 res/assets payload сохранены с тем же содержимым.
- Configuration cache переиспользуется, merge восстанавливается FROM-CACHE.
- Пересборка без build cache даёт тот же SHA-256 AAR:
  `6a8cd24a64e670aedcbfb3696707a0db1a3544ac709fa3afb48878f34a391e4e`.
- Подписи AAR/POM/module проверены; изменённый архив отклоняется.
- API/implementation scopes сохранены; внутренних modules в публикации нет.
- Embed версии 1 и runtime constraint версии 2 приводят к упаковке версии 2.
- Отрицательные сборки отвергают отсутствующий API, конфликт ресурсов, конфликт Manifest
  attributes, duplicate Provider authorities и несовместимый minSdk.
- Manifest сохраняет relative class names как полные имена исходного namespace,
  tools:replace/remove и отложенные placeholders. Query provider не считается компонентом.
- Android 15, Pixel_9_Pro_XL_API_35: 24 instrumentation tests прошли для project,
  Maven metadata и POM-only consumer, каждый в debug и R8 release.
- На устройстве запускаются Activity и Service, отвечают Receiver и Provider.
  PackageManager подтверждает exported, metadata, permissions и подстановку placeholders.
- Ресурсные тесты проверили 48 сочетаний en/ru × day/night × portrait/landscape.
- Запущенный скриптом эмулятор закрывается после завершения, включая ошибочный прогон.

Результаты относятся к полигону и закреплённым версиям. Fonts/media, дополнительные
Manifest-сценарии, дополнительные AIDL/bindings и Compose, external native/STL, дополнительные flavor-сценарии и relocation ещё требуют
отдельного расширения [покрытия](playground-matrix.md).

Flavor scopes и публикация отдельных вариантов: [variant-validation.md](variant-validation.md).

AIDL и Binding payload: [generated-code-validation.md](generated-code-validation.md).
