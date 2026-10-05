# Конфликты ресурсов и бинарные ссылки

Проверка выполняется при сборке конечного AAR; отдельный ручной анализ не нужен.
Имена ресурсов сохраняются, включая имена для `getIdentifier`.

## Ресурсы

Одинаковые файловые ресурсы объединяются по байтам. Для values XML сравниваются
элементы с учётом namespace и содержимого; порядок XML-атрибутов, комментарии и
отступы между структурными элементами не создают конфликт. Значимое содержимое
строк сохраняется. После удаления дубликата остальные определения остаются в архиве.
Values XML без изменений сохраняется побайтово.

Для разных определений задаётся конкретный победитель:

```kotlin
fatAar {
    // Пустой qualifier: обычные values без night/locale и других qualifiers.
    resourceWinners.put("/string/shared_title", "sdk")
    resourceWinners.put("night-v8/string/shared_title", "project::feature")
    // Без qualifier правило применяется ко всем конфликтующим qualifiers.
    resourceWinners.put("drawable/shared_icon", "module:example:library")
}
```

Полный ключ имеет вид `qualifier/type/name`; используется qualifier из готового AAR,
который Android может нормализовать, например `night-v8`. Ошибка содержит точный ключ.
Владелец: `sdk`, `project::path` либо `module:group:name`. Без правила разные
определения дают `FATAAR_RESOURCE_CONFLICT`. Неизвестный владелец и неиспользованное
правило дают отдельную ошибку, чтобы опечатка не оставалась незаметной.

`attr` может быть определён глобально или внутри `declare-styleable`. Общие определения
выносятся из конфликтующих блоков так, чтобы удаление дубликата styleable не удаляло
сам атрибут. Полностью совместимые styleable объединяются. Разные списки или порядок
атрибутов остаются ошибкой `FATAAR_STYLEABLE_CONFLICT`: изменение порядка ломает
индексы, которые компилятор уже мог записать в код модулей. Произвольное объединение
частей одного styleable пока не поддерживается.

Явный overlay означает выбор одного значения или файла для всех модулей, ссылающихся
на это имя. Он не создаёт раздельные ресурсы для разных модулей. В частности, автор
SDK должен согласовать структуру конфликтующих layouts с их binding-классами.

## Бинарные ссылки

После мержа выполняется кешируемая задача `validate<Variant>Linkage`. Её вход —
конечные классы AAR, выбранные внешние runtime AAR/JAR и Android bootclasspath.
Она проверяет тела всех методов, включая private, а также дескрипторы классов,
полей/методов, наследование, catch-типы, массивы, class literals, method handles и
ссылки из invokedynamic. Результат учитывает уже выполненную замену ссылок на R SDK.

Проверяются наличие класса/метода/поля, точный JVM-дескриптор, static/instance,
видимость членов с учётом наследования и nestmates, class/interface invocation.
Конструкторы не ищутся у родителя. Array clone и signature-polymorphic методы
MethodHandle/VarHandle имеют специальную обработку по
[правилам JVM resolution](https://docs.oracle.com/javase/specs/jvms/se12/html/jvms-5.html).
Стандартные javac bootstrap-вызовы для lambda, string concat и records обслуживаются
Android D8 desugaring, поэтому отсутствие соответствующего bootstrap в android.jar
не считается пропавшей библиотекой.

Ошибки называются `FATAAR_LINKAGE_MISSING_CLASS`, `MISSING_METHOD`, `MISSING_FIELD`,
`STATIC_MISMATCH`, `ACCESS` и `KIND` с префиксом `FATAAR_LINKAGE_`. Диагностика содержит
вызывающий класс/метод, точную отсутствующую ссылку и выбранные runtime-артефакты.
`linkage.txt` находится в `sdk/build/reports/fat-aar/<variant>/`.

R-поля проверяются по R.txt, поскольку настоящий R создаётся в consumer. Data Binding
BR и DataBindingComponent также создаются в consumer; подтверждённые соответствующей
metadata типы явно отмечаются как `deferred-consumer-generated` в отчёте, а их
поведение проверяется Android-полигоном. Их члены не выдаются за проверенные этой задачей.

Это анализ статических ссылок, а не полный JVM verifier: не проверяются stack frames,
все правила protected receiver/максимально специфичных default methods и semantic API
compatibility. Reflection, JNI/dlsym, Kotlin metadata и строковые ссылки требуют
отдельных проверок; аннотации не трактуются как обязательные runtime-ссылки.

## Автоматический полигон

```sh
python3 scripts/verify-resource-linkage.py
./gradlew verifyPlayground :app:assembleDebugAndroidTest
```

Первый сценарий создаёт реальные Android-модули и внешние Java-библиотеки, собирает
debug/release AAR/APK, анализирует финальный AAR, запускает Java-код из его classes.jar,
проверяет configuration/build cache и overlays SDK/дочернего модуля. Затем отдельно
удаляет класс, метод и поле, меняет дескриптор и static/instance, делает метод private.
Каждый сценарий обязан остановить сборку правильной ошибкой. Код встроенного модуля
компилируется против исходной API-библиотеки и не пересобирается против сломанной runtime,
поэтому ошибки обнаруживает именно валидатор бинарных ссылок.

Отчёт: `build/reports/resource-linkage-validation/result.json`. Этот сценарий не
запускает публикацию или тесты на устройстве; его выполнение Java-кода не считается
проверкой Android resource IDs на устройстве.
