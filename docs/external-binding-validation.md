# Внешние DataBinding-библиотеки

Если embedded-модуль включает layout внешней DataBinding-библиотеки, SDK объявляет
эту библиотеку через `api`. Приложение объявляет только SDK и включает DataBinding.
Внешние классы, ресурсы и metadata остаются в отдельном AAR; Maven-потребитель получает
его транзитивно из compile scope POM или API variant Gradle Module Metadata.

```kotlin
dependencies {
    "embed"(project(":feature"))
    api("vendor:binding-library:1")
}
```

Merger использует разрешённые API-артефакты выбранного варианта как отдельные входы
cacheable задачи. Он читает namespace, R.txt и классы каждого внешнего AAR.
Для embedded binding logs `module_package` меняется на namespace SDK. Для внешних
layouts namespace сохраняется; их binding base/implementation classes и layout symbols
должны существовать именно во внешнем AAR. Одноимённые классы или symbols SDK не могут
заменить отсутствующие определения внешней библиотеки. Совпадающие namespaces разных
артефактов отклоняются, чтобы владелец ресурсов был однозначным.

Неизвестный namespace в class log — ошибка с указанием пакета и рекомендацией `api`/`embed`.
Если публичный generated binding встроенного модуля требует внешний класс, а SDK
объявляет библиотеку в `implementation`, ABI validation выдаёт `FATAAR_API_DEPENDENCY`:
имя класса, исходный API member, координаты библиотеки и рекомендацию `api(...)`.

## Полигон и реальные артефакты

`integration-tests/external-bindings` содержит обычный producer `:external`,
embedded-модуль и отдельный тестовый `:sdk`. Этот SDK — изолированный сценарий,
production SDK проекта остаётся одним модулем `:sdk` в корне.

Обычный producer публикует `com.fataar.external:binding-library:1` в локальный Maven repo.
Embedded layout включает external layout, layout SDK включает embedded layout.
SDK публикуется как `com.fataar.externaltest:sdk:1`.

`integration-tests/external-bindings-consumer` — отдельная сборка без plugin includeBuild
и project dependencies. В приложении объявлена только SDK-зависимость. Его собственный
DataBinding layout включает и external, и embedded layouts, а Java-код использует
публичный внешний binding-класс через транзитивный API.

```sh
python3 scripts/verify-external-bindings.py
python3 scripts/verify-external-binding-device.py --serial emulator-5554
# Всё выше вместе с остальными полигонами и управлением эмулятором:
python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35
```

Автоматическая проверка:

- Строит debug/release AAR и проверяет сохранённые external resource namespaces,
  отсутствие external classes/resources/metadata внутри SDK и наличие embedded payload.
- Проверяет compile scope POM/API scope metadata и отсутствие embedded coordinates.
- Собирает независимое приложение с Gradle metadata и только POM, в debug и R8 release;
  выполняет lint и проверяет определения SDK и external classes в debug DEX.
- Проверяет configuration cache reuse и восстановление merger из build cache.
- Проверяет отказ сборки при замене `api` на `implementation`.
- Повреждает копии настоящего опубликованного external AAR: удаляет layout symbol или
  binding implementation. Merger обязан обнаружить пропуски. Используется один путь
  входного файла и повторно используемый configuration cache: изменение байтов AAR
  должно инвалидировать результат задачи. Оригинальные опубликованные архивы сохраняются.
- На устройстве требует выполнение именованного теста вложенных bindings и передачи
  значений через три уровня layouts, в debug/R8 release, metadata/POM-only — 4 теста.

R8 consumer сохраняет только вызываемый из тестового APK facade и общие библиотеки
тестового раннера (tracing/Kotlin runtime), которые AGP исключает из test APK при наличии
в приложении. Binding/SDK classes продолжают оптимизироваться. Test APK сохраняет
именованный тестовый класс и annotations для reflection discovery. Нулевой прогон,
пропущенный или ошибочный test case не считается успехом даже при exit code 0 Gradle.
При ошибке скрипт сохраняет Gradle log и отдельный runner log устройства в
`build/reports/external-binding-device-validation/`.

Поддержка подтверждается на закреплённых Gradle 9.7.1 / AGP 9.4.0 и Android 15.
Полигон проверяет внешнюю Android библиотеку из Maven, обычные Java DataBinding includes
и generated setters. Внешние project dependencies, внешние ViewBinding-only layouts,
instance/two-way/lifecycle bindings и Kotlin/KAPT требуют дополнительных реальных сценариев.

## Подтверждённый прогон

2 октября 2026: 18 artifact stages и все 4 runtime-теста нового полигона прошли.
Общий `verify-all.py` завершился PASS: 78 artifact stages, 40 JVM/TestKit tests,
2 DEX parser tests и 60 device tests. Запущенный скриптом эмулятор закрыт.
Основной SDK сохранил SHA-256 `761872f31a4bb3f7c93f3744ccf1044f964694f18eb205f522cfe668a162304e`.
