# Автоматическая проверка реального SDK

Основной вход:

```sh
python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35
```

Без `--avd` выбирается первый установленный AVD. Можно передать `--serial <device>`
для уже запущенного устройства. Скрипт самостоятельно собирает библиотеку, публикует её,
строит consumers, запускает тесты, читает результаты и останавливает только запущенный
им эмулятор. Android SDK, JDK и образ AVD должны быть установлены заранее.
Нативный полигон также требует NDK 25.1.8937393 и CMake 3.22.1.

Успех — exit code 0 и `build/reports/full-validation/result.json` со статусом PASS.
Старый итоговый отчёт удаляется до прогона. Пропущенные/нулевые instrumentation tests,
ошибки сборки и расхождения артефактов завершают проверку ошибкой. XML-отчёты предыдущего
device-прогона удаляются перед запуском; connected-задача принудительно выполняется заново.

## Что является объектом проверки

1. Gradle собирает настоящие Android fixture-модули и `:sdk`.
2. Плагин получает настоящие AAR/JAR и создаёт конечный AAR.
3. Скрипт читает этот AAR: проверяет определения классов, ресурсы, Manifest, rules, SPI.
   Все res/assets из входных Android AAR должны присутствовать в результате с тем же
   содержимым. Для values XML учитывается изменение имени файла, но не его содержимого.
4. Публикуется только SDK. Внутренних Maven coordinates в repository нет.
5. Проверяются подписи AAR/POM/module, scopes, воспроизводимость и кеши.
6. Независимая Android-сборка скачивает опубликованный SDK. У неё нет includeBuild,
   substitution или зависимостей на исходные fixture-проекты.
7. В debug APK проверяются DEX class_defs: каждый класс SDK действительно определён,
   а не просто упомянут в ссылках.
8. На устройстве выполняются одинаковые проверки project consumer, Maven consumer
   с Gradle metadata и POM-only consumer, каждый в debug и release с R8/resource shrinking.

