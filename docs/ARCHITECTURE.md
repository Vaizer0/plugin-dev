# Архитектура Plugin Development Studio

## Общий обзор

Desktop-приложение на Compose Multiplatform (JVM), которое переиспользует ключевые компоненты из Android-проекта Novela (LuaJ, Jsoup, OkHttp) и добавляет слой анализа веб-сайтов для полу-автоматической генерации Lua-плагинов.

## Модульная структура

```
┌─────────────────────────────────────────────────────────────────┐
│                        app (Compose Desktop)                     │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Plugin        │ │ Site         │ │ Function │ │ Code      │  │
│  │ Browser       │ │ Explorer     │ │ Runner   │ │ Editor    │  │
│  └──────┬───────┘ └──────┬───────┘ └────┬─────┘ └─────┬─────┘  │
│         │                │              │              │        │
├─────────┼────────────────┼──────────────┼──────────────┼────────┤
│    core-engine (переиспользован из scraper модуля)              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐    │
│  │ LuaEngine    │ │ SourceInter- │ │ LuaSourceAdapter     │    │
│  │ (LuaJ)       │ │ face + моде- │ │ (Lua → Kotlin мост) │    │
│  │ + 40 API     │ │ ли           │ │ + фабрика подклассов │    │
│  │ функций      │ │              │ │                      │    │
│  └──────────────┘ └──────────────┘ └──────────────────────┘    │
│         │                │              │                       │
├─────────┼────────────────┼──────────────┼───────────────────────┤
│    site-analyzer (новый модуль)                                 │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐    │
│  │ DOM Analyzer │ │ JS Scanner   │ │ Pattern Matcher      │    │
│  │ (Jsoup)      │ │ (Regex +     │ │ (сравнение с 31      │    │
│  │ - структура  │ │  GraalJS?)  │ │  известным шаблоном) │    │
│  │ - повторы    │ │ - API URLs   │ │                      │    │
│  │ - CMS def    │ │ - fetch/XHR  │ │ -> CMS detection     │    │
│  │ - формы      │ │ - config      │ │ -> Selector sugg.    │    │
│  │ - пагинация  │ │   variables  │ │ -> API suggestion    │    │
│  └──────────────┘ └──────────────┘ └──────────────────────┘    │
│         │                │              │                       │
├─────────┼────────────────┼──────────────┼───────────────────────┤
│    pattern-db (база знаний)                                      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐    │
│  │ Plugin       │ │ CMS Profiles │ │ Templates            │    │
│  │ Patterns     │ │ - Madara     │ │ - код для каждого    │    │
│  │ (из 31 .lua) │ │ - NovelBin   │ │   типа плагина      │    │
│  │              │ │ - API-First  │ │ - шаблоны функций    │    │
│  │              │ │ - Russian WP │ │                      │    │
│  └──────────────┘ └──────────────┘ └──────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

## Слои архитектуры

### 1. UI Layer (app)

Compose Desktop экраны:

| Эран | Назначение |
|------|-----------|
| PluginBrowser | Список плагинов из папки, метаданные, статус |
| SiteExplorer | Результаты анализа URL: DOM, JS, API, CMS |
| FunctionRunner | Выбор функции, параметры, запуск, результат |
| PluginWizard | 6-шаговый мастер создания плагина |
| CodeEditor | Редактор Lua с подсветкой |
| Console | Логи, ошибки, предупреждения |
| NetworkInspector | Хронология HTTP запросов |

### 2. Core Engine (core-engine)

Портирован из `scraper` модуля Android-проекта с заменой Android-зависимостей:

| Компонент | Изменения для Desktop |
|-----------|---------------------|
| `LuaEngine` | Убран `@ApplicationContext`. `SharedPreferences` -> `java.util.prefs.Preferences`. `android.util.Base64` -> `java.util.Base64` |
| `LuaSourceAdapter` | Убран `android.content.Context`. `@StringRes` -> String. |
| `SourceInterface` | Убран `android.content.Context` из `resolveName()` |
| `BookResult`, `ChapterResult` | Без изменений |
| `Response<T>`, `PagedList<T>` | Без изменений |
| `LuaFilter`, `LuaSetting` | Без изменений |
| `NetworkClient` | Адаптирован: убран Android-specific код, OkHttp остаётся |

### 3. Site Analyzer (site-analyzer)

#### DOM Analyzer

Анализирует загруженный HTML:

1. **Структурный анализ**:
   - Построение дерева тегов с частотами
   - Поиск повторяющихся блоков (одинаковый CSS-путь, > 3 экземпляров)
   - Группировка по типу: список, таблица, карточки

2. **Детекция элементов**:
   - Заголовки: `h1-h6`, классы содержащие "title", "name", "head"
   - Ссылки: `a[href]`, группировка по паттернам URL
   - Изображения: `img`, `picture`, `figure`
   - Формы: `form` с `input[type=search]`, `input[name=s]`
   - Пагинация: `a` с текстом "next", "›", "»", page numbers

3. **Детекция CMS/фреймворка**:
   - WordPress: `<meta name="generator" content="WordPress">`
   - Madara theme: `wp-content/themes/madara`
   - NovelBin: структура `.col-truyen-main .row`
   - ScribbleHub: `.search_main_box`
   - Next.js: `__NEXT_DATA__`
   - Custom API: отсутствие характерных HTML-маркеров

4. **Embedded JSON**:
   - JSON-LD: `<script type="application/ld+json">`
   - Next.js: `<script>window.__NEXT_DATA__`
   - Nuxt: `<script>window.__NUXT__`
   - Shopify: `window.Shopify`

#### JS Scanner

Сканирует JavaScript-файлы на предмет API-эндпоинтов. Детальная спецификация — в `JS_SCANNER_SPEC.md`.

### 4. Pattern Database (pattern-db)

Хранит знания, извлечённые из 31 существующего плагина:

```kotlin
data class SitePattern(
    val id: String,
    val name: String,
    val cmsProbes: List<Probe>,         // Как определить CMS
    val catalogPattern: PagePattern?,    // Как парсить каталог
    val searchPattern: PagePattern?,     // Как парсить поиск
    val bookPattern: PagePattern?,       // Как парсить страницу книги
    val chapterListPattern: PagePattern?, // Как парсить список глав
    val chapterTextPattern: PagePattern?, // Как парсить текст главы
    val dataSource: DataSourceType       // HTML | API | AJAX | JSONP
)

