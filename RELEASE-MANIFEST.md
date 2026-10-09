# N9Ezz Universal Loader 0.4.1 experimental

Проверено 9 октября 2026 года. Minecraft 1.21.11 / Java 21.

| Артефакт | Bytes | SHA-256 |
| --- | --- | --- |
| `dist/N9Ezz-Universal-Loader-0.4.1-experimental.jar` | 53,561,899 | `b81855307752b3d20df9bff66175914d2a118e7cce688c15e24b153952c60b3b` |
| `dist/N9Ezz-Universal-Loader-0.4.1-experimental-sources.zip` | 46,892,313 | `b5451d33b11c389cadf1b8d85879a9f625faed60022f6cc9dae87ff9864dc960` |
| `dist/N9Ezz-Universal-Loader-0.4.1-test-reports.zip` | 658,209 | `d1f2daa9714132896caf2b61cf3a30a6deb98e108a769b7f8de80d238b25435e` |

Один установщик, встроенный N9Ezz и профиль `N9Ezz-Universal-1.21.11`.
Для установки нужен Java 21 runtime и установленный vanilla Minecraft 1.21.11.

```sh
java -jar N9Ezz-Universal-Loader-0.4.1-experimental.jar
```

## Ограничение модов

Внешние моды принимаются только с собственным `n9ezz.mod.json` и N9Ezz API.
Чужие platform descriptors, смешанные descriptors и внешние вложенные JAR
отклоняются до исполнения mod code. Проверяются также дополнительные библиотеки.
Встроенные N9Ezz/Fabric API остаются закреплёнными SHA-256 компонентами.
Проверка формата не является цифровой подписью автора или Java sandbox.
Правила и проверенный Java-пример: [native-mod-policy](docs/native-mod-policy.md).
Файлы пользователя не удаляются. Обычные моды нельзя подключать через прежние
внешние Quilt/FML contracts; эти адаптеры остаются внутренними компонентами.

## Проверки

Полная сборка и все suites завершились с exit0. Новая политика: 36 PASS;
core: 54 PASS; Quilt/FML internals: 34/33 PASS; installer: 11 PASS;
3036 проверок готового встроенного TargetGeometry.class без его перекомпиляции.
Четыре настоящих platform fixtures предыдущей 0.4.0 отклонены точным новым JAR
с EXTERNAL_MOD_NOT_N9EZZ. Пример HelloN9 из документации скомпилирован,
упакован, проверен и исполнен через CLI.

Точный JAR прошёл настоящий demo-world запуск Minecraft с N9Ezz и двумя native
observer mods. Оба acceptance JSON PASS, без replay. Наблюдались инициализация,
реальные world/player, integrated server, INIT/JOIN и render callbacks.
Demo modal приостановил сервер: длительная симуляция и атаки/PvP не заявлены.
Точные числа и ограничения: [release-test-report-0.4.1](docs/release-test-report-0.4.1.md).

Итоговый source ZIP извлечён в `/tmp/n9ezz-reproduce-041-final`.
Скопирован только проверенный dependency cache (13 inputs).
Команда полной offline-пересборки:

```sh
source /workspace/toolchains/activate-temurin21.sh
python3 /tmp/n9ezz-reproduce-041-final/universal-loader/build.py --offline
```

Exit0, без --skip-tests. **JAR и source ZIP воспроизведены побайтно.**
Логи и receipt: `dist/tests-0.4.1/isolated-build.log`, `reproduction.json`.
144 исходных файла client и встроенные client/API payloads сохранены побайтно.
Логика клиентских модулей и отправки пакетов не менялась.

## Исходники и незавершённые части

Source ZIP содержит собственный Java-код, addon sources, pinned binary inputs,
licenses/notices и соответствующие TinyRemapper sources. Исходников полного
Pulse host нет, source-only пересборка этого компонента не заявлена.
Полные game patches/registries Forge/NeoForge, ранний QSL, общий remapping
произвольных mods и dedicated server остаются незавершёнными.
Запрет внешних platform mods не означает завершения этих механизмов.

Проверены шесть приложений пользователя. Neo installer относится к 1.21.6;
Forge-named JAR — vanilla1.21.11; дополнительный JSON — профиль со ссылками на
отдельные Forge libraries; client.txt — официальные Mojang mappings.
Сами Forge runtime libraries не приложены. Полные unmodified Mojang mappings
не включены в распространяемые архивы; текущие intermediary mappings сохранены.
Подробности: [аудит файлов](docs/uploaded-platforms-0.4.1.md).

## Сохранённые версии и среда

Старые 0.1/0.2 baseline и 0.3.0/0.3.1/0.4.0 release JAR/ZIP сохранены.
Manifest/checksums 0.4.0 находятся в `dist/RELEASE-MANIFEST-0.4.0.md` и
`dist/SHA256SUMS-0.4.0`. Старые игровые результаты относятся к старым бинарникам.

Облачные startup instructions обновлены для новой политики и native world probe.
Черновик сохранён (requires_publish=true); для применения в новых задачах
нужно сохранить настройки и опубликовать среду. Восстановление новой машины
отдельно не проверялось.
