# Flavors, build types и область embed

SDK остаётся обычной Android library с собственным кодом и ресурсами. Для каждого
Android-варианта плагин создаёт отдельный объединённый AAR. Выбор внутренних архивов
использует attributes runtime-варианта SDK и стандартные правила AGP:
[variant matching, matchingFallbacks и missingDimensionStrategy](https://developer.android.com/build/build-variants).

## Декларации

```kotlin
android {
    flavorDimensions += "channel"
    productFlavors {
        create("red") { dimension = "channel" }
        create("blue") { dimension = "channel" }
    }
    sourceSets.maybeCreate("redRelease")
}
dependencies {
    "embed"(project(":common"))
    "redEmbed"(project(":red-only"))
    "blueEmbed"(project(":blue-only"))
    "redReleaseEmbed"(project(":release-only"))
}
```

`embed` действует во всех вариантах; `<sourceSet>Embed` действует там же, где
соответствующий `<sourceSet>Implementation`; `<variant>Embed` — только в указанном
варианте. Чтобы использовать комбинированный scope прямо в dependencies, создайте
соответствующий source set заранее. Поиск зависимостей, ABI-проверка, merge и публикация
учитывают только scopes выбранного варианта. Несовместимость вариантов не исправляется
выбором случайного архива: проверка графа выдаёт `FATAAR_UNRESOLVED_DEPENDENCY` с причиной Gradle.

Автоисправление Android-зависимостей записывает выбранный вариант в registry:

```sh
./gradlew :sdk:addMissingDependencies -PfatAar.variant=redDebug
```

```text
redDebugEmbed project :features:child
```

Другие варианты эта запись не затрагивает. Для зависимости, общей всем вариантам,
можно явно использовать `embed` в build script или registry. Неизвестный scope registry
отклоняется с `FATAAR_INVALID_RULE_SCOPE`. JVM-плагин сохраняет общий scope `embed`.

## Публикация

У каждого варианта есть компонент `fat<Variant>`, например `fatRedRelease`.
Разные flavor AAR публикуются под разными координатами, например `sdk-red` и `sdk-blue`.
Публикуемый компонент сохраняет API/runtime scopes выбранного варианта и не содержит
Android attributes: release-библиотеку может использовать debug-приложение.
Project consumer выбирает capability `<group>:<project>-fat` со стандартными Android
attributes. Maven consumer подключает обычные координаты.

## Автоматический полигон

`integration-tests/variants` — отдельная тестовая сборка с одним SDK и восемью
внутренними Android-модулями. Она проверяет:

- `red`/`blue` × `debug`/`release`/`staging`: шесть настоящих конечных AAR.
- Дополнительную dimension `engine`: modern для red, classic для blue.
- `staging` SDK с debug-зависимостями через `matchingFallbacks`.
- Общие, flavor-, build-type- и variant-scoped embed; одинаковый путь assets у
  red-only/blue-only должен содержать только данные выбранной ветки.
- Определения классов, строки байткода, values и assets в конечных AAR;
  наличие классов из AAR в DEX project и Maven debug APK.
- Отдельные Maven координаты, red implementation / blue API scopes, отсутствие внутренних
  модулей в POM/module, consumers с Gradle metadata и только POM.
- Ошибки при отсутствии missingDimensionStrategy, matchingFallbacks или red-leaf.
- Идемпотентное исправление redDebug, которое не исправляет redRelease и не добавляет
  red-leaf в blue; восстановление исходного fixture registry после проверки.
- Configuration cache и восстановление всех шести merge-задач из build cache.

На устройстве `VariantSmokeTest` читает код, BuildConfig, ресурсы разных namespace и assets
через SDK. Проверяются шесть project-вариантов и по четыре Maven-варианта в каждом режиме
метаданных, включая R8/resource shrinking release. Maven debug использует опубликованный
release SDK — тест явно проверяет это различие.

```sh
python3 scripts/verify-variants.py
python3 scripts/verify-variant-device.py --serial emulator-5554
python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35
```

Device-команда требует предварительного запуска artifact-команды. Полная команда делает
оба этапа сама. Отчёты: `build/reports/variant-validation/result.json` и
`build/reports/variant-device-validation/result.json`. Успех требует реальных XML test cases
без ошибок/пропусков; старые отчёты удаляются перед запуском.

Полигон пока использует одну общую flavor dimension и одну дополнительную dimension
производителя. Несколько общих dimensions, flavor-name fallback, disabled variants и
публикация с BOM/capabilities требуют отдельных сценариев. Resource renaming исключён.

## Подтверждённый результат

1 октября 2026: `verify-all.py` завершился со статусом PASS. Проверены все шесть AAR,
21 этап flavor artifact validation и 14 flavor instrumentation tests. Вместе с основным
полигоном это 51 этап проверки артефактов, 38 Android-тестов, 31 JVM/TestKit тест плагина
и 2 теста DEX parser. Все проверки прошли без ошибок и пропусков; тестовый registry
восстановлен, запущенный скриптом эмулятор закрыт.
