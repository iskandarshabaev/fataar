# AIDL, ViewBinding и DataBinding

Полигон включает четыре дополнительных Android-модуля: `aidl`, `view-binding`,
`data-binding-left` и `data-binding-right`. Основной `:sdk` также содержит собственный
DataBinding layout. SDK остаётся единственным production-модулем сборки библиотеки.

## Контракт использования

```kotlin
// В SDK с DataBinding-модулями.
android { buildFeatures { dataBinding = true } }
dependencies {
    "embed"(project(":features:bindings"))
    // Публичные/protected типы ViewDataBinding должны быть доступны через API SDK.
    api("androidx.collection:collection:1.0.0")
}

// В приложении, которое использует SDK с DataBinding.
android { buildFeatures { dataBinding = true } }
```

Включение DataBinding у приложения требуется стандартным контрактом Android даже
когда само приложение не содержит binding layouts:
[Android: настройка DataBinding](https://developer.android.com/topic/libraries/data-binding/start).
AIDL/viewBinding включаются в тех модулях, которые сами генерируют соответствующий код.
Для использования уже скомпилированного AIDL-интерфейса отдельная генерация в приложении
не нужна; полигон включает её, чтобы проверить импорт Parcelable из SDK.

AGP автоматически добавляет зависимости DataBinding. Валидатор продолжает проверять
полный внешний API, включая protected методы базовых классов. В полигоне это обнаружило
`LongSparseArray` из `androidx.collection`: SDK теперь экспортирует эту зависимость явно.

## Что передаётся в AAR

- AIDL: generated interfaces, Stub/Proxy, Parcelable и Service в `classes.jar`;
  экспортированные `.aidl` декларации в `aidl/`. Разные байты по одному пути — ошибка,
  одинаковые декларации объединяются. Перечень экспорта задаётся producer-модулем через AGP.
- ViewBinding: generated binding class, XML layout, IDs и values. Bytecode R-ссылки
  преобразуются в namespace SDK существующим механизмом merger.
- DataBinding: binding base/implementation classes, mapper каждого модуля, layouts,
  BR declarations, setter stores и binding class logs.
- Ссылки `module_package` встроенных модулей в class logs переводятся на namespace SDK, чтобы DataBinding
  compiler потребителя создавал корректные обращения к R при `<include>`.
  Имена layouts, binding-классов, переменных и packages для BR сохраняются.
- BR declarations и setter stores копируются без изменения содержимого. UTF-16 class logs
  сохраняют кодировку при переписывании resource owner.

Проверенный формат AGP 9.4.0: `data-binding/*-br.bin`, `*-setter_store.json` с AndroidX/version 5
и `data-binding-base-class-log/*-binding_classes.json`. Другие payload formats отклоняются явно.
Java serialization BR-файлов сохраняется непрозрачно; merger проверяет заголовок,
а реальная генерация потребителя проверяет чтение данных своим AGP.

## Generated types и compile classpath

AGP исключает BR и `androidx.databinding.DataBindingComponent` из библиотечного AAR,
затем генерирует их у потребителя. ABI-проверка учитывает только этот известный
consumer-generated interface при наличии paired metadata и mapper. Это записывается
в `abi.txt` как `consumer-generated=androidx/databinding/DataBindingComponent`.
Обычные отсутствующие API-типы по-прежнему приводят к ошибке.

Для project consumer merger предоставляет два вторичных JAR:

- Compile JAR содержит конечные классы и compile-only R с объединёнными символами.
  R-поля не final и не имеют ConstantValue: компилятор сохраняет GETSTATIC вместо
  встраивания placeholder IDs. Unit-тест проверяет это реальной компиляцией Java.
- Runtime JAR содержит только `classes.jar` конечного AAR. R stubs в него не входят;
  настоящие resource IDs генерирует AGP приложения.

Maven publication передаёт обычный AAR. Compile-only JAR и R stubs в публикацию не попадают.
Без отдельного compile R новый код DataBinding project consumer не мог обращаться
к `com.fataar.sdk.R.layout`, хотя ресурсы уже были доступны при упаковке APK.

## Автоматические проверки

`verify-all.py` включает всё ниже без ручного анализа артефактов:

1. Чтение настоящего AAR: generated classes, 9 metadata files, Parcelable AIDL declaration,
   mapper classes, namespace rewrite и неизменённые BR/setter-store bytes.
2. Проверку compile R в отдельном JAR и его отсутствия в runtime JAR/конечном AAR.
3. Реальную генерацию AIDL у потребителя с импортом `FixturePacket` из SDK.
4. Реальную генерацию собственного DataBinding layout потребителя: `<include>` layouts
   из двух модулей, разные variables и BindingAdapter из embedded-модуля.
5. На устройстве: ViewBinding inflate/bind, DataBinding inflate, setters, ObservableField
   update, BR lookup и binding layout самого SDK.
6. AIDL Service запускается в отдельном процессе. Проверяются отличающийся PID и
   Parcelable round trip с изменением строки/числа — вызов проходит через Binder Proxy.
7. Все три новых device-теста выполняются у project, Maven metadata и POM-only consumers,
   в debug и release после R8/resource shrinking.
8. Отрицательные сборки: конфликт AIDL, invalid JSON, отсутствие paired BR metadata
   и отсутствие binding implementation. Сценарии преобразуют настоящий producer AAR;
   после каждого восстанавливается SDK и проверяется исходный SHA-256.

Основной device-набор теперь содержит 7 именованных тестов в каждом consumer/variant.
Отчёты предыдущего запуска удаляются; отсутствующие, пропущенные и ошибочные test cases
не считаются успехом. Источники shared consumer code используются только для кода
приложения: внутренние production-модули доступны Maven-потребителю через опубликованный SDK.

```sh
python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35
python3 scripts/verify-merged.py
python3 scripts/verify-device.py --serial emulator-5554 --consumer maven-pom
```

## Границы текущего полигона

Проверены handwritten Parcelable declarations, обычный AIDL IPC, generated Java bindings,
static BindingAdapter, ObservableField и includes. AIDL structured parcelables/oneway,
instance BindingAdapters, two-way binding, lifecycle/LiveData и Kotlin/KAPT layouts
требуют новых сценариев. Внешние DataBinding-библиотеки из Maven теперь проверяются
по реальным API AAR: их resource namespaces сохраняются, классы и layout symbols
валидируются отдельно. Неизвестные namespace отклоняются.
[Контракт и полигон внешних bindings](external-binding-validation.md).

Проверки выполняются на закреплённых версиях Gradle/AGP и Android 15. Общая class relocation,
переписывание Kotlin metadata и resource renaming не входят в этот этап.

## Подтверждённый прогон 2 октября 2026

Полный запуск `verify-all.py` завершился со статусом PASS:

- 38 JVM/TestKit тестов плагина и 2 теста DEX parser.
- 60 этапов проверки артефактов: 39 основного SDK и 21 flavor-полигона.
- 56 instrumentation tests: 42 основных и 14 flavor; без ошибок и пропусков.
- Все 49 классов конечного SDK присутствуют в AAR и определены в debug DEX consumers.
- Сохранены 24 res/assets payload, 9 binding metadata files и Parcelable AIDL declaration.
- Configuration/build cache, подписи и пересборка без cache прошли проверку.
- SDK AAR SHA-256: `761872f31a4bb3f7c93f3744ccf1044f964694f18eb205f522cfe668a162304e`.
- Тестовый registry восстановлен, запущенный скриптом эмулятор закрыт.
