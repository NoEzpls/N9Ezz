# Проверка нативного N9Ezz Loader

Проверено 13 октября 2026 года локально на Temurin 21.0.8. Использован ранее доступный JAR `N9Ezz-Universal-Loader-0.4.1-network.1-experimental.jar`, SHA-256 `bd6c983ac22f436e914f5510faa598a18c218f08e10c8b5c36504ebf31e40100`. Обращение к GitHub, получение исходников из репозитория и сетевые игровые соединения для этих проверок не использовались.

Проверенный снимок `N9Ezz-CF-Assistant-0.2.2-experimental.jar` имеет SHA-256 `73bf18a5e633886463e330edfc600177946e88a4e5e5490dbef0bb2e1ef456f5`. Это контрольная сумма конкретной проверенной сборки; после изменения исходников нужна повторная проверка итогового JAR.

Созданы отдельные временные каталоги игры и модов. В каталоге модов находится только проверяемый JAR CF Assistant. Формат вызова:

```bash
"$JAVA_HOME/bin/java" -jar "$LOADER_JAR" --verify --game-dir "$TEST_GAME_DIR" --mods-dir "$TEST_MODS_DIR"
"$JAVA_HOME/bin/java" -jar "$LOADER_JAR" --run --game-dir "$TEST_GAME_DIR" --mods-dir "$TEST_MODS_DIR"
```

Первая команда завершилась с кодом `0`. Полный вывод:

```json
{
  "mode": "verify-no-entrypoints",
  "mods": [
    "cf_assistant@0.2.2-experimental"
  ],
  "lifecycle": [
    "DISCOVERED",
    "RESOLVED",
    "TRANSFORMERS_READY",
    "VERIFY_ONLY_COMPLETE"
  ],
  "diagnostics": []
}
```

Вторая команда завершилась с кодом `0`. Полный вывод:

```text
[CF] game runtime absent; API-only load, no game hooks installed
{
  "mode": "api-only-runtime",
  "mods": [
    "cf_assistant@0.2.2-experimental"
  ],
  "lifecycle": [
    "DISCOVERED",
    "RESOLVED",
    "TRANSFORMERS_READY",
    "COMMON_SETUP",
    "CLIENT_SETUP",
    "ENTRYPOINT:cf_assistant:client",
    "READY"
  ],
  "diagnostics": []
}
```

Loader распознал нативный дескриптор, разрешил зависимости и вызвал клиентскую точку входа без диагностических ошибок. Minecraft в этом режиме отсутствует: игровые перехватчики не устанавливались, подключение к RaidMine и реальные ставки не выполнялись. Эта проверка не подтверждает совместимость с NeoForge или Minecraft 1.21.11.
