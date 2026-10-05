# BOM и платформы

BOM определяет версии зависимостей и сам не содержит классов или ресурсов. SDK явно
учитывает платформу как `api(platform(...))` либо `implementation(platform(...))`.
Каждая библиотека отдельно объявляется как `embed`, `api`, `implementation` или
`runtimeOnly`. Ограничение версии в BOM не считается доставкой библиотеки.

```kotlin
dependencies {
    api(platform("example:sdk-bom:2"))
    api("example:public-model")
    implementation("example:engine")
    add("embed", "example:private-utils")
}
```

Разрешение runtime graph выбирает версию встраиваемого архива: если запросили
`private-utils:1`, а платформа выбрала `2`, упаковывается именно выбранная версия `2`.
Внешние библиотеки остаются в метаданных публикации и приходят к потребителю через SDK.
Встроенные библиотеки удаляются из dependency list публикации.

Вложенные платформы учитываются по настоящему графу зависимостей. Достаточно объявить
внешнюю платформу, которая действительно предоставляет вложенную. При пропуске
платформы сборка сообщает `FATAAR_MISSING_PLATFORM`, полный путь зависимости и варианты
объявления. `addMissingDependencies` не выбирает API/implementation за пользователя.
Попытка `embed(platform(...))` завершается `FATAAR_EMBED_PLATFORM`.

`enforcedPlatform` сейчас не поддерживается для SDK: `FATAAR_ENFORCED_PLATFORM`.
Такая платформа распространяет принудительные версии на потребителей, и публикация
получается с другим контрактом совместимости. Используем обычную платформу: потребитель
может выбрать совместимую более новую версию. Это явное ограничение текущей реализации.
[Контракт Gradle platforms](https://docs.gradle.org/current/userguide/platforms.html).

Если BOM объявлен только через implementation, он не попадает в API variant GMM.
Для versionless API dependency нужна API-платформа; иначе её версию задаём явно.
Валидатор ABI разрешает именно внешний API classpath, поэтому такой пропуск
обнаруживается до публикации SDK.

В Gradle Module Metadata сохраняются различия API/runtime. У POM импорт BOM находится
в `dependencyManagement`, поэтому POM не передаёт это различие полностью. Объявленные
библиотеки сохраняют compile/runtime scopes. POM-only потребителей проверяем отдельно.
Строгие/rich constraints, capabilities и конфликты платформ с разными правилами пока
не заявляем как полностью покрытые. Отдельная приватная платформа только для выбора
встроенных архивов, без её публикации потребителю, пока не реализована.

## Проверка реального артефакта

```sh
python3 scripts/verify-bom.py
```

Полигон `integration-tests/bom` публикует два вложенных BOM, реальные JVM библиотеки
версий 1/2/3 и два SDK с разными scopes платформы. Потребитель находится в отдельной
сборке и получает только координату SDK. Проверяются:

- выбранные версии и содержимое `classes.jar`, отсутствие внешних классов в SDK;
- versionless API dependency при API-платформе: компиляция SDK и consumer получает
  версию через BOM; при implementation-платформе API dependency имеет явную версию;
- настоящие POM/GMM, отсутствие внутренних модулей в dependencies;
- четыре APK: API/implementation × metadata/POM-only, реальные DEX definitions;
- выбор потребителем engine версии 3 поверх рекомендованной 2;
- запуск кода из опубликованного SDK с реально разрешёнными внешними JAR;
- configuration cache, восстановление AAR из build cache и понятные ошибки.

Запуск Java в этом полигоне проверяет поведение классов без Android API. Он не выдаётся
за запуск Android-приложения на устройстве. Остальные Android-функции проверяет
`verify-all.py` с instrumentation tests.

Отчёт: `build/reports/bom-validation/result.json`, 35 этапов проверки.