Android-тесты совместно используют исходник `SdkSmokeTest`, но production-код Maven
consumer получает исключительно из опубликованного артефакта. Тесты не добавляют
fixture-модули в зависимости, не подменяют их классы и не подключают исходные ресурсы.
Общий Kotlin source set подключается через `AndroidSourceSet.kotlin`: со встроенным Kotlin
добавление Kotlin-каталога только в `.java` не поддерживается
([документация AGP](https://developer.android.com/build/migrate-to-built-in-kotlin)).

## Матрица автоматических проверок

| Возможность | Проверка |
| --- | --- |
| Классы SDK и внутренних модулей | Определения во входных архивах → merged classes.jar → DEX debug APK |
| Ресурсы и assets | Содержимое каждого входного res/assets сравнивается с конечным AAR |
| Межмодульные R-ссылки | Реальное приложение, custom View, SDK с R разных namespace |
| String lookup | getIdentifier с неизменённым именем после resource shrinking |
| Qualifiers | en/ru × day/night × portrait/landscape, 8 комбинаций в каждом consumer/variant |
| Values | strings, plurals one/few/many, array, bool, integer, dimen, color, style |
| XML и графика | XML parser, vector/shape/color selector, bitmap PNG, animation/animator |
| Бинарные файлы | Все 256 значений байта в raw и контрольные пиксели PNG |
| Attrs/styleable | Custom View: текст, emphasis, цвет и padding; отдельный layout-land |
| Manifest | Activity запускается, Service привязывается, Receiver/Provider отвечают; PackageManager проверяет exported, placeholders, metadata, permissions |
| Generated code | ViewBinding inflate/bind, DataBinding/BR/ObservableField, consumer includes и BindingAdapter; AIDL import и IPC в отдельном процессе |
| Внешние bindings | Maven API AAR, namespaces и отдельная проверка layout/classes; consumer с одной SDK-зависимостью; 4 debug/R8 runtime-теста |
| Binding errors | Реальные AAR: повреждённая metadata, отсутствие BR/implementation, AIDL-конфликт; восстановление SDK после каждого сценария |
| Manifest errors | Реальные AAR: конфликт атрибутов, duplicate authorities и minSdk; SDK восстановлен после каждого сценария |
| Java resources/SPI | Properties и ServiceLoader через SDK, в том числе после R8 |
| Consumer rules | Reflection-класс вызывается по вычисляемому имени после R8 |
| API/implementation | POM/module scopes и compile consumer без явного ConstraintLayout |
| Подпись | Криптографическая проверка AAR/POM/module и отказ при изменении архива |
| JNI/Prefab | Четыре ABI, все JNI/headers/archives/ABI records; ELF через независимый readelf; CMake shared/static/header-only и транзитивные exports; подпись финального SDK и 12 runtime-тестов |
| Native errors | Настоящие AAR: DT_NEEDED/loadLibrary, ABI/ELF/static archive/minSdk, missing Prefab modules и конфликты владельцев; reuse CC при изменении входных байтов |
| Кеши | CC reuse, UP-TO-DATE/FROM-CACHE, перенос TestKit-проекта, инвалидация входов |
| Версии embed | Реальные версии 1/2 тестовой Maven-библиотеки, выбор v2 через constraint |
| Ошибки API | Настоящая сборка с ConstraintLayout в implementation должна упасть |
| Конфликт ресурсов | Настоящий embedded Android-модуль получает дубликат string; merge должен упасть с обоими источниками |
| Варианты | Шесть реальных AAR; scoped embed, dimension selection, build-type fallback, отдельные координаты и API scopes; 14 runtime flavor-тестов |
| Missing dependencies/fixer | JVM/TestKit: новая транзитивная зависимость, диагностика, идемпотентность, отказ от неоднозначного исправления |

## Границы

Это покрытие реализованных сценариев, а не заявление о 100% branch coverage или поддержке
всех возможностей Android. Menu сейчас проверяется упаковкой/сборкой, но не открывается
на устройстве. Дополнительные Manifest-сценарии, tools:selector, fonts/media, external native/STL,
дополнительные AIDL/binding-сценарии, Compose, дополнительные flavor-сценарии, BOM/capabilities и class relocation требуют отдельного
расширения полигона. Неизвестный AAR payload должен отклоняться явно.

Для разработки доступны более короткие команды:

```sh
python3 scripts/verify-merged.py
python3 scripts/verify-variants.py
python3 scripts/verify-external-bindings.py
python3 scripts/verify-native.py
python3 scripts/verify-native-device.py --serial emulator-5554
python3 scripts/verify-external-binding-device.py --serial emulator-5554
python3 scripts/verify-variant-device.py --serial emulator-5554
python3 scripts/verify-device.py --serial emulator-5554 --consumer project
python3 scripts/verify-device.py --serial emulator-5554 --consumer maven
python3 scripts/verify-device.py --serial emulator-5554 --consumer maven-pom
```

Device-команды отдельно требуют предварительно собранной/опубликованной текущей версии SDK.
Полная команда выполняет все шаги сама.

## Подтверждённый прогон

30 сентября 2026: полный запуск завершился со статусом PASS — 23 этапа
проверки артефактов и 18 instrumentation tests. Все 48 наборов настроек ресурсов
прошли в project, Maven metadata и POM-only consumers, debug и R8 release.
Отчёт: `build/reports/full-validation/result.json`.

2 октября 2026, BOM и масштаб: полный прогон на Gradle 9.8.0 / AGP 9.4.0 PASS —
150 этапов проверки: 39 основного SDK, 21 flavors, 18 внешних bindings, 37 native
и 35 BOM. Также прошли 51 JVM/TestKit-тест плагина, 2 теста DEX parser
и 72 instrumentation tests.
Подписи AAR/POM/module повторно проверены стандартным signing plugin. Устройство —
API 35 ARM64; ABI записан в отчёт native runtime.

Отдельно прошли четыре Gradle/JDK комбинации и проекты на 50/100/200 Android-модулях:
точное содержимое AAR, исполнение marker-классов из готового JAR, оба кеша,
изменение/удаление файлов, перенос проекта и SDK-only Maven APK.
[BOM](bom-validation.md), [масштаб и совместимость](scale-and-compatibility.md).
GitHub Actions workflow подготовлен, но на GitHub ещё не запускался.

2 октября 2026, этап JNI/Prefab и signing: полный прогон PASS — 115 этапов проверки
артефактов (39 основного SDK + 21 flavors + 18 внешних bindings + 37 native/signing),
48 JVM/TestKit тестов, 2 теста DEX parser и 72 instrumentation tests
(42 основных + 14 flavor + 4 внешних bindings + 12 JNI/Prefab).
Стандартный Gradle signing самостоятельно строит merged release AAR после clean,
без предварительного assemble и без build cache. Подписи AAR/POM/module проверены
криптографически, повреждённый AAR отклонён; sign-only CC и clean/FROM-CACHE прошли.
Нативный SDK содержит 16 JNI libraries для четырёх ABI и 6 Prefab-модулей.
Runtime ARM64 подтверждён у project/metadata/POM consumers в debug/R8 release
на Pixel_9_Pro_XL_API_35 (arm64-v8a). Для остальных ABI подтверждены сборка и анализ
артефактов. Уточнение 2 октября: прежнее указание x86_64 было ошибкой описания
архитектуры AVD; сами instrumentation tests выполнялись на ARM64.
[Контракт и границы](native-validation.md). Итоговый отчёт:
`build/reports/full-validation/result.json`.

Подробный контракт и границы проверок Manifest: [manifest-validation.md](manifest-validation.md).

1 октября 2026: покрытие расширено Manifest-сценариями — 30 проверок артефактов
и 24 instrumentation tests, дополнительно 29 JVM/TestKit тестов. Проверены
tools:replace/remove, placeholders, запуск четырёх компонентов, duplicate authorities,
minSdk и отличие query provider от компонента приложения.

Подробный контракт flavor-полигона: [variant-validation.md](variant-validation.md).

1 октября 2026, этап flavors: общий автоматический прогон PASS — 51 этап проверки
артефактов (30 основного SDK + 21 flavor-полигона), 38 instrumentation tests
(24 основных + 14 flavor), 31 JVM/TestKit тест плагина и 2 теста DEX parser.
Все шесть flavor/build-type AAR, scopes, fallback, отрицательные сценарии и вариантное
автоисправление подтверждены реальными сборками и потребителями на устройстве.

Контракт и границы generated code: [generated-code-validation.md](generated-code-validation.md).

2 октября 2026, этап AIDL/bindings: полный прогон PASS — 60 проверок артефактов,
56 instrumentation tests, 38 JVM/TestKit тестов и 2 теста DEX parser. SDK содержит
49 классов и 24 res/assets payload. Remote Parcelable IPC, ObservableField, BR lookup,
BindingAdapter и includes подтверждены у project и независимых Maven consumers
в debug и R8 release; прежние flavor-сценарии также прошли.

Внешние DataBinding-библиотеки: [external-binding-validation.md](external-binding-validation.md).
Полный прогон включает отдельную публикацию, независимый metadata/POM-only consumer,
проверку resource ownership и ошибки повреждённого внешнего AAR.

2 октября 2026, этап внешних DataBinding API: полный прогон PASS — 78 этапов проверки
артефактов (39 основного SDK + 21 flavors + 18 внешних bindings), 40 JVM/TestKit тестов,
2 теста DEX parser и 60 instrumentation tests (42 основных + 14 flavor + 4 внешних bindings).
Внешняя библиотека приходит через API SDK в metadata/POM-only consumers, её namespaces
сохраняются, external payload не дублируется в SDK. Отрицательные проверки удаляют
layout symbol и binding implementation из копии настоящего AAR; изменение входа
обнаруживается при reused configuration cache. Все release-прогоны выполняются с R8.
Отчёт: `build/reports/full-validation/result.json`.
