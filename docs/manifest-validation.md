# Manifest в объединённом SDK

Проверка выполняется на настоящих Android AAR и конечных APK через
`python3 scripts/verify-all.py --avd Pixel_9_Pro_XL_API_35`.

## Объединение

Manifest собственного SDK имеет приоритет над Manifest встроенных модулей.
Используется Android Manifest Merger в режиме library. SDK задаёт `tools:replace`
для приоритетного metadata value и `tools:node="remove"` для удаления metadata и permission.
Встроенный модуль объявляет компоненты относительными именами `.FixtureActivity` и т. п.;
в конечном AAR они должны быть полными именами в исходном namespace.

`${applicationId}` и пользовательский `${fataarChannel}` остаются в library Manifest.
Демо задаёт `project-channel`, независимый Maven consumer — `maven-channel`.
Окончательные значения проверяются через PackageManager уже установленного приложения.
Consumer этого полигона должен задать `android.defaultConfig.manifestPlaceholders["fataarChannel"]`.
Это требование fixture SDK, а не дополнительный обязательный placeholder плагина.

Markers `tools` могут оставаться в AAR и применяться при следующем merge consumer.
Это стандартное поведение library Manifest; в конечном application Manifest они удаляются.
[Официальный контракт Android Manifest Merger](https://developer.android.com/build/manage-manifests).
Проверка AAR учитывает remove markers, а runtime-проверка требует физического отсутствия
удалённых metadata/permissions в установленном приложении.

## Положительные проверки

- Конечный AAR содержит Activity, Service, Receiver и Provider с `exported=false`.
- Все четыре класса действительно определены в merged `classes.jar`.
- Relative class names преобразованы в исходные полные имена.
- Provider authority в AAR — `${applicationId}.fixture`, в APK — package consumer + `.fixture`.
- Metadata override содержит `sdk-value`, удалённого metadata в приложении нет.
- ACCESS_NETWORK_STATE сохранён, VIBRATE удалён.
- Пользовательский placeholder подставляется consumer.
- Query provider с той же authority сохраняется как декларация видимости и не считается вторым компонентом.
- Activity реально запускается и устанавливает marker title.
- Service реально создаётся и привязывается; Binder возвращает marker descriptor.
- Receiver реально получает ordered broadcast и возвращает marker result.
- Provider запускается и отвечает через ContentResolver (проверяется основным smoke-тестом).

Одинаковые instrumentation tests выполняются для project, Maven metadata и POM-only
consumer в debug и release с R8. Исходники компонентов/resources к Maven consumer не подключаются.

## Отрицательные сборки

Тестовые init scripts меняют модель только проверочного запуска; исходники не редактируются.
После каждого сценария выполняется обычная сборка SDK.

| Сценарий | Ожидаемая ошибка |
| --- | --- |
| Разные metadata values без tools:replace | `FATAAR_MANIFEST_CONFLICT`, inputs и диагностика Android merger |
| Два разных Provider с общей authority, включая списки через `;` | `FATAAR_DUPLICATE_PROVIDER_AUTHORITY`, оба класса и inputs |
| Embedded library minSdk 25 при SDK minSdk 24 | `FATAAR_MIN_SDK_CONFLICT`, модуль и обе версии |

Manifest Merger сопоставляет providers по классу. Поэтому разные классы с одинаковой
authority требуют дополнительного guard: после merge плагин проверяет каждый элемент
списка authorities активных Provider внутри application. Узлы с remove/removeAll и
providers внутри queries не учитываются.

## Границы проверки

Проверяются совпадающие literal/symbolic authorities. Два разных placeholder, которым
consumer позднее присвоит одно значение, нельзя доказать конфликтующими на этапе сборки AAR.
Для них требуется проверка конечного consumer. Другие комбинации tools markers,
`tools:selector`, дополнительные intent-filters, видимость сторонних пакетов через queries
и component aliases пока не покрыты этим этапом.

Плагин требует, чтобы minSdk каждой embedded library не превышал minSdk SDK.
Небезопасный `tools:overrideLibrary` не отменяет этот guard. Preview minSdk отклоняется явно.
Наличие компонентов и их запуска проверено текущими fixtures; это ещё не общий
linkage validator для произвольных классов/динамических ссылок из Manifest.

## Подтверждённый результат

1 октября 2026: 29 JVM/TestKit тестов, 30 проверок реальных артефактов
и 24 instrumentation tests завершились успешно. Четыре компонента работают
в project, Maven metadata и POM-only consumers, debug и R8 release.
Отчёт: `build/reports/full-validation/result.json`.
