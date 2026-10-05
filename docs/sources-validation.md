# Объединённый sources JAR

SDK может создать один архив исходников для своего кода и всех встроенных библиотек:

```kotlin
fatAar { withSourcesJar() }
```

Это опция; обычная сборка AAR не требует наличия source artifacts у внешних библиотек.
В основном полигоне опция включена. Команды работают локально и не публикуют артефакты:

```sh
./gradlew :sdk:mergeDebugFatAarSources :sdk:mergeReleaseFatAarSources
python3 scripts/verify-sources.py
```

Результаты: `sdk/build/outputs/sources/sdk-debug-sources.jar` и
`sdk/build/outputs/sources/sdk-release-sources.jar`. В компоненте `fatRelease` архив
имеет classifier `sources` и documentation variant без зависимостей. Стандартный
Gradle Signing Plugin включает его в `signMergedPublication` вместе с AAR/POM/module.
Для проверки используется временный тестовый ключ, созданный под `build/`.

## Состав и выбор варианта

Android-источники получаются через публичные `variant.sources` и
`configureJavaCompileTask`: Java, Kotlin, AIDL, BuildConfig, View Binding, Data Binding
и Java-исходники процессоров javac. Для готовности последних экспорт зависит от
сборки соответствующего AAR. JVM-модули предоставляют стандартный `sourcesJar`;
`com.fataar.embeddable` включает его автоматически.

Generated Data Binding `BR.java` и `DataBindingComponent.java` не включаются: AGP
исключает эти типы из library AAR и создаёт их в consumer с его нумерацией полей.
Пустые Java-маркеры, созданные AIDL для пользовательских Parcelable, также пропускаются;
сам пользовательский Java-файл и декларация AIDL сохраняются.

Source artifacts выбираются для компонентов, уже выбранных в графе встроенных
бинарных зависимостей, посредством [variant reselection Gradle](https://docs.gradle.org/current/userguide/artifact_views.html).
Версия Maven-библиотеки и flavor/build type Android-модуля следуют выбору бинарного
артефакта, включая `matchingFallbacks` и `missingDimensionStrategy`. `api` и
`implementation`, оставленные внешними зависимостями SDK, не попадают в архив.

Исходники сохраняются побайтово, с оригинальными package и ссылками на R. Это архив
для просмотра исходников и отладки; он не представляет самостоятельно собираемый
проект из объединённых исходников. R в байткоде AAR уже может ссылаться на namespace
SDK. C/C++ исходники, KSP и javadoc JAR этим механизмом не предоставляются.

## Диагностика и воспроизводимость

- `FATAAR_MISSING_SOURCES`: отсутствует источник для конкретной встроенной библиотеки.
  При включённой опции неполный архив не выдаётся как успешный результат.
- `FATAAR_SOURCE_CONFLICT`: одинаковый путь с разными байтами; ошибка называет путь
  и оба модуля. Одинаковые файлы объединяются без потери содержимого.
- Небезопасные ZIP-пути, повторяющиеся ZIP-entries, повреждённые архивы и `.class`
  вместо исходников отклоняются.

Лицензии и прочие несырьевые файлы внешних sources JAR сохраняются отдельно для
каждого владельца под `META-INF/fataar/inputs/`. `META-INF/fataar/sources.json`
содержит владельцев, пути исходников и SHA-256 входных архивов. Абсолютные пути
рабочей машины в этот индекс не записываются.

Порядок ZIP-записей и время фиксированы. Задачи экспортирования/мержа кешируемые;
изменение и удаление исходника меняют результат. Проверяются configuration cache,
восстановление после удаления outputs и build cache в перемещённом проекте.

## Автоматическая проверка

`scripts/verify-sources.py` собирает настоящие AAR и JAR. Для основного SDK он
сравнивает все пользовательские Java/Kotlin/AIDL файлы побайтово и читает атрибут
`SourceFile` каждого класса из конечного `classes.jar`, проверяя наличие исходника.
Дополнительно проверяются шесть flavor/build-type архивов, scoped dependencies,
полный SDK documentation variant у независимого consumer, подписи и отказ при
подмене подписанного JAR.

Изолированный Maven-полигон собирает реальные версии 1 и 2 Java-библиотеки.
SDK запрашивает версию 1, а constraint выбирает 2. Бинарный AAR и sources JAR должны
содержать код версии 2 в режимах Gradle metadata и POM-only. Отсутствие источников
выбранной версии проверяется отрицательным сценарием.

В этом сценарии нет вызовов `publish*`. Сгенерированные JAR/POM/module копируются
в каталог с локальной Maven-структурой только для проверки разрешения зависимостей.
Отчёт: `build/reports/sources-validation/result.json`.
