# Проверка зависимостей и JVM API

Валидатор появился во второй итерации 6 сентября 2026 и теперь работает перед
[физическим объединением AAR](merged-aar.md). Декларация `embed` задаёт встраиваемые компоненты.

## Использование

```kotlin
plugins {
    id("com.android.library")
    id("com.fataar")
}
fatAar {
    // Явное разрешение автоисправлению встраивать проекты под этим префиксом.
    autoEmbedProjectPrefixes.add(":features:")
}
dependencies {
    "embed"(project(":features:entry"))
    api("androidx.constraintlayout:constraintlayout:2.2.1")
    api("androidx.constraintlayout:constraintlayout-core:1.1.1")
}
```

Обычные `api`, `implementation` и `runtimeOnly` означают внешнюю поставку.
`embed` и внешний scope для одного выбранного компонента конфликтуют.
Транзитивные зависимости внешнего компонента покрываются его опубликованным графом.
Транзитивные зависимости встроенного компонента должны быть учтены в SDK:
отдельный `embed` либо путь от внешней зависимости, объявленной в SDK.
BOM/constraints влияют на выбор версии, но сами не являются декларацией поставки payload.

```sh
./gradlew :sdk:validateReleaseDependencies
./gradlew :sdk:validateDependencies
./gradlew :sdk:addMissingDependencies
./gradlew :sdk:assembleRelease
```

Первая команда проверяет граф release, вторая — графы всех вариантов.
Сборка Android library запускает graph guard через публичный preBuild lifecycle hook.
При выдаче AAR выполняется ABI-проверка: она включена как identity transform
`SingleArtifact.AAR`, поэтому потребитель AAR или Maven-публикация не обходят её.
Входной AAR копируется без изменений только после успешной проверки.

Для flavored-варианта автоисправления:
`./gradlew :sdk:addMissingDependencies -PfatAar.variant=demoRelease`.
Или вызвать вариантную задачу, например `addMissingDebugDependencies`.
Один запуск автоисправления должен выбирать один вариант.

## Автоисправление

`addMissingDependencies` по умолчанию проверяет release-граф и добавляет однозначные
внутренние проекты в `sdk/fat-aar-dependencies.txt`:

```text
releaseEmbed project :features:child
```

Файл читается плагином при конфигурации и содержит реальные декларации зависимостей.
Его нужно хранить вместе с исходниками. Поддерживаются строки
`<embed|sourceSetEmbed|variantEmbed|api|implementation|runtimeOnly> <project|module> <path|group:name:version>`,
пустые строки и `#`-комментарии.

Задача не редактирует Kotlin/Groovy build script. Если есть неизвестная внешняя библиотека,
конфликт либо неразрешённая зависимость, она отказывается от всех изменений с диагностикой.
Повторный запуск идемпотентен. Запись атомарная; после неё требуется **новый запуск сборки**.
Android-исправление использует `<variant>Embed` и затрагивает только выбранный вариант.
Явный `embed` остаётся общим; JVM-исправление использует его. [Контракт вариантов](variant-validation.md).
ABI-ошибки пока исправляются явным `api(...)`; эта задача автоматически чинит только граф.

Автоисправление намеренно не кешируется и не пропускается как UP-TO-DATE: оно меняет
исходную модель. Обычные validation/ABI/inventory задачи кешируются; configuration cache
поддерживается и для команды исправления. Изменение registry инвалидирует конфигурацию
следующего запуска.

## Диагностика

| Код | Значение |
| --- | --- |
| `FATAAR_MISSING_DEPENDENCY` | Компонент не учтён; показана цепочка от SDK и возможное исправление |
| `FATAAR_CONFLICTING_DELIVERY` | Один выбранный компонент одновременно embed и external |
| `FATAAR_UNRESOLVED_DEPENDENCY` | Не удалось разрешить зависимость; показана цепочка и причина Gradle |
| `FATAAR_FIX_REQUIRES_DECISION` | Автоисправление неоднозначно; файл не менялся |
| `FATAAR_API_DEPENDENCY` | Тип публичной/protected сигнатуры есть в runtime, но отсутствует в API-classpath |
| `FATAAR_MISSING_API_CLASS` | Тип отсутствует в пакетируемых классах, внешнем API и Android bootclasspath |
| `FATAAR_DUPLICATE_CLASS` | Класс повторяется между SDK и явно встраиваемыми компонентами либо внутри них |
| `FATAAR_UNSUPPORTED_FILE_DEPENDENCY` | Файловая зависимость не имеет поддерживаемого контракта учёта |
| `FATAAR_UNSUPPORTED_COMPONENT` | Неподдерживаемый тип компонента, включая included-build substitution |
| `FATAAR_INVALID_RULE` | Некорректная запись registry |
| `FATAAR_INVALID_RULE_SCOPE` | В registry указан несуществующий Android scope |

Отчёты: `sdk/build/reports/fat-aar/<variant>/dependencies.txt`, `abi.txt`, `inventory.txt`.

## Реально найденный API-пропуск

