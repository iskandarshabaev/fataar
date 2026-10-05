# Fat AAR: исследование, архитектура и план тестового полигона

**Дата проверки: 5 сентября 2026 года.** Для проекта FatAarPlugin и разработчика собственного Android SDK.

Это исходное проектное решение на основе документации и чтения исходников. После исследования реализованы модульный полигон и проверка графа/JVM API; фактическое состояние описано в [README](../README.md) и [документации валидатора](dependency-validation.md). **6 сентября завершено [практическое сравнение Fused 9.4.0 и UmbrellaAAR 3.0.0](merger-candidate-results.md): выбран собственный merge pipeline.** Production-объединение остаётся следующим этапом. История первого этапа — в [отчёте реализации](playground-validation.md).

**Уточнение объёма работ 2 октября 2026:** KSP исключён по запросу пользователя.
Упоминания KSP ниже относятся к исходному исследованию, а не к текущим обязательствам.
Объединение, публикация, подпись и JNI/Prefab уже реализованы; актуальное состояние
и ограничения находятся в README.

## Рекомендация

Начать с независимого тестового полигона и собственного слоя проверки зависимостей. На небольшом одинаковом наборе примеров сравнить Fused Library и UmbrellaAAR, затем выбрать реализацию объединения. Старый fat-aar не брать за основу управления сборкой. Обязательна Maven-публикация с полноценными внешними `api` и `implementation` зависимостями: потребитель объявляет только SDK. Смена пакетов классов включена в архитектуру и критерии выбора. Переименование Android-ресурсов исключено из текущего объёма работ: их исходные имена сохраняются, в том числе для обращений по строковому имени.

При обязательной поддержке Data Binding, Prefab, произвольных локальных AAR, Android-aware relocation и строгой проверки каждого типа содержимого нужно закладывать собственные операции преобразования и объединения. Готового опубликованного решения с подтверждённым выполнением всех этих требований, configuration cache и автоматическим исправлением состава SDK я не нашёл. Это результат проверенного набора кандидатов, а не утверждение об отсутствии любых решений в интернете.

**Первый результат разработки — полигон, который умеет обнаруживать ошибки упаковки независимо от выбранного плагина.** Он позволит переиспользовать готовый merger там, где тот проходит проверки, и точно увидеть, что требуется дописать.

## Исходное состояние и инструменты

По локальным файлам: подключён только `:app`, исходников Activity нет; есть manifest, ресурсы шаблона и стандартные тесты. В каталоге отсутствует Git-репозиторий. AGENTS.md в проекте и проверенных родительских каталогах не найден.

| Параметр | До начала работ | Предложение для полигона |
|---|---|---|
| AGP | 8.9.2 | 9.4.0, зафиксированная версия |
| Gradle Wrapper | 8.11.1 | 9.7.1, с проверкой checksum дистрибутива |
| Kotlin | внешний Android plugin 2.0.21 | встроенный Kotlin AGP 9; compiler plugins согласовать отдельно |
| JVM target | 11 | единый явно заданный target; для начального стенда 17 |
| JDK для Gradle | фактически не проверен | 17 как минимальная базовая среда; отдельная проверка на 21 |
| compileSdk / targetSdk приложения | 35 / 35 | начать с проверенного установленного SDK, отдельно проверить API 37 |
| minSdk | 24 | оставить 24 у демо; отрицательный пример несовместимого minSdk |
| Build/configuration cache | явно не включены | включить сразу и считать проблемы ошибками |

