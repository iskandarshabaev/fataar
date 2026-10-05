# Сравнение merger-кандидатов и решение

Проверено 6 сентября 2026 года: Gradle **9.7.1**, AGP **9.4.0**, JDK 21,
compile SDK 36, min SDK 24. Configuration cache включён с `problems=fail`.

**Выбор: собственный merge pipeline внутри `com.fataar` на публичном AGP Artifacts API.**
Fused 9.4.0 и UmbrellaAAR 3.0.0 не проходят обязательные требования в проверенной
конфигурации. Это решение по двум конкретным версиям, а не утверждение о невозможности
исправить их или об отсутствии других плагинов.

Корневой `:sdk` остаётся единственным SDK-модулем с собственным кодом. Его production
упаковка пока является модульным baseline. Настоящий объединённый AAR в этой итерации
построен только в sandbox кандидата и **не принят для использования**.

## Воспроизведение

```sh
python3 scripts/verify-candidates.py
```

Скрипт копирует исходники SDK и fixtures в `build/candidates/umbrella`, создаёт минимальный
Fused-проект в `build/candidates/fused` и отдельный consumer в `build/candidates/consumer`.
Fixture conventions берутся из существующего `plugin-build`. Корневые Android-модули
не редактируются. Сборки используют закреплённый wrapper и обычный Gradle user cache.

Публикация происходит только в `build/candidates/umbrella/repository` и содержит одну
SDK coordinate. Внутренние fixture-библиотеки туда не публикуются. Consumer не имеет
`includeBuild`, project substitution или ссылки на корневой baseline repository.

При каждом обычном запуске generated sandbox удаляется и создаётся заново. Для диагностики
есть `--prepare-only`, `--reuse`, `--candidate fused` и `--candidate umbrella`.
`--reuse` оставляет изменения в generated-копии; итоговый контрольный прогон выполнен без него.

Логи, полный inventory и итоговый JSON: `build/reports/candidate-validation/`.
`result.json` удаляется в начале запуска и появляется только после выполнения всех проверок.
Нулевой exit code означает, что результаты сравнения воспроизведены, включая ожидаемые
отрицательные сценарии. Он **не означает**, что кандидаты пригодны для выпуска SDK.

## Подтверждённые результаты

| Проверка | Fused 9.4.0 | UmbrellaAAR 3.0.0 |
| --- | --- | --- |
| Обычный `com.android.library` в том же модуле | Прямой запрет совместного применения | SDK с собственными исходниками собирается |
| Прямое объявление внешнего `api` | Конфигурация отсутствует | ConstraintLayout и core попадают в compile scope POM; consumer компилируется |
| Сохранение приватного `implementation` | Не проверялось после начальных отказов | Нарушено: `org.jetbrains:annotations:26.0.2` превращается в compile scope |
| Применение `signing` | Ошибка повторного `assemble` | Успех; подписи AAR, POM и sources JAR криптографически проверены |
| Gradle Module Metadata | Merge/publication не запускались | `.module` не создаётся |
| Configuration cache | Повторный `help` использует кеш; это не тест merge | Повторная подписанная публикация использует кеш |
| Build cache | Merge не запускался | Extract/main/merge/bundle восстановлены `FROM-CACHE` после clean |
| Пересборка без build cache | Не проверялась | SHA-256 AAR совпал после `--rerun-tasks --no-build-cache` |
| Изменение входного asset | Не проверялось | Новый payload попал в AAR; после восстановления исходника вернулся исходный SHA-256 |
| Классы 12 fixtures + SDK | Не проверялось | Все входные class definitions найдены в `classes.jar`; проверены дубли entries |
| Consumer debug, только SDK coordinate | Не проверялось | Собирается в обоих режимах метаданных |
| Consumer release, R8 + resource shrinking | Не проверялось | Ошибка трёх отсутствующих R-классов в обоих режимах |
| Runtime на устройстве | Не запускался | Не запускался: кандидат уже отклонён по release-сборке |

## Fused: минимальные несовместимости

Контрольный проект сначала успешно выполняет `help`, затем тот же проект по одному
получает `api`, `signing` и `com.android.library`. Это отделяет ошибки кандидата от
ошибок его базовой настройки. Для 9.4 используется актуальный DSL:
`minSdk { version = release(24) }`.

Воспроизведены сообщения:

```text
Configuration with name 'api' not found.
Cannot add task 'assemble' as a task with that name already exists.
'com.android.library' and 'com.android.fused-library' plugins cannot be applied in the same project.
```

Первое сообщение относится к прямому `api(...)` в fused-модуле. Оно не доказывает,
что зависимости API включённых библиотек вообще не могут попадать в fused publication:
этот отдельный сценарий здесь не проверялся. Но требуемый пользователем единый SDK
с собственными исходниками и стандартным DSL уже несовместим с этим вариантом интеграции.

