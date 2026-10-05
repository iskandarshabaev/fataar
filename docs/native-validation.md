# JNI, Prefab и подписанная публикация

Полигон `integration-tests/native` собирает один SDK с собственным JNI и тремя embedded
Android libraries. Для каждого debug/release варианта собираются armeabi-v7a, arm64-v8a,
x86 и x86_64. Фикстура использует NDK 25.1.8937393 и CMake 3.22.1; эти инструменты должны
быть установлены заранее. Версия NDK фиксирует воспроизводимые входы теста, а не ограничивает
плагин этой версией.

| Producer | Содержимое |
| --- | --- |
| native-base | Shared math library, static archive, заголовки с вложенным include, header-only модуль |
| native-bridge | JNI по имени функции, зависимость ELF и Prefab export на native-base |
| native-registered | JNI_OnLoad и RegisterNatives; точное имя Java-класса защищено consumer rules |
| sdk | Собственные Java/JNI, вызовы обоих embedded JNI-модулей, собственный Prefab-модуль |
| app | CMake-потребитель только SDK: shared/static/header-only; вызовы Java SDK и собственного JNI |

AGP не предоставляет DSL для `export_libraries`. Cacheable producer-задача
`DeclarePrefabExports` использует публичный `SingleArtifact.AAR`, чтобы добавить стандартные
Prefab-ссылки в настоящий producer AAR. Она также убирает вспомогательный static target
header-only модуля: в этом полигоне AGP 9.4 требует существования CMake target и упаковывает
его несмотря на `headerOnly=true`. Тест проверяет конечный модуль действительно без binaries.
Это подготовка тестовых входов, не обход в production-плагине.

## Контракт плагина

```kotlin
fatAar {
    prefabPackageName.set("sdk") // по умолчанию project.name
    nativeAbis.addAll("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}
```

Без `nativeAbis` literal loadLibrary проверяется для ABI, найденных во входных JNI.
С явным списком каждая такая библиотека и каждый бинарный Prefab-модуль должны иметь все
указанные ABI. Header-only модуль от этого требования освобождён.

JNI копируется с сохранением пути и байтов. Различающиеся файлы по одному пути отклоняются
с владельцами. Проверяются ELF class/machine/type, SONAME и DT_NEEDED по program headers;
stripped ELF поддерживается. Системные NDK-библиотеки сверяются с известными API bounds,
остальные нужны в merged JNI либо в SDK external runtime AAR. Прямой literal вызов
`System.loadLibrary("name")` проверяется с именем класса/метода и отсутствующим путём.

В AAR формируется один Prefab schema 2 package. Имена модулей сохраняются; повторение имени
ошибка даже при одинаковом содержимом. Ссылки `//embedded-package:module` становятся
`:module`; internal package dependencies исключаются. Заголовки, ABI records и binaries
сохраняются побайтно. External Prefab package должен быть представлен в SDK `api`; его
имя и ссылка сохраняются. Проверяются наличие заголовков, ABI records/binaries, ELF shared
и объекты ELF static archive, minSdk и наличие shared-модуля в runtime JNI.

## Автоматическая проверка

```sh
python3 scripts/verify-native.py
python3 scripts/verify-native-device.py --serial emulator-5554
```

Оба этапа включены в `verify-all.py`. Артефактный этап сравнивает все JNI, заголовки,
статические архивы и ABI records с настоящими producer AAR. Независимый `llvm-readelf`
проверяет машины, зависимости и 16 KiB LOAD alignment для четырёх ABI; merged Java-классы
сверяются с определениями в debug DEX. CMake-потребитель не добавляет прямую ссылку на
math/header-only: их подключение проходит через переписанные транзитивные Prefab exports.

Один SDK публикуется стандартными `maven-publish` и `signing`. Disposable OpenPGP ключ
генерируется локально только для теста. Криптографически проверяются подписи AAR, POM
и Gradle module metadata. Подписанный AAR побайтно равен конечному merged release AAR,
содержащему JNI и все Prefab-модули. Удаление библиотеки из копии валидного ZIP должно
сломать проверку исходной подписи. Повторная публикация проверяет configuration cache
и UP-TO-DATE signing; после clean/FROM-CACHE снова проверяются содержимое и подписи.
Отдельный сценарий очищает SDK и запускает только `signSdkPublication`, без assemble,
inspect или publish и без build cache. Signing должен сам собрать конечный merged AAR;
проверяются его прежний SHA-256 и все три подписи. Затем повторяется sign-only с reuse CC.

Независимый consumer не подключает plugin-build, producer-проекты или исходники SDK.
У него одна SDK-зависимость, два режима resolution: Gradle metadata и только POM. Оба
собираются в debug/release с CMake, R8 и lint. Отдельно проверяется project consumer.
Упаковка APK сохраняет все 16 SDK JNI-файлов и четыре consumer-библиотеки.

Отрицательные сценарии изменяют только копии настоящих AAR: пропущенные JNI/DT_NEEDED,
literal-loaded library, неправильный ABI/ELF, static archive, отсутствующий Prefab ABI,
schema/export/minSdk и конфликты JNI/Prefab с обоими владельцами. Меняется содержимое
по прежнему пути при reuse configuration cache; после восстановления позитивный сценарий
должен снова пройти.

Runtime-этап требует ровно два именованных теста в каждой из шести комбинаций
project/metadata/POM × debug/R8 release. Он проверяет реальные численные результаты:
JNI по имени, RegisterNatives, собственный JNI SDK, shared/static/header-only в CMake consumer.
Нулевой discovery, пропуск или runner crash — ошибка, даже при exit code 0 Gradle.

Отчёты: `build/reports/native-validation/result.json` и
`build/reports/native-device-validation/result.json`, с логами и независимыми ELF dumps.

## Границы покрытия

Текущий Pixel_9_Pro_XL_API_35 AVD использует arm64-v8a. Runtime проверяется на ARM64;
остальные ABI проходят реальную CMake сборку и анализ AAR/APK. Архитектура фактического
устройства записывается в native-device-validation/result.json; x86/x86_64 runtime
будет подтверждён только после реального запуска на соответствующем устройстве. Динамически вычисляемые
loadLibrary/System.load, dlsym, native symbol/API compatibility, C++ STL ABI compatibility
и class relocation требуют отдельных сценариев. DT_NEEDED-проверка доказывает наличие
библиотек, не наличие каждой импортированной функции. External Prefab/runtime AAR код
поддерживает учёт владельцев, но отдельный end-to-end полигон для него ещё не добавлен.
Подпись проверена со стандартным Gradle signing, а не со всеми сторонними signing-плагинами.

## Подтверждённый результат

2 октября 2026: полный `verify-all.py` — PASS. Нативный этап: 37 проверочных этапов,
12 именованных runtime-тестов; общая регрессия: 115 этапов артефактов, 48 JVM/TestKit,
2 DEX parser tests и 72 instrumentation tests. Ошибок и пропусков нет.
Release AAR SHA-256:
`5d9a28d301a66798e4fceabf106251a50f4944364a12007890ddb591448cf055`.
Он содержит 3 Java-класса, 16 JNI libraries и 6 Prefab-модулей; подпись относится
именно к этому полному архиву. Самостоятельный sign-only и кеши сохраняют его SHA-256.