После проверки унаследованных членов обнаружилось, что у ConstraintLayout есть методы,
например `fillMetrics(Metrics)`, с типами из `constraintlayout-core`.
В проверенном графе 2.2.1 эта зависимость присутствовала только в runtime.
Поэтому SDK теперь явно экспортирует также `constraintlayout-core:1.1.1`.
Независимый consumer вызывает `panel.fillMetrics(Metrics())` без прямой зависимости на core.
POM и Gradle Module Metadata проверяются на compile/API scope обеих библиотек.

## Покрытие и ограничения

Проверяются выбранный runtime-граф, стандартные внешние scopes, ромбы/циклы,
транзитивные пропуски, неразрешённые зависимости, публичные/protected JVM-сигнатуры
SDK и embedded-классов: наследование, поля, аргументы/результаты, массивы, generics,
bounds и checked exceptions. Дополнительно обходятся внешние базовые классы и интерфейсы,
чьи члены наследует SDK. Android/JDK типы берутся из Android bootclasspath.

Тела методов, включая байткод inline bodies, дополнительно проверяются на ссылки
классов/методов/полей [валидатором конечного AAR](resource-and-linkage-validation.md).
Kotlin metadata, typealias и аннотации как API-контракт не анализируются.
Kotlin internal с публичным JVM access
может проверяться консервативно. Reflection, строки ресурсов и JNI проверяются отдельными
runtime/packaging сценариями. Контракты provided/buildOnly, сложные capabilities,
локальные AAR/JAR и сложные ограничения версий внешней
публикации требуют следующих этапов. Прямые file dependencies отвергаются; непрозрачные
file-компоненты дополнительно отвергаются при ABI-проверке.

Artifact graph читается через `rootComponent`/`rootVariant` Provider и обход
`getDependenciesForVariant`. В task actions не попадают Project/Configuration.
Для class-JAR view используется изолированная AGP-конвенция `android-classes-jar`:
это точка совместимости, которую нужно проверять при каждом обновлении AGP.
Зависимости задач-производителей сохраняются через ArtifactCollection FileCollection.
Основание: [Gradle configuration cache requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)
и [обход выбранного графа](https://docs.gradle.org/current/userguide/dependency_graph_resolution.html).

## Проверки

`python3 scripts/verify-merged.py` включает:

- Unit/TestKit тесты графа, отказ неоднозначного исправления без частичной записи,
  идемпотентность, обнаружение новой зависимости внутри дочернего модуля.
- Configuration-cache reuse, UP-TO-DATE и FROM-CACHE для валидатора.
- JVM ABI тесты, проверку потери API-типа, инвалидацию при изменении classpath
  и кеширование identity AAR transform.
- Негативный Android-сценарий: временно перенести ConstraintLayout из api в implementation
  через init script; ожидать `FATAAR_API_DEPENDENCY`, затем восстановить сборку.
- Публикацию и отдельного consumer через POM и Gradle Module Metadata.

На устройстве:
```sh
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release
python3 scripts/verify-device.py --serial emulator-5554
```

Release instrumentation сохраняет R8 и shrinkResources, использует тестовую подпись демо
и keep для двух SDK-фасадов и общего с test APK runtime (tracing, Kotlin, coroutines).
AGP исключает общие зависимости из test APK, поэтому их нельзя удалять из тестируемого
APK до запуска runner. Fixture-классы проходят обычную оптимизацию; рефлексия по-прежнему зависит от
consumer rules fixture-модуля. Без свойства `testBuildType=release` эти тестовые правила
и подпись к release не применяются.

Отдельные test-APK правила сохраняют класс smoke-тестов и его runtime-аннотации,
чтобы R8 не сломал поиск тестов через reflection. Для CLASS-retained аннотации
Error Prone разрешён только отсутствующий javac enum `javax.lang.model.element.Modifier`;
это исключение относится только к test APK. SDK/app правила остаются строгими.
`verify-device.py` проверяет наличие всех семи ожидаемых test case в XML и отсутствие
failures/errors/skips; одного `BUILD SUCCESSFUL` недостаточно.

ABI-модель проверяет явно объявленные embed-классы до merge. После merge отдельные
проверки сравнивают определения классов в исходных архивах, конечном AAR и debug APK.

Runtime release обнаружил удаление `fixture_dynamic_name` ресурсным shrinker.
Правило `tools:keep` добавлено в сам `resources-values`, чтобы оно поставлялось с модулем,
а не требовало ручной настройки приложения. Имя ресурса не меняется.
Этот тип файла должен сохраняться при будущем merge.
[Android: сохранение ресурсов с динамическими ссылками](https://developer.android.com/topic/performance/app-optimization/customize-which-resources-to-keep).

При DataBinding ABI учитывает consumer-generated `DataBindingComponent` по строгому
контракту metadata/mapper; [подробности и проверка](generated-code-validation.md).

## BOM

Платформы явно учитываются в SDK через API/implementation и не считаются архивами
или поставщиками классов. Пропуск BOM не исправляется автоматически. Вложенные BOM,
versionless dependencies и версия реально встроенного JAR проверяются отдельным
полигоном. [Политика и ограничения](bom-validation.md).