data class PagePattern(
    val containerSelector: String?,      // CSS селектор контейнера
    val titleSelector: String?,          // CSS селектор заголовка
    val urlSelector: String?,            // CSS селектор ссылки
    val coverSelector: String?,          // CSS селектор обложки
    val apiEndpoint: String?,            // API URL (если не HTML)
    val httpMethod: String?,             // GET / POST
    val httpHeaders: Map<String, String>?, // Заголовки
    val pagination: PaginationType       // Как определяется hasNext
)
```

### Data Flow: создание плагина через Wizard

```
User вводит URL каталога
    → Site Analyzer загружает HTML
    → DOM Analyzer находит повторяющиеся блоки
    → Pattern Matcher сравнивает с БД (31 плагин)
    → Определяет: "Это NovelBin clone"
    → Предлагает селекторы:
        .col-truyen-main .row → карточки
        div.col-xs-7 > div > h3 > a → заголовок + ссылка

User вводит URL страницы новеллы
    → JS Scanner загружает и анализирует JS
    → Находит в JS: ajaxurl = "/wp-admin/admin-ajax.php"
    → Находит в JS: action = "manga_get_chapters"
    → Предлагает: список глав через POST AJAX

User нажимает [Generate Plugin]
    → Generator берёт шаблон для NovelBin clone
    → Подставляет найденные селекторы
    → Генерирует .lua файл

User нажимает [Test Run]
    → Function Runner загружает плагин в LuaEngine
    → Вызывает getCatalogList(0)
    → Показывает результат + HTTP запросы
```

## Зависимости

```kotlin
// build.gradle.kts (app module)
dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Core Engine
    implementation(project(":core-engine"))
    implementation("org.luaj:luaj-jse:3.0.2")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")

    // Serialization
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:2.2")

    // DI
    implementation("io.insert-koin:koin-core:3.5.3")
}
```
