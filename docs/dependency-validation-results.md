# Результаты второй итерации

Проверено 6 сентября 2026 года: Gradle 9.7.1, AGP 9.4.0, JDK 21/toolchain 17.
Runtime — временный эмулятор Android 15 (API 35), запущенный в read-only режиме.

| Проверка | Результат |
| --- | --- |
| Исходный неучтённый граф | Обнаружены dependency-left, dependency-right и dependency-leaf с цепочками до SDK |
| Автоисправление SDK | Три явные embed-декларации добавлены в registry; повтор идемпотентен |
| Изменение дочернего модуля | TestKit подтверждает ошибку при добавлении новой неучтённой зависимости |
| Неизвестная внешняя зависимость | Отказ автоисправления без частичной записи |
| Неразрешённая зависимость, constraint, конфликт ролей | Отрицательные сценарии проходят |
| JVM API | Проверяются сигнатуры, generics, наследование и унаследованные члены |
| Негативный Android API-сценарий | Перенос ConstraintLayout в implementation вызывает FATAAR_API_DEPENDENCY |
| Реальный API-пропуск | Добавлен api для constraintlayout-core; Maven consumer вызывает fillMetrics без прямой зависимости |
| Plugin tests | 19 тестов, 0 failures/errors |
| JVM fixture test | 1 тест, 0 failures/errors |
| Gradle validatePlugins | Успешно |
| Configuration cache | Повтор baseline переиспользует запись; TestKit проверяет graph/ABI/inventory |
| Build cache | TestKit подтверждает FROM-CACHE и инвалидацию graph/ABI/inventory |
| Maven publication | POM и .module scopes проверены; consumer собирается в обоих режимах |
| Debug instrumentation | Оба ожидаемых smoke-теста реально выполнены, 0 failures/errors/skips |
| Release instrumentation | Оба ожидаемых smoke-теста реально выполнены после R8/resource shrinking, 0 failures/errors/skips |

## Команды и артефакты

- `python3 scripts/verify-baseline.py` — сборка, тесты, кеши, Maven consumer, отрицательная проверка API.
- `python3 scripts/verify-device.py --serial emulator-5554` — оба runtime-варианта с обязательной проверкой XML.
- `build/reports/playground-validation/result.json` и соседние логи.
- `build/reports/playground-device-validation/result.json` и отдельные debug/release логи.
- `plugin-build/build/reports/tests/test/index.html`.
- `sdk/build/reports/fat-aar/release/dependencies.txt`, `abi.txt`, `inventory.txt`.

Release проверяется с тестовой подписью и правилами доступа для instrumentation.
Сохранены два SDK-фасада и общий тестовый runtime; fixture-код оптимизируется обычным R8.
Полные условия описаны в [документации валидатора](dependency-validation.md).

## Что обнаружили runtime-проверки

Успешный Gradle connected-test запуск мог содержать ноль тестов из-за падения runner.
Отдельный скрипт теперь требует оба ожидаемых test case в XML и отвергает такой результат.
Общие с тестовым APK зависимости нужно сохранить в тестируемом release APK.

Resource shrinker удалял `fixture_dynamic_name`, используемый через getIdentifier.
Правило tools:keep теперь принадлежит самому библиотечному модулю ресурсов.
Переименование ресурсов не добавлялось.

## Граница этапа

AAR всё ещё модульный baseline. Реестр и ABI-модель задают будущий состав SDK,
но не объединяют его физически. Полный binary linkage/Kotlin metadata, подпись,
сравнение merger-кандидатов, настоящая упаковка и relocation остаются дальнейшей работой.

Следующий завершённый этап — [сравнение merger-кандидатов](merger-candidate-results.md),
включая проверку подписей и consumer без внутренних артефактов. Этот отчёт сохраняет
границу предыдущей итерации.