AGP 9.4.0 указан как текущий релиз; он требует Gradle не ниже 9.6.0, JDK 17 и поддерживает API до 37. Endpoint Gradle возвращает 9.7.1 как выпущенный текущий релиз. **Пара 9.4.0 + 9.7.1 проверена на модульном полигоне с JDK 21 и Android SDK 36; это ещё не проверка будущего merger.** [Android API releases](https://developer.android.com/reference/tools/gradle-api), [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Gradle current release](https://services.gradle.org/versions/current).

При переходе на AGP 9 убрать `org.jetbrains.kotlin.android` из Android-модулей, мигрировать `kotlinOptions` на актуальную конфигурацию компилятора. Для JVM-модуля самого плагина Kotlin настраивается независимо. KSP, Compose compiler, serialization и Parcelize проверять с выбранной версией Kotlin, а не механически переносить текущую 2.0.21. [Миграция на встроенный Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).

Предлагаемые настройки корневой и тестовых сборок:

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=fail
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

Строгий режим configuration cache добавить в CI через `enableFeaturePreview("STABLE_CONFIGURATION_CACHE")`. Isolated Projects проверять отдельным профилем: это дополнительная цель, не другое название configuration cache. Не включать обходы `unsafe.ignore` и режим `problems=warn`. [Настройка configuration cache](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html).

## Что дают существующие решения

| Решение | Подтверждённое состояние | Решение для нашего проекта |
|---|---|---|
| aasitnikov/fat-aar-android | README: maintenance mode, 1.4.7, тестирование до AGP 9.0 | Использовать как источник регрессионных примеров; не переносить архитектуру задач |
| Официальный Fused Library | AGP 9+, прямое включение библиотек, ограничения ниже | Сравнительный кандидат для обычных Android libraries |
| tiper/UmbrellaAAR | README ветки 3.x заявляет проверку configuration-cache store/reuse, включая POM | Второй кандидат; сначала закрепить доступную версию/commit и проверить своими тестами |
| deepmedia/Grease | README 0.3.7: variant configurations, `greaseTree`, relocation | Изучить отдельные приёмы; latest AGP/cache совместимость не подтверждена |
| GradleUp Shadow | Объединение JAR и преобразование Java resources | Полезен для `META-INF/services` и JAR-части; не заменяет Android merger |

Источники таблицы: [aasitnikov](https://github.com/aasitnikov/fat-aar-android), [Fused](https://developer.android.com/build/publish-library/fused-library), [UmbrellaAAR](https://github.com/tiper/UmbrellaAAR), [Grease](https://github.com/deepmedia/Grease), [Shadow](https://github.com/GradleUp/shadow).

У aasitnikov проблема глубже возраста плагина: в прочитанном `VariantProcessor` действия задач обращаются к `Project`, меняют source sets в `doFirst`, пишут в выходы AGP и непосредственно вызывают действие другой задачи. Эти приёмы требуют переделки для нашей архитектуры. Это вывод по исходникам, а не результат локального прогона. [VariantProcessor](https://raw.githubusercontent.com/aasitnikov/fat-aar-android/master/source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy).

Fused не встраивает транзитивные зависимости автоматически. Руководство исключает Data Binding и Prefab/RenderScript, отмечает проблему file dependencies на AAR; для разных вариантов нужны отдельные fused libraries. Прямое подключение fused project экспериментально. Баг consumer rules с нестандартным именем уже исправлен в AGP 9.1+. View Binding нельзя автоматически приравнивать к Data Binding. [Ограничения Fused](https://developer.android.com/build/publish-library/fused-library).

У UmbrellaAAR заявлена проверка на AGP 9.3.1 / Gradle 9.5.1 в проекте с 95 объединяемыми модулями. Однако он ориентирован на локальные модули, не поддерживает project isolation, упрощает Maven scopes до compile и допускает зависимость POM от набора задач. Поэтому его отчёты полезны, а модель публикации требует отдельной проверки. [README и limitations](https://github.com/tiper/UmbrellaAAR).

Первоначальная неопределённость версии UmbrellaAAR снята 6 сентября: проверены опубликованный marker **3.0.0**, Maven-артефакт и соответствующий tag. Устаревший снимок страницы Plugin Portal показывал 2.3.0. Фактические сборки 3.0.0 и точный commit приведены в [результатах сравнения](merger-candidate-results.md).

Для анализа использования зависимостей полезен отдельный [Dependency Analysis Gradle Plugin](https://github.com/autonomousapps/dependency-analysis-gradle-plugin): он находит используемые транзитивные зависимости и ошибочные `api`/`implementation`. Проверку содержимого конечного AAR он не заменяет. Его внутренние классы не следует превращать в обязательный API нашего плагина.

Переиспользование кода ограничить небольшими понятными операциями и тестами. У aasitnikov указан MIT, у UmbrellaAAR/Grease/Shadow — Apache-2.0. Для реально переносимых файлов закреплять исходный commit, сохранять лицензии и применимые уведомления. В первом этапе достаточно изучения подходов без копирования реализаций.

## Подпись Fused: причина и возможный обход

Найден конкретный [баг #466503466](https://issuetracker.google.com/issues/466503466), зарегистрированный 7 декабря 2025. По доступному индексированному содержимому причина — повторная регистрация `assemble`: Fused создаёт её сам, а `signing` подключает Gradle lifecycle plugin. Прямое чтение актуального статуса трекера потребовало входа, поэтому нельзя утверждать, что дефект воспроизведён на AGP 9.4.0.

В прочитанной ветке AOSP `mirror-goog-studio-main` действительно осталась ручная регистрация `project.tasks.register("assemble")`. Ветка не закреплена за релизом 9.4.0; это подтверждает механизм проблемы, но не заменяет воспроизведение на релизе. [FusedLibraryPluginUtils.kt](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/fusedlibrary/FusedLibraryPluginUtils.kt).

**Предлагаемый эксперимент только внутри сравнительного теста Fused:** вспомогательный `:publication` в изолированной сборке `integration-tests/candidates/fused`, получающий конечный AAR как outgoing artifact через Gradle configuration. В нём применяются `maven-publish` и `signing`; Fused туда не применяется. Это потенциально устраняет столкновение lifecycle-задач. Обход пока не проверен; перенос подписи не устраняет ограничения самого merger. В основной архитектуре упаковка, публикация и подпись настраиваются в единственном `:sdk`.

Под подписью здесь понимается OpenPGP-подпись Maven-публикации: AAR, POM, `.module` и публикуемых sources/javadoc. APK signing config к этой задаче не относится. Нужно проверить подписи итоговых байтов и порядок `validation → package/metadata → sign → publish`. [Gradle Signing](https://docs.gradle.org/current/userguide/signing_plugin.html), [Signing publications](https://docs.gradle.org/current/userguide/publishing_signing.html).

## Контракт: что означает «один AAR»

Есть два режима поставки. Их важно различить до фиксации DSL:

| Режим | Что получает приложение | Правило проверки |
|---|---|---|
| `standalone` | AAR как файл | Все требуемые неплатформенные runtime-зависимости внутри; необъявленных требований к приложению нет |
| `maven` | Одна Maven-coordinate SDK | Внутренние модули встроены; согласованные внешние библиотеки приходят через POM и Gradle Module Metadata |

Файл AAR сам по себе не обеспечивает Gradle-разрешение внешних транзитивных зависимостей. Google рекомендует Maven-поставку с метаданными; при подключении файла зависимостями управляют отдельно. [Android library distribution](https://developer.android.com/studio/projects/android-library).

По уточнению пользователя основной сценарий — Maven-публикация: свои модули объединяются в один AAR SDK, а внешние библиотеки автоматически разрешаются у потребителя через метаданные. `standalone` остаётся дополнительным профилем для случаев, когда действительно нужен автономный файл. Встраивание общих AndroidX/Kotlin-библиотек может создать duplicate classes у потребителя, который уже подключает их. Нельзя автоматически считать embedding любой найденной зависимости безопасным. Grease прямо описывает этот случай. [Grease usage](https://github.com/deepmedia/Grease#usage).

Предлагаю явные состояния зависимостей: `embed`, `external`, `provided`, `buildOnly`. Неизвестное состояние — ошибка. `provided` требует отдельного описанного контракта с приложением и не должно рекламироваться как полностью автономный AAR. `buildOnly` подходит процессорам генерации, но не их runtime-библиотекам. Android/JDK platform types распознаются по заданному toolchain, а не по широкому списку исключений.

Способ поставки и видимость зависимости — разные признаки. Для `external` обязательно сохранять `api` либо `implementation`/`runtimeOnly`; одной общей категории external недостаточно. Встроенный модуль не должен оставаться отдельной зависимостью публикации, но его внешние API/runtime requirements должны быть учтены в итоговом SDK.

### Внешние API-зависимости: ConstraintLayout

Обязательный сценарий: SDK предоставляет `SdkView : ConstraintLayout` либо публичный метод с `ConstraintLayout` в аргументе/результате. Автор SDK объявляет `api(libs.androidx.constraintlayout)`, а приложение — только `implementation("com.example:sdk:1.0.0")`. Потребитель должен компилироваться и работать без ручного добавления ConstraintLayout.

Предлагаемая конфигурация SDK; `embed` предстоит реализовать, alias ConstraintLayout добавить в version catalog:

```kotlin
dependencies {
    embed(project(":feature-ui"))
    api(libs.androidx.constraintlayout)
}
```

| Объявление внешней зависимости в SDK | Compile classpath потребителя | Runtime classpath потребителя | Maven POM |
|---|---|---|---|
| `api` | Да | Да | `compile` |
| `implementation` | Нет, если нет другого API-пути к ней | Да | `runtime` |
| `runtimeOnly` | Нет, если нет другого API-пути к ней | Да | `runtime` |

Эта разница означает, что ошибочная публикация API-зависимости как runtime может сломать компиляцию клиента, хотя Gradle скачивает библиотеку для запуска. При использовании ConstraintLayout только внутри закрытой реализации/XML `implementation` может быть достаточно; для типа в публичном API нужен `api`. [Gradle API/implementation separation](https://docs.gradle.org/current/userguide/java_library_plugin.html).

В `.module` внешний API должен присутствовать в подходящих API- и runtime-вариантах; implementation — только в runtime-вариантах. Недостаточно вручную исправить POM: Gradle может предпочесть Gradle Module Metadata, и публикации окажутся несогласованными. Генерировать обе формы из одной модели software component. [Custom publishing components](https://docs.gradle.org/current/userguide/publishing_customization.html).

Для Fused это отдельный блокирующий эксперимент по замечанию пользователя. Официальное руководство описывает `include` для физической упаковки и публикацию `fusedLibraryComponent`; включение ConstraintLayout в AAR не является эквивалентом внешнего `api`. Поддержку привычного API DSL и перенос API scopes на конкретном закреплённом AGP нужно проверить воспроизведением; в этом исследовании такой прогон не выполнен. [Fused dependency configuration](https://developer.android.com/build/publish-library/fused-library).

Наш плагин должен поддерживать стандартное `api(...)` непосредственно в SDK и сохранять согласованные API requirements встроенных модулей. Нельзя заставлять потребителя исправлять неправильную публикацию. Новая внешняя API-зависимость внутри модуля попадает под общую строгую проверку состава: при отсутствии учёта в SDK сборка падает с цепочкой и предложением добавить external API entry. После учёта её метаданные генерируются автоматически.

Проверка публичных сигнатур должна обнаруживать случай «тип доступен только через implementation», включая наследование, параметры/результаты, generic bounds и Kotlin API. Если политика однозначно задаёт внешний способ поставки, задача `addMissingDependencies` может добавить или повысить запись до external API; при конфликте явных правил требуется конкретная диагностика. Для одной библиотеки, встреченной через API- и runtime-пути, сохраняется API-доступность; нельзя понизить её до runtime при дедупликации.

Acceptance test: fixture с `SdkView : ConstraintLayout`, отдельный consumer с единственной зависимостью на SDK и вызовом API, требующим разрешения ConstraintLayout. Проверить Kotlin/Java compilation, debug/release и реальный inflate. Прогнать два варианта разрешения: обычный с `.module` и отдельный consumer, настроенный на POM без перенаправления к `.module`. Проверить отсутствие встроенных классов ConstraintLayout и отсутствие внутренних project coordinates в публикации.

Отрицательный тест намеренно переводит external API в runtime в обеих формах metadata: валидатор должен выдать `FATAAR_API_DEPENDENCY_NOT_EXPORTED` с типом, coordinate и путём к модулю. Дополнительный тест рассогласовывает POM и `.module`. Контрольный случай использует внешнюю библиотеку только в реализации: она автоматически приходит для runtime и не становится API без причины. Все сценарии повторяются с configuration cache.

Одна Android library не может переносить буквально любые возможности приложения. Dynamic features, asset delivery и конфигурация app-плагинов не превращаются в обычный AAR простым копированием. Для неподдерживаемых типов входных модулей нужен ранний отказ. Для desugaring, Hilt и других требований к сборке потребителя — явное описание и consumer test.

## Предлагаемая архитектура

Оставить исходные модули самостоятельными Android/JVM libraries. SDK собирается из их выходных артефактов; объединение исходных каталогов перед компиляцией не должно менять их исходную семантику.

В основной сборке один модуль `:sdk`: обычная Android library с собственными исходниками публичного API, ресурсами и manifest, к которой применяется наш fat-AAR plugin. Этот же модуль объявляет `embed`, внешние `api`/`implementation` зависимости, собирает конечный AAR и настраивает публикацию с подписью. Его собственное содержимое объединяется с выбранными зависимостями и проходит те же проверки.

```text
FatAarPlugin/
  plugin-build/                 included build: код и тесты Gradle-плагина
    merger-core/                операции над архивами, правила конфликтов
    gradle-plugin/              DSL, configurations, tasks, AGP adapter
  app/                          демо, подключает опубликованный SDK
  sdk/                          исходники/API, ресурсы, manifest, упаковка и публикация AAR
  fixtures/                     положительные Android/JVM примеры
  integration-tests/
    consumer-maven/             отдельная Gradle-сборка
    consumer-file/              отдельная Gradle-сборка
    negative/                   генерируемые ошибочные проекты
    candidates/                 изолированные Fused/Umbrella проверки
```

`plugin-build` подключить через `pluginManagement { includeBuild("plugin-build") }`; код плагина — JVM, без зависимости от приложения. Названия подпроектов внутренней сборки можно сократить на первом шаге, сохранив разделение Gradle orchestration и операций над файлами. Дополнительные модули исходников/упаковки, необходимые Fused, допускаются только внутри его изолированного сравнительного теста.

Слой Gradle должен получать выбранные варианты через attributes/capabilities и публичные AGP Variant/Artifacts APIs. Пути вроде `build/intermediates/...`, угадывание имён внутренних задач и доступ к чужим `Project` из task action не должны входить в контракт. [Расширение AGP](https://developer.android.com/build/extend-agp), [Variant selection](https://docs.gradle.org/current/userguide/variant_aware_resolution.html).

Для наших локальных модулей маленький producer-plugin публикует дополнительный файл описания: выбранный variant, namespace, требования SDK, граф и compile-only/build-time особенности. SDK потребляет его как артефакт. Это закрывает важный пробел: `compileOnly` и требования генераторов нельзя полностью восстановить из одного SDK runtime classpath. Чужие AAR без такого описания проверяются по доступным метаданным и явному контракту.

Планируемый поток данных:

```text
вариантные AAR/JAR + описание модулей + утверждённая политика
  → снимок графа и перечень входных файлов
  → проверка состава зависимостей
  → карта переименований классов и диагностика конфликтов
  → преобразование пакетов классов и связанных ссылок
  → план объединения преобразованных входов
  → специализированные операции объединения
  → AAR + карта происхождения содержимого
  → проверка AAR и метаданных публикации
  → подпись и публикация
  → сборка/запуск независимых consumers
```

Задачи получают `Property`, `RegularFileProperty`, `ConfigurableFileCollection` и сериализуемые значения. Для графа использовать provider API разрешения зависимостей, преобразуя результаты в собственные DTO; не держать `Configuration`/`ResolvedArtifactResult` в состоянии задач. Внедрять файловые/worker services вместо `project.copy` и ручного вызова чужих задач. [Configuration Cache requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html).

Детерминированные операции оформлять как cacheable tasks/transforms: все входы объявлены, у каждой задачи собственный output-каталог по variant, порядок ZIP-entry стабилен, timestamps нормализованы, абсолютных путей в результате нет. Fingerprint должен учитывать содержимое ресурсов, порядок merge и политику конфликтов. Обычный `UP-TO-DATE` не доказывает восстановление из build cache. [Build Cache](https://docs.gradle.org/current/userguide/build_cache.html).

Для metadata публикации создать нормальную модель Gradle component с корректными API/runtime variants. Список встроенных модулей не должен утекать в POM как зависимость на неопубликованные внутренние coordinates. Метаданные конфигурируются из утверждённой политики; задача проверки не меняет dependencies во время execution. [Custom publishing components](https://docs.gradle.org/current/userguide/publishing_customization.html), [Maven Publish](https://docs.gradle.org/current/userguide/publishing_maven.html).

## Что именно придётся объединять и проверять

Базовый перечень частей AAR включает manifest, classes, ресурсы и символы, assets, вложенные JAR, JNI, consumer rules, lint и Prefab. Наличие валидного ZIP не означает семантически исправную библиотеку. [Anatomy of an AAR](https://developer.android.com/studio/projects/android-library).

Ниже — предлагаемые требования к нашей реализации, а не заявленная поддержка готовых плагинов.

| Содержимое | Обязательное поведение |
|---|---|
| Java/Kotlin bytecode | Проверять FQCN-дубликаты, ссылки, Kotlin metadata, top-level functions, inline/reified и service registrations |
| R / ресурсы | Согласовать namespaces, ссылки bytecode, `R.txt`, attrs/styleables и публичные символы; проверить финальные ID у consumer |
| Manifest | Android merge semantics, относительные class names, placeholders, authorities, tools rules, minSdk и отчёт происхождения |
| `res/` | Все типы и qualifiers, перекрёстные ссылки, явные overlays; конфликтующие значения по умолчанию ошибка |
| `assets/`, Java resources | Сохранять байты; `META-INF/services` объединять по правилам; конфликт с указанием двух источников |
| Kotlin module files | Не перетирать одноимённые `.kotlin_module`; проверять компиляцией отдельного Kotlin consumer |
| AIDL | Сохранить generated bytecode и нужные потребителю AIDL declarations/imports; проверить consumer-side compilation |
| Consumer R8 rules | Сохранить и агрегировать правила; проверять после shrinker и resource shrinking приложения |
| JNI / Prefab | Проверять ABI, имена, ELF-зависимости, metadata/headers, native consumer linking; не просто наличие `.so` |
| Data/View Binding | Отдельно проверять binding classes, BR, mapper и metadata через реальное использование layouts |
| Code generation | KSP/KAPT/Room/Hilt/serialization: generated code и runtime requirements; processors не попадают в runtime AAR |
| lint.jar | Сохранить несколько registries и регистрацию правил; тестовый consumer lint обязан увидеть нарушения |
| AAR metadata / profiles | Проверять требования compileSdk/AGP и сохранение baseline profiles в формате выбранного AGP |
| Неизвестные entries | Учёт в inventory; явное правило обработки либо ошибка, а не молчаливое исчезновение |

Самый рискованный участок — ресурсы и R. Нельзя ограничиться копированием `R.class` с промежуточными числовыми ID. Нужно отдельно доказать корректность attrs/styleable indexes, ссылок между namespace и публичного ресурсного API. Решение о remapping/генерации совместимых классов принимается после минимального эксперимента с двумя resource libraries и внешним consumer.

Manifest лучше обрабатывать Android merger, а не XML-конкатенацией. Применимость его конкретной библиотечной версии и API закрепляется в адаптере. Отдельно проверить unresolved placeholders: некоторые должны пережить упаковку SDK и разрешиться applicationId приложения. [Правила manifest merger](https://developer.android.com/build/manage-manifests).

Конфликтная политика должна зависеть от типа: для классов — ошибка; для ресурса с одинаковым типом/именем/qualifier — ошибка без явного overlay; для services — объединение провайдеров; для opaque assets/JNI — ошибка при различных байтах. Даже удаление одинаковых дубликатов должно быть отражено в отчёте. Лицензионные notices не удаляются общим `exclude("META-INF/**")`. Подход к resource transformers можно изучить в [Shadow](https://gradleup.com/shadow/configuration/merging/).

## Репакеджинг классов и сохранение имён ресурсов

**Репакеджинг классов остаётся отдельной возможностью этапа упаковки:** исходные модули продолжают собираться под прежними пакетами. Универсальное прозрачное переименование произвольной библиотеки, включая динамические строки и закрытый native code, обещать нельзя. Android-ресурсы объединяются с сохранением их исходных имён.

### Пакеты классов

Предлагаемая операция — package relocation, например:

```text
com.vendor.codec.Decoder
  → com.mycompany.sdk.internal.codec.Decoder
```

Правила должны выбирать исходный package и целевой prefix, поддерживать точные исключения и сохранять структуру подпакетов. По умолчанию сохранять фасад публичного SDK; его намеренное переименование задаётся отдельным правилом. Переименование опубликованного API меняет пути импорта и совместимость клиента, поэтому проверяется отдельным ABI-тестом.

Shadow использует ASM для изменения bytecode и поддерживает relocation по package prefix. Grease использует Shadow для этой функции. Это полезная основа JVM-части. Однако Android-раздел Shadow сейчас отсылает к Fused; готовой гарантии преобразования всего AAR из этого не следует. [Shadow relocation](https://gradleup.com/shadow/configuration/relocation/), [Shadow Android integration](https://gradleup.com/shadow/android-plugins/).

Наша карта преобразования должна применяться к definitions и всем ссылкам внутри встроенного SDK: descriptors/signatures/annotations, ссылкам фасада на внутренние классы, service descriptors, package-relative Java resources, manifest-компонентам, custom views/fragments в XML, consumer rules и baseline profiles. Один перенос entry `.class` в другой каталог не является relocation.

Для Kotlin нужно согласованно преобразовать `@Metadata` и `.kotlin_module`, включая package parts, multifile facades, type aliases и типы публичных сигнатур. Официальная `kotlin-metadata-jvm` позволяет читать/изменять metadata; для записи нужен поддерживаемый формат и strict parsing. Неизвестная версия metadata должна останавливать преобразование. [Kotlin Metadata JVM](https://kotlinlang.org/docs/metadata-jvm.html).

Правила применять только к выбранным embedded definitions, но обновлять ссылки на них во всех объединяемых входах. External/provided libraries не переписывать автоматически: они существуют вне нашего артефакта. Проверять package boundaries, пересечения правил, два класса с одинаковым целевым именем и неоднозначность определения исходного класса. Простая добавка module prefix не решает неоднозначную ссылку на два одинаковых FQCN.

Для строковых class names предусмотреть явные правила/адаптеры и проверку обнаруженных случаев. Глобальная замена строк может изменить протокол, serial name или обычный текст. Kotlin stdlib/reflect не включать в автоматический relocation по умолчанию: Shadow отдельно предупреждает об их связи с compiler/runtime. [Shadow: строки и Kotlin runtime](https://gradleup.com/shadow/configuration/relocation/).

JNI — отдельный предел: native code может содержать `FindClass`, signatures или экспортировать функции с именем Java-класса. Даже `RegisterNatives` требует согласования этих имён. Для закрытой `.so` по умолчанию сохранять JNI bridge packages либо выдавать явную ошибку; для своих исходников можно спроектировать согласованную пересборку native-части. [Android JNI binding](https://developer.android.com/ndk/guides/jni-tips).

### Имена Android-ресурсов

Автоматическое переименование и добавление префиксов ресурсам исключены из текущего плана по решению пользователя. Ссылки могут использовать `Resources.getIdentifier(...)`, `android.resource://...` и имена из JSON или native code; изменения одного R и XML для их сохранения недостаточно. [Resources API](https://developer.android.com/reference/android/content/res/Resources).

Плагин сохраняет имена ресурсов и проверяет конфликты типа/имени/qualifier. Неоднозначный конфликт без явно заданного overlay останавливает сборку с указанием модулей и файлов. Автоматическое переименование не используется как способ разрешить конфликт.

В полигоне остаются тесты чтения ресурсов через R, по строковому имени и через resource URI, включая имя из JSON. Их нужно выполнять после объединения и после репакеджинга классов. Изменение Java/Kotlin package не должно автоматически менять Android namespace или строковые имена ресурсов; согласованность R-ссылок проверяется отдельно.

Предлагаемый DSL, который ещё предстоит реализовать:

```kotlin
fatAar {
    relocation {
        relocate("com.vendor.codec", "com.mycompany.sdk.internal.codec")
        preserve("com.mycompany.sdk.api.**")
    }
}
```

Результат репакеджинга должен содержать `class-mapping.json` в build reports. Он используется проверками и помогает объяснить каждую замену; входит в воспроизводимые outputs преобразования. Все правила и версии преобразователей входят в cache key. Карта применяется один раз к исходным именам классов, без повторного добавления package prefix на тёплом кеше.

Acceptance tests репакеджинга: public API compilation; Kotlin reflection/serialization; manifest/custom view; service loading; binding; сохранённый JNI bridge; cross-module R references и строковый поиск ресурсов с прежними именами; смена package prefix между сборками с прогретыми кешами. Неподдержанный native/dynamic случай должен давать ожидаемую ошибку, а не зелёный отчёт о полной поддержке.

Эти требования делают собственный слой преобразований обязательным предметом раннего эксперимента. Даже если готовый backend объединяет архивы, нужно проверить, позволяет ли он подать преобразованные variant artifacts без внутренних task hooks. Если нет, его нельзя выбирать основой для полного набора требований.

## Валидация зависимостей и отсутствующих классов

### Проверка 1: граф и политика поставки

Для каждого выпускаемого variant строить граф выбранных Gradle components и artifacts, а не сравнивать строки из `dependencies {}`. Сохранять component identity, selected version, variant, capabilities, исходную цепочку и причины разрешения конфликтов.

Проверять полное достижимое замыкание runtime-графа, включая зависимость, добавленную глубоко внутри модуля. Каждый его элемент должен быть учтён политикой; каждый обязательный артефакт встроенного элемента — реально присутствовать в результате. Отдельно проверить API/compile graph, `compileOnly`, BOM/constraints, exclusions, dependency substitutions, local files и runtime-only libraries.

Наличие вершины в графе ещё не означает наличие её файлов в AAR. И наоборот: классы локального JAR могут уже находиться внутри исходного AAR; их нельзя встроить второй раз, ориентируясь только на путь зависимости.

Для Maven-режима проверять разрешённый граф внешнего consumer и scopes в POM/`.module`. Совместно доступные версии должны согласовываться с теми, против которых скомпилированы модули. Конфликт требований или несовместимость API — ошибка с цепочкой, а не автоматический выбор «самой новой» версии нашим кодом.

### Проверка 2: содержимое и ссылки

Сформировать входной inventory: component → entry/class/resource → hash. Для каждой операции записать, какие входы сохранены, объединены, преобразованы или явно исключены. Проверка готового AAR сверяет эту карту, чтобы случайное выпадение assets, lint или metadata тоже останавливало сборку.

Индекс классов и анализ bytecode должны проверять не только наличие имени класса, но также значимые ссылки на поля/методы, наследование и сигнатуры. XML/manifest/service registrations проверяются отдельными анализаторами. Для JNI нужен отдельный анализ зависимостей библиотек по ABI. Platform stubs и явно предоставляемые API входят в заданную область проверки. При relocation проверка учитывает карту исходных и конечных имён: переименованный класс не считается потерянным, а устаревшая ссылка — допустимой.

Ограничение принципиальное: reflection со строками, загружаемыми с сервера, произвольный JNI и условные runtime-пути нельзя полностью доказать статически. Для них нужны явные declarations и runtime-тесты. Корректное обещание плагина — полнота учёта известных зависимостей и поддержанных артефактов, плюс перечисленные проверки; абсолютную работоспособность произвольного Android-кода сборка не докажет.

### Проверка 3: независимый потребитель

В отдельной Gradle-сборке доступны только SDK и разрешённые внешние dependencies. Нет `includeBuild` с исходными модулями, dependency substitution и `mavenLocal`, который мог бы скрыть ошибку старой публикацией. В тестовом Maven-репозитории публикуется SDK; внутренние embedded modules отдельно там отсутствуют.

Проверяются Kotlin и Java compilation, сборка debug, release со shrinker, lint и запуск smoke tests. Приложение реально читает assets/raw resources, открывает экран с custom view, вызывает ServiceLoader/JNI, использует сгенерированный код и manifest-компоненты. Отдельный file consumer проверяет обещание `standalone`.

### Предлагаемые задачи

```text
:sdk:checkReleaseFatAarDependencies
:sdk:verifyReleaseFatAar
:sdk:reportFatAarDependencies
:sdk:addMissingDependencies
```

Первые две задачи должны автоматически предшествовать успешной сборке/публикации соответствующего SDK. Для собственного pipeline связи задаются через входы/выходы; нельзя полагаться на `finalizedBy`, чтобы проверка выполнялась уже после публикации. Если выбранный готовый backend не позволяет встроить обязательную проверку до выдачи артефакта, это провал критерия его пригодности.

Пример предполагаемой диагностики:

```text
FATAAR_UNCLASSIFIED_DEPENDENCY
SDK variant: release
Path: :sdk -> :feature-network -> :network-core
Missing policy entry: project(:network-core), variant=release
Required by: com.example.network.NetworkClient
Expected: embedded runtime dependency
Fix: ./gradlew :sdk:addMissingDependencies
Policy file: gradle/fat-aar-dependencies.toml
```

`addMissingDependencies` обновляет отдельный версионируемый файл политики. Он добавляет однозначные записи согласно заранее заданным правилам, например внутренние project dependencies → embed. Для неизвестной внешней библиотеки не угадывает embed/external/provided: выдаёт конкретный список нерешённых решений и способ задать правило. В standalone-профиле external runtime dependency остаётся ошибкой.

Предусмотреть `--dry-run` нельзя как собственный режим записи: это уже стандартный флаг Gradle, который не выполняет задачи. Для отчёта без записи использовать отдельную `reportFatAarDependencies` либо собственную опцию `--preview`.

Обновление должно быть детерминированным, атомарным, сохранять пользовательские правила и не менять файл повторно при тех же данных. Изменение выбранных versions/variants/capabilities тоже проверяется; для версии можно использовать отдельный Gradle lockfile, чтобы не дублировать version catalog. Обычная сборка ничего не исправляет автоматически.

После записи требуется **следующий запуск Gradle**. Нельзя безопасно обещать изменение уже сконфигурированного графа задач в том же invocation. Файл политики подключается через отслеживаемое чтение provider API; его изменение должно инвалидировать configuration cache. Исправление состава SDK не подменяет устранение ошибки разрешения зависимости или конфликта версий.

## Состав тестового полигона

Предлагаю 22 содержательных fixture-модуля, вводимых поэтапно. В каждом есть проверяемое поведение, ожидаемый набор артефактов и собственная контрольная сборка до объединения.

| Модуль в `fixtures` | Содержимое и проверка | Очередь |
|---|---|---|
| `jvm-core` | Java/Kotlin, generics, annotations, nested classes, top-level functions, inline/reified | Базовая |
| `android-core` | Kotlin/Java Android code, BuildConfig, Parcelize, обращения к R | Базовая |
| `resources-values` | strings/plurals/arrays, attrs/styleables, styles/themes, public/private declarations | Базовая |
| `resources-files` | layout/menu/navigation/xml/raw/font/drawable/mipmap, vector/selector/anim/animator/transition | Базовая |
| `resources-qualified` | locale, night, density, orientation, API qualifiers; перекрёстные ссылки | Базовая |
| `manifest-components` | Activity, Service, Receiver, Provider, permissions, queries, metadata | Базовая |
| `manifest-rules` | placeholders, authorities, tools merge rules, relative class names | Базовая |
| `assets` | JSON/XML/text/binary, вложенные каталоги, одинаковые пути с отдельным negative case | Базовая |
| `java-resources` | classloader resources, properties, notices, два ServiceLoader provider | Базовая |
| `dependency-graph` | deep chain, diamond/shared dependency, resource-only и runtime-only edges | Базовая |
| `external-artifacts` | контролируемый Maven AAR/JAR и их transitives; BOM и constraints; внешний ConstraintLayout в публичном API SDK | Базовая |
| `local-artifacts` | файловые AAR/JAR, JAR уже внутри AAR; артефакты воспроизводимо генерируются | Базовая |
| `consumer-rules` | reflection и consumer keep rules; проверка minified consumer | Базовая |
| `viewbinding` | binding нескольких layouts и их qualifiers; реальный inflate | Расширенная |
| `databinding` | BR, adapters, expressions, mapper, двусторонние связи | Ранний риск-эксперимент, затем расширенная |
| `compose` | composable API, ресурсы, compiler metadata и runtime | Расширенная |
| `codegen` | KSP/Room, serialization; отдельный контролируемый processor, не публикуемый в SDK | Расширенная |
| `di` | Dagger/Hilt и требования к consumer plugin; generated registries | Расширенная |
| `aidl` | Binder вызов и импорт декларации в отдельном consumer | Расширенная |
| `native-jni` | реальный JNI-вызов, arm64-v8a/x86_64, зависимость одной .so от другой | Расширенная |
| `native-prefab` | headers/metadata и компиляция/link C++ у consumer | Ранний риск-эксперимент, затем расширенная |
| `lint-profiles` | два lint registry через вспомогательные JAR и baseline profile | Расширенная |

Для репакеджинга классов добавить два небольших специальных fixtures: `relocation-api` и `relocation-impl`. Они дополняют 22 базовые группы случаями публичного фасада, package-private доступа и reflection/serialization. Основные resource/binding/JNI fixtures также прогоняются с репакеджингом классов, сохраняя имена ресурсов и проверяя их строковый поиск. Режимы с relocation классов и без него должны быть разными проверяемыми профилями одного полигона.

Некоторые строки потребуют небольших вспомогательных JVM/Android producers. Количество каталогов не является критерием готовности. Нужны также два library-модуля с build types и flavor dimensions, чтобы проверить правильный выбор варианта. Использовать попарные комбинации, а не полный взрыв всех сочетаний по всем модулям.

Отрицательные сценарии создавать TestKit-ом отдельно от обычного demo build: пропавшая транзитивная зависимость; неверный variant; отсутствующий класс/метод; duplicate classes; конфликт resource/asset/JNI; потерянный ServiceLoader provider; неверные POM scopes; утечка внутренней coordinate; minSdk/compileSdk несовместимость; неполный ABI; пропавшие consumer rules; неразрешённая host requirement. Каждый тест ожидает конкретный код ошибки и источник проблемы.

Полезны намеренные повреждения уже собранного SDK: удалить класс, ресурс, asset, service entry или правило. Это проверяет сам валидатор и защищает от ситуации, когда merger и тест пользуются одной ошибочной логикой.

Для масштаба добавить отдельный генератор проектов на 50/100/200 модулей: цепь, ромб, широкая fan-out структура. Они проверяют время конфигурации, объём кеша, incremental invalidation и память, но не заменяют содержательные Android-примеры.

## Как доказать поддержку кешей

Это обязательные acceptance tests каждого backend, а не обещание по наличию аннотации `@CacheableTask`.

| Проверка | Условие успеха |
|---|---|
| Первый запуск одного набора задач | Configuration cache сохранён, problems=0 |
| Повтор того же invocation | Configuration cache reused; корректный результат после десериализации |
| Восстановление outputs | Удалены только outputs тестового проекта, общий build cache сохранён; собственные детерминированные merge tasks получают FROM-CACHE |
| Сборка в другом каталоге | Кеш переносим; AAR совпадает побайтно |
| Изменение одного XML/asset/class/manifest | Повторно выполняются необходимые операции, результат содержит изменение |
| Удаление входного файла | В выходе не остаётся старой записи |
| Изменение зависимости/политики | Граф и проверка обновляются; нет ложного успеха на тёплом кеше |
| Изменение package prefix | Новые имена классов и ссылки согласованы; старые outputs удалены; повторный prefix не добавляется; имена ресурсов сохранены |
| Debug и release одновременно | Выходы вариантов не перемешиваются |
| Подпись/публикация дважды | Отдельно проверена совместимость этих task graphs с configuration cache |
| Автоисправление политики | Повторная запись no-op; следующая сборка использует обновлённый файл |

Gradle TestKit поддерживает реальные тестовые сборки с `--configuration-cache`, `--build-cache` и проверкой `TaskOutcome.FROM_CACHE`. [TestKit](https://docs.gradle.org/current/userguide/test_kit.html), [Тестирование configuration cache](https://docs.gradle.org/current/userguide/configuration_cache_debugging.html).

Signing, публикация и задача, редактирующая файл политики, имеют побочные эффекты: не требовать от них `FROM-CACHE` как от чистой операции объединения. При этом их configuration-cache совместимость остаётся обязательной. Gradle отмечает Signing поддержанным, а Maven Publish частично поддержанным для некоторых возможностей; проверять именно наш pipeline. [Статус Gradle plugins](https://docs.gradle.org/current/userguide/configuration_cache_status.html).

Для byte-reproducibility сравнивать неподписанный AAR и воспроизводимые metadata. Detached signatures могут содержать время; проверять их валидность, а не требовать одинаковый hash. Сами cache tests изолировать от пользовательских `~/.gradle` настроек и случайно опубликованных библиотек.

Начальная CI-матрица: основная пара AGP 9.4.0 / Gradle 9.7.1; тот же AGP с минимальным Gradle 9.6.0; JDK 17 и дополнительная проверка 21. Предыдущую линию AGP 9.3.2 добавить, только если решаем её поддерживать. Новые stable-релизы сначала проходят эту матрицу, затем обновляется зафиксированная версия. Это проверяемое обещание поддержки последних версий, без динамических `+` dependencies и без обещания совместимости с ещё не вышедшими релизами.

## Порядок реализации и критерии перехода

| Этап | Работа | Готово, когда |
|---|---|---|
| 1. Основа | Проверить JDK/SDK, обновить toolchain, включить кеши, добавить included build плагина, единый `sdk` с исходниками и демо | Минимальные library/app и тест плагина собираются дважды с configuration cache |
| 2. Независимый полигон | Базовые fixtures, consumer-maven/file, inventory и отрицательные тесты; временно обычный модульный контроль | Все fixtures работают до упаковки; тест замечает намеренную потерю файла/зависимости |
| 3. Выбор merger | Одинаковые примеры на Fused и закреплённом UmbrellaAAR; рано проверить external API/ConstraintLayout, R/styleable, репакеджинг классов, строковый поиск ресурсов, Data Binding, Prefab и signing | Есть таблица фактических pass/fail и решение: переиспользовать backend или писать собственные операции |
| 4. Учёт зависимостей | Variant graph, правила embed/external/provided/buildOnly, сохранение API/runtime scopes, diagnostics, addMissingDependencies | Новая зависимость внутри дочернего модуля ломает SDK build; потерянный external API диагностируется; задача учёта исправляет однозначный случай; следующий build проходит |
| 5. Базовая упаковка | Classes/resources/manifest/assets/JAR/services/consumer rules; базовый relocation, cacheable pipeline | Независимый consumer работает в debug и minified release; имена, ссылки и зависимости корректны |
| 6. Сложные форматы | Binding/codegen/AIDL/native/Prefab/lint/profiles, публичный ABI и соответствующие адаптеры relocation | Каждый заявленный формат проходит соответствующий consumer test; остальные дают явный отказ |
| 7. Публикация и масштаб | POM/.module с API/runtime scopes, sources, подписи, collision policies, CI матрица, 50–200 модулей | Consumer подключает только SDK и получает внешние API/runtime dependencies; обе формы metadata проверены в изолированном repo; cache tests зелёные, измерена масштабируемость |

Эксперимент с подписью провести уже на этапе 3; этап 7 завершает полноценную публикацию. Аналогично Data Binding/Prefab проверяются рано как причины выбора архитектуры, даже если полная поддержка делается позже.

Критерий выбора: переиспользовать готовую реализацию или её отдельные операции можно только при выполнении всех обязательных сценариев, включая единый `:sdk` с собственными исходниками, упаковкой и публикацией. Решение, требующее разделить основной SDK на модуль исходников и модуль упаковки, остаётся только сравнительным примером. Если UmbrellaAAR существенно сокращает работу и проходит тесты — можно адаптировать закреплённую реализацию. Если кандидаты не проходят обязательные требования, оставить тот же полигон и validator, реализовав недостающие merger stages. Не разрабатывать сразу несколько production backends: сравнительные сборки должны оставаться тестовыми кандидатами.

Публичную AGP-интеграцию предпочитать всегда, но не обещать, что она предоставляет готовое объединение всех форматов. Если понадобится Android tooling вне стабильного Variant API, ограничить его одним адаптером, закрепить версии и проверять матрицей. Недокументированные операции с task outputs не должны распространяться по всему плагину.

## Что остаётся доказать экспериментами

1. Кеширование будущего merge pipeline на AGP 9.4.0 / Gradle 9.7.1. Сборка модульного baseline, configuration-cache reuse и восстановление build cache задачи инвентаризации уже проверены.
2. Состояние signing-конфликта именно в закреплённом релизе и пригодность отдельного publishing project.
3. Доступность опубликованной UmbrellaAAR 3.x и соответствие выбранного commit заявленному поведению.
4. Реальную семантику сложных ресурсов, строкового поиска ресурсов, Data Binding и Prefab после объединения и репакеджинга классов.
5. Передачу внешних API/runtime dependencies после реального merge через обе формы Maven-метаданных. ConstraintLayout уже проверен в модульном baseline. Отдельно определить состав дополнительного standalone-профиля, если он нужен.

Исследование охватывает первичные Android/Gradle документы, исходники AOSP и fat-aar, README/registry релевантных альтернатив и конкретный signing issue. Поиск остановлен после проверки этих ключевых развилок: оставшиеся вопросы требуют сравнительных сборок на полигоне. Реализованы контрольная модульная сборка, независимый Maven consumer, учёт runtime-графа, автоисправление однозначных project-пропусков и проверка JVM API. Объединение, полный binary-linkage validator, подпись и relocation остаются следующими этапами.
