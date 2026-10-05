# Результаты первой итерации

Исторический отчёт за 5 сентября. Текущее состояние второй итерации описано
в [документации валидатора](dependency-validation.md).

Проверено 5 сентября 2026 года на macOS arm64.
Gradle 9.7.1, AGP 9.4.0, JDK запуска Temurin 21.0.9, toolchain 17,
Android compile/target SDK 36, min SDK 24.

## Реализовано

- Один `sdk` с собственным кодом, ресурсом и Manifest; демо зависит только от SDK.
- 12 fixture-модулей; их покрытие перечислено в [матрице](playground-matrix.md).
- Included build `plugin-build`, публичная AGP-интеграция `com.fataar`, инвентаризация
  реального AAR, диагностика отсутствующего Manifest и дублирующихся классов.
- Модульная Maven-публикация baseline с API/runtime scopes и независимый consumer.
- Оба кеша включены; configuration-cache problems завершают сборку ошибкой.

## Фактически выполненные проверки

| Проверка | Результат |
| --- | --- |
| `python3 scripts/verify-baseline.py` | Успешно, все шесть этапов |
| Demo debug / minified release | APK собраны, R8 завершился успешно |
| Android lint debug | Без ошибок; остались предупреждения шаблона, unused resources и более новых SDK/ConstraintLayout |
| Plugin unit tests | 3 теста: порядок/расположение архива, отсутствующий Manifest, дубликат класса |
| Plugin TestKit | 1 тест: configuration-cache reuse, UP-TO-DATE, FROM-CACHE, перенос каталога, изменение входа |
| Gradle `validatePlugins` | Успешно |
| JVM fixture unit test | 1 тест успешно |
| Повтор корневой сборки | `Configuration cache entry reused` |
| SDK POM / `.module` | ConstraintLayout передаётся как API; internal implementation не попадает в API variant |
| Отдельный Maven consumer | Debug и R8 release собраны через `.module` и в POM-only режиме |
| Instrumentation tests | APK с двумя smoke-тестами собран; на устройстве не запускались |
| Gradle Wrapper JAR | SHA-256 совпал с опубликованным: `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |

Автоматически создаваемый отчёт: `build/reports/playground-validation/result.json`.
Рядом лежат полные логи. Тесты плагина: `plugin-build/build/reports/tests/test/index.html`.
Инвентаризация SDK: `sdk/build/reports/fat-aar/release/inventory.txt`.

Запрос `adb devices -l` завис и был остановлен; работающий Android runtime в этой сессии
не проверен. После подключения устройства выполнить `./gradlew :app:connectedDebugAndroidTest`.

## Границы результата

Полученный `sdk-release.aar` содержит собственный SDK-код, а зависимости пока поступают
из отдельно опубликованных fixture-артефактов. Это ожидаемый baseline, не fat AAR.
Проверки подтверждают работоспособность исходного графа и публикации, а не полноту merge.

Ещё не реализованы: объединение AAR, dependency closure/ABI validator,
`addMissingDependencies`, подпись публикации и репакетирование классов.
Форматы JNI/Prefab/DataBinding/Compose/AIDL и другие расширения из матрицы ещё не покрыты.
Переименование ресурсов исключено по принятому решению.
