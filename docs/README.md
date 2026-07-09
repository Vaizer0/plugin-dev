# Plugin Development Studio

**Desktop GUI для создания, отладки, тестирования и починки Lua-плагинов для Novela (NoveLA).**

Инструмент анализирует структуру целевых сайтов-источников новелл, находит CSS-селекторы и API-эндпоинты (включая скрытые в JavaScript), и полу-автоматически генерирует рабочие Lua-плагины.

## Возможности

- **Site Explorer** — анализ любого URL: DOM-структура, API в JS, CMS, кодировка
- **JS Scanner** — поиск API-эндпоинтов, fetch/axios вызовов, admin-ajax.php в JavaScript
- **Plugin Wizard** — пошаговое создание плагина через выбор найденных элементов
- **Plugin Repair** — открыть сломанный плагин, инструмент сам найдёт новые селекторы/эндпоинты
- **Function Runner** — запуск любой функции плагина с просмотром результата и HTTP запросов
- **Pattern Database** — база знаний из 31 существующего плагина для авто-распознавания CMS и структур

## Технологии

- **Kotlin** + **Compose Multiplatform Desktop**
- **LuaJ** — исполнение Lua-плагинов
- **OkHttp** — HTTP клиент
- **Jsoup** — HTML парсинг и DOM анализ
- **Gson** — JSON
- **SnakeYAML** — YAML
- **Koin** — DI

## Фазы разработки

1. **Core Engine** — LuaEngine, Plugin Browser, Function Runner
2. **Site Analyzer** — DOM анализ, JS Scanner, Pattern Database
3. **Wizard + Repair** — создание и починка плагинов
4. **IDE + Polish** — редактор кода, live preview, экспорт

## Структура репозитория

```
plugin-dev-studio/
├── app/                    # Compose Desktop приложение
├── core-engine/            # LuaEngine + адаптеры (port из scraper)
├── site-analyzer/          # DOM + JS анализатор
├── pattern-db/             # База знаний паттернов
└── docs/
    ├── ARCHITECTURE.md
    ├── FEATURES.md
    ├── ROADMAP.md
    ├── PATTERN_DATABASE.md
    ├── JS_SCANNER_SPEC.md
    └── UI_WIREFRAMES.md
```
