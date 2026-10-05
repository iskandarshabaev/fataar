# Матрица тестового полигона

Полигон теперь собирает единый AAR собственным merger. Демо использует project capability,
отдельный consumer получает только опубликованный SDK. [Текущий pipeline](merged-aar.md).

| Модуль | Проверяемые особенности |
| --- | --- |
| `jvm-core` | Java JAR, SPI-интерфейс, JVM unit test |
| `android-core` | Java + Kotlin, BuildConfig, inline/reified Kotlin-функция, зависимость на JAR |
| `resources-values` | strings, ru/night qualifiers, plurals, arrays, bool, integer, dimen, color, style, attrs/styleable, public.xml, tools:keep для строкового lookup |
| `resources-files` | layouts и land qualifier, custom View из XML, vector/shape/color selector, anim/animator/menu, raw JSON/binary, PNG, XML, межмодульные ссылки |
| `manifest-components` | Activity, Service, Receiver, Provider, permission, metadata, `${applicationId}` authority |
| `assets` | JSON и вложенный XML; чтение AssetManager |
| `java-resources` | META-INF/services, ServiceLoader, properties в classpath; provider вызывается без прямой ссылки на его класс |
| `dependency-leaf` | Общий транзитивный Android-модуль |
| `dependency-left` | Левая ветка графа |
| `dependency-right` | Правая ветка, повторная ссылка на leaf |
| `dependency-graph` | Ромбовидный граф; обе ветки вызываются из SDK |
| `aidl` | Interface/Stub/Proxy, exported Parcelable declaration, remote Service и parcel round trip |
| `view-binding` | Generated binding class, layout inflate/bind и resource IDs |
| `data-binding-left/right` | Два mapper, разные variables, ObservableField, BindingAdapter, metadata для include в приложении |
| `consumer-rules` | consumer ProGuard rules, lookup класса по вычисляемой строке |

SDK использует R из разных namespace и поиск ресурса через `getIdentifier` по неизменённому
имени. Публичный `SdkPanel : ConstraintLayout` проверяет внешний API-контракт:
демо и Maven consumer импортируют ConstraintLayout без прямой зависимости на него.

## Уровни проверки

- **JVM / TestKit:** валидность инвентаризации, диагностика дублей, детерминированный
  отчёт, configuration cache, build cache, изменение входа, перенос рабочего каталога.
- **Android build:** debug, R8 release, lint, компиляция тестов; XML/Manifest и ссылки
  между ресурсами проходят стандартный Android build pipeline.
- **Maven consumer:** отдельные settings без includeBuild и project substitution.
  Два режима: Gradle Module Metadata и POM-only. Проверяются scopes и запуск опубликованного SDK на устройстве.
- **Device:** `SdkSmokeTest` проверяет значения/классы/ServiceLoader/ресурс по имени,
  provider, custom View, бинарные payload и 8 сочетаний языка/night/ориентации через SDK. Требует устройство; компиляция тестового
  APK сама по себе не означает прохождение runtime-проверок.

## Что ещё добавить

| Область | Следующая проверка |
| --- | --- |
| Реальный fat AAR | Реализовано: единственный SDK в Maven repository, два режима metadata, определения классов проверяются в debug APK |
| Негативный dependency graph | Реализованы пропуски transitive-узлов, новая зависимость дочернего модуля, неоднозначное исправление, public JVM API и статические ссылки финального байткода; дальше — version/capability/publication constraints |
| Коллизии | Проверены дубли классов/ресурсов/JNI/Prefab и merge ServiceLoader; дальше — отдельные сценарии assets и расширение диагностики |
| Manifest | Проверены tools:replace/remove, placeholders, minSdk, четыре компонента, duplicate authorities и отличие queries/provider; дальше — tools:selector, aliases, queries и дополнительные intent-filter scenarios |
| Android API | AIDL IPC/Parcelable, ViewBinding и DataBinding реализованы; дальше — расширенные bindings, Compose и Kotlin metadata; KSP исключён из области работ |
| Native | Реализованы JNI/ELF и shared/static/header-only Prefab для четырёх ABI, CMake consumers и RegisterNatives после R8; дальше — external native, STL и imported symbols |
| Ресурсы | Binary/PNG и en/ru × day/night × portrait/landscape после shrinking, дедупликация и явные overlays проверяются; дальше — fonts/media, дополнительные qualifiers и несовместимые styleable |
| Варианты | Проверяются red/blue × debug/release/staging, extra dimension, fallback, scoped embed и fixer; дальше — несколько общих dimensions и flavor-name fallback |
| Публикация | Реализованы подпись AAR/sources JAR/POM/module, scopes, обычные и вложенные BOM, воспроизводимость; дальше — rich constraints/capabilities |
| Runtime | Project и независимые Maven consumers, debug/R8 release; дальше — дополнительные Android API и версии ОС |
| Relocation | Bytecode, Kotlin metadata, XML/Manifest, SPI, rules; reflection/JNI требуют явной политики |

Не поддерживаемые сейчас области не должны молча считаться корректно обработанными
будущим плагином. Перед включением каждой возможности нужны положительный пример,
отрицательный сценарий там, где он применим, и проверка конечного consumer.

Полный автоматический запуск и точное покрытие: [automated-validation.md](automated-validation.md).

Изолированный flavor-полигон: [variant-validation.md](variant-validation.md).

Generated code: [generated-code-validation.md](generated-code-validation.md).

Внешние DataBinding API: [контракт и сценарии](external-binding-validation.md).
Проверяются реальные AAR/POM/module, транзитивная компиляция consumer, debug DEX,
R8 runtime и изменение входного external AAR при reused configuration cache.

Нативный SDK: [JNI/Prefab и signing](native-validation.md).