Полная сборка Fused после этих отказов не запускалась. Документированные ограничения
Data Binding/Prefab и вариантов остаются дополнительными доводами, а не результатами
нашего локального теста. [Официальное руководство Fused](https://developer.android.com/build/publish-library/fused-library).

## UmbrellaAAR: успешная публикация с неработающим release consumer

Проверена опубликованная версия **3.0.0**, а не предполагаемая ветка разработки.
Tag `3.0.0` указывает на `3531da52b7d941746c0137182cd04c61c3d9d7e8`.
[Plugin marker 3.0.0](https://plugins.gradle.org/m2/io/github/tiper/umbrellaaar/io.github.tiper.umbrellaaar.gradle.plugin/3.0.0/io.github.tiper.umbrellaaar.gradle.plugin-3.0.0.pom),
[исходники опубликованного артефакта](https://repo.maven.apache.org/maven2/io/github/tiper/umbrellaaar/3.0.0/umbrellaaar-3.0.0-sources.jar).

Применены `io.github.tiper.umbrellaaar` и companion `io.github.tiper.umbrellaaar.pom`.
Наши девять корневых `embed` заменены на `export`; кандидат сам дошёл до 12 fixtures
по графу. Чтобы исходники SDK видели эти модули при компиляции, явно добавлено
`implementation.extendsFrom(export)`: сам `export` выбирает входы упаковки.
Наш `com.fataar` в sandbox не применяется, поэтому его проверки не влияют на результат.

Публикации получили разные artifactId, и запускается только release umbrella publication.
Это устраняет описанную автором коллизию координат baseline/debug/release публикаций,
не меняя merger или POM генератор кандидата. [Настройка и ограничения 3.0.0](https://github.com/tiper/UmbrellaAAR/blob/3531da52b7d941746c0137182cd04c61c3d9d7e8/README.md).

В конечном AAR есть классы всех входов, assets, Java resources, SPI descriptor и keep-файл
для строкового поиска ресурса. Но исходники **самого SDK** сохранили ссылки:

```text
SdkPanel -> com.fataar.fixtures.resourcesfiles.R$layout
SdkProbe -> com.fataar.fixtures.resourcesfiles.R$raw
SdkProbe -> com.fataar.fixtures.resourcesvalues.R$integer
```

Объединённый AAR имеет SDK namespace. Consumer генерирует его R, а исходные namespace
внутренних модулей больше не представлены отдельными AAR. R8 обнаруживает все три
отсутствующих класса. Исходники кандидата объясняют результат: `ExtractDependencies`
переписывает R-ссылки embedded-входов, а `ExtractMainAar` только распаковывает host AAR.
Тот факт, что присутствуют все входные class definitions, не гарантирует корректность ссылок.

Consumer также компилирует API с ConstraintLayout/core без прямых зависимостей на них.
Однако контрольная приватная зависимость `implementation("org.jetbrains:annotations:26.0.2")`
в POM имеет `compile`, а не `runtime`. `.module` отсутствует, поэтому режим с разрешённым
Gradle metadata фактически использует POM fallback. Два режима consumer не следует
называть проверкой двух реально опубликованных форматов метаданных.

Для signing создаётся новая одноразовая OpenPGP identity через Bouncy Castle из закреплённого
Gradle distribution. Настоящие ключи разработчика не используются. Подписи проверяются
открытым ключом; намеренно изменённый AAR с прежней подписью должен отвергаться.
Тестовый секретный ключ хранится только в generated sandbox, без пароля, и не предназначен
для реальных публикаций. Подпись `.module` проверить невозможно, поскольку его нет.

## Архитектурное решение и следующий этап

Готовые плагины не включаем как транзитивный backend `com.fataar`. Исправление UmbrellaAAR
потребовало бы собственного слоя публикации, более строгого resource/conflict validation,
новой обработки host bytecode и в дальнейшем отказа от поиска чужих задач по именам
ради корректного variant/artifact selection. Для наших требований это уже существенная
часть самостоятельного merger.

Переиспользуем публичные библиотеки и проверенные подходы: ASM для bytecode,
Android manifest merger, детерминированные архивы и Gradle cacheable tasks.
Код сторонних плагинов в репозиторий этой итерацией не копировался. Если позже понадобится
заимствовать конкретную реализацию, отдельно сохранить её лицензию и attribution.

Следующая реализация — один законченный путь для существующих 12 fixtures:

1. Разрешать embedded AAR/JAR по variant-aware artifact collections и существующей
   проверенной delivery policy, передавать задачам только сериализуемые inputs.
2. Объединить ресурсы с исходными именами, Manifest, classes/JAR resources, assets и consumer
   rules. Определить явные ошибки конфликтов; учесть `R.txt`, attrs/styleable и `public.txt`.
3. Обработать R-ссылки **во всех** включённых классах, включая собственный код SDK.
   Проверить определения, ссылки и resource symbols; не выдавать простое наличие классов
   за полный linkage check. Не менять значения inlined styleable indices без согласованной
   таблицы символов; конфликтующие определения должны останавливаться явно.
4. Подключить результат через публичный `SingleArtifact.AAR` transform обычного `:sdk`.
   Сохранить внешние API/runtime scopes и исключить embedded project coordinates в обоих
   форматах публикации; отдельно проверить, что demo project consumer получает тот же результат.
5. Подписать AAR/POM/module и собрать независимый consumer, в репозитории которого есть
   только SDK. После успешного R8 — device smoke tests debug/release с обязательными test cases.
6. Проверить cache reuse, FROM-CACHE, изменение входов и правила конфликта. Затем расширять
   полигон на flavors, bindings, native/Prefab и прочие ещё не покрытые Android возможности.

Общий package relocation остаётся следующим самостоятельным этапом. Здесь R-ссылки
обсуждаются как обязательная часть объединения namespace; переименование ресурсов
по-прежнему исключено.
