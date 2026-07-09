# JS Scanner — Спецификация

Анализатор JavaScript-файлов для поиска API-эндпоинтов, конфигурационных переменных и скрытых механизмов данных.

## 1. Общий подход

Для каждой загруженной HTML страницы:

1. Найти все теги `<script src="URL">` и `<script>` (inline)
2. Скачать все внешние JS файлы (асинхронно, с ограничением 20 файлов)
3. Применить набор regex-паттернов к каждому файлу
4. Классифицировать находки по типу
5. Группировать по base URL
6. Представить результат пользователю

## 2. Паттерны поиска

### 2.1 API Endpoints

```kotlin
// fetch вызовы
val FETCH_PATTERN = Regex(
    """fetch\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// axios вызовы
val AXIOS_PATTERN = Regex(
    """axios\.(?:get|post|put|delete|patch)\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// XMLHttpRequest
val XHR_PATTERN = Regex(
    """(?:new\s+)?XMLHttpRequest""",
    RegexOption.IGNORE_CASE
)

// jQuery AJAX
val JQUERY_AJAX_PATTERN = Regex(
    """\.ajax\s*\(\s*\{[^}]*?url\s*[:=]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// $.get / $.post shorthand
val JQUERY_SHORTHAND = Regex(
    """\$\s*\.\s*(?:get|post)\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// Open/location
val XHR_OPEN = Regex(
    """\.open\s*\(\s*['"`](?:GET|POST)['"`]\s*,\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)
```

### 2.2 WordPress AJAX

```kotlin
// admin-ajax.php
val WP_AJAX_URL = Regex(
    """(?:ajaxurl|ajax_url)\s*[:=]\s*['"`]([^'"`]*)admin-ajax\.php[^'"`]*['"`]""",
    RegexOption.IGNORE_CASE
)

// AJAX actions
val WP_AJAX_ACTION = Regex(
    """['"`]action['"`]\s*[:=]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// wpApiSettings (стандартный WordPress JS объект)
val WP_API_SETTINGS = Regex(
    """wpApiSettings\s*=\s*\{[^}]*?"root"\s*:\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)
```

### 2.3 Config Variables

```kotlin
// API Base URL переменные
val API_BASE_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:api|Api|API)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// Base URL переменные
val BASE_URL_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:base|Base)[uU]rl\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// API ключи и токены
val API_KEY_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:apiKey|siteId|token|secret)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

// Endpoint переменные
val ENDPOINT_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:endpoint|Endpoint)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)
```

### 2.4 Embedded Data

```kotlin
// Next.js __NEXT_DATA__
val NEXT_DATA = Regex(
    """__NEXT_DATA__\s*=\s*(\{.+?\});""",
    RegexOption.DOT_MATCHES_ALL
)

// Nuxt __NUXT__
val NUXT_DATA = Regex(
    """__NUXT__\s*=\s*(\{.+?\});""",
    RegexOption.DOT_MATCHES_ALL
)

// JSON-LD (уже из HTML, но можно и из JS)
val JSON_LD = Regex(
    """application/ld\+json""",
    RegexOption.IGNORE_CASE
)
```

### 2.5 Custom Encoding Functions

```kotlin
// Поиск функций, похожих на encode/decode
val CUSTOM_ENCODE = Regex(
    """function\s+\w*[Ee]ncode\w*\s*\([^)]*\)""",
    RegexOption.IGNORE_CASE
)

val CUSTOM_DECODE = Regex(
    """function\s+\w*[Dd]ecode\w*\s*\([^)]*\)""",
    RegexOption.IGNORE_CASE
)

// Base64 с кастомным алфавитом (как в quanben5)
val CUSTOM_BASE64 = Regex(
    """(?:base64|Base64)\s*=\s*['"`]([A-Za-z0-9+/]{20,})['"`]""",
    RegexOption.IGNORE_CASE
)

// String manipulation patterns (obfuscation)
val STRING_MANIP = Regex(
    """.*\.(?:charCodeAt|fromCharCode|substring|split|join).*""",
    RegexOption.IGNORE_CASE
)
```

### 2.6 GraphQL

```kotlin
val GRAPHQL_ENDPOINT = Regex(
    """['"`](/graphql|/api/graphql|/v1/graphql)['"`]""",
    RegexOption.IGNORE_CASE
)

val GRAPHQL_QUERY = Regex(
    """(?:query|mutation)\s+\w+\s*\{""",
    RegexOption.IGNORE_CASE
)
```

## 3. Классификация находок

После сбора всех URL из JS, каждый классифицируется:

```kotlin
enum class EndpointCategory {
    CATALOG,          // listing, browse, novels, page, catalog
    SEARCH,           // search, find, query, s
    BOOK_DETAIL,      // detail, info, manga, book, novel, get
    CHAPTER_LIST,     // chapters, releases, pages
    CHAPTER_TEXT,     // content, reader, chapter, text, view
    GENRES,           // genres, tags, categories
    AUTH,             // login, auth, token, session
    OTHER             // неопределённый
}

fun classifyEndpoint(url: String): EndpointCategory {
    val path = url.lowercase()
    return when {
        path.contains("chapter") || path.contains("release") -> CHAPTER_LIST
        path.contains("search") || path.contains("query") || path.contains("find") -> SEARCH
        path.contains("catalog") || path.contains("browse") || path.contains("listing") -> CATALOG
        path.contains("manga") || path.contains("novel") || path.contains("book") && 
            (path.contains("detail") || path.contains("info") || path.contains("get")) -> BOOK_DETAIL
        path.contains("genre") || path.contains("tag") || path.contains("category") -> GENRES
        path.contains("login") || path.contains("auth") || path.contains("token") -> AUTH
        path.contains("reader") || path.contains("content") || path.contains("text") -> CHAPTER_TEXT
        else -> OTHER
    }
}
```

## 4. Примеры анализа

### Пример: RanobeLib (JS содержит API-конфиг)

```javascript
// В JS файле найден объект:
window.__APP_CONFIG__ = {
    apiBase: "https://api.cdnlibs.org/api/manga/",
    siteId: "3",
    headers: {
        "Site-Id": "3"
    }
};
```

**Результат JS Scanner:**
```
API Endpoints Found:
  apiBase = "https://api.cdnlibs.org/api/manga/"
  siteId = "3"
  
Endpoint classification:
  GET https://api.cdnlibs.org/api/manga/ → CATALOG (содержит "manga")
  GET https://api.cdnlibs.org/api/manga/{slug} → BOOK_DETAIL
  GET https://api.cdnlibs.org/api/manga/{slug}/chapters → CHAPTER_LIST
  GET https://api.cdnlibs.org/api/manga/{slug}/chapter? → CHAPTER_TEXT
```

### Пример: ScribbleHub (WordPress AJAX)

```javascript
// В JS найден объект:
var ajaxurl = 'https://www.scribblehub.com/wp-admin/admin-ajax.php';

// AJAX вызов:
$.ajax({
    url: ajaxurl,
    data: { action: 'wi_getreleases_pagination', pagenum: -1, mypostid: id }
});
```

**Результат JS Scanner:**
```
WordPress AJAX Detected:
  ajaxurl = "https://www.scribblehub.com/wp-admin/admin-ajax.php"
  action: "wi_getreleases_pagination"
    params: pagenum, mypostid → CHAPTER_LIST
```

### Пример: Quanben5 (кастомный base64 в JS)

```javascript
// В JS файле найдена функция:
var staticChars = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN";

function customEncode(str) {
    var result = '';
    for (var i = 0; i < str.length; i++) {
        var num0 = staticChars.indexOf(str[i]);
        var code = num0 >= 0 ? staticChars[(num0 + 3) % 62] : str[i];
        result += 'P' + code + 'P';
    }
    return result;
}
```

**Результат JS Scanner:**
```
Custom Encoding Detected:
  function: customEncode
  alphabet: "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN"
  pattern: Оборачивает каждый символ в 'P' + код + 'P'
  
Suspected API:
  JSONP endpoint: ?c=book&a=search.json&callback=search
```

## 5. Ограничения

- **Static analysis only** — не выполняем JavaScript, только regex
- **Minified JS** — сложнее читать, но паттерны обычно сохраняются
- **Динамические URL** (собранные из переменных) — не детектируются, только статические строки
- **Webpack/другие бандлеры** — пути могут быть обфусцированы
- **JCEF Proxy Mode** (Phase 4) — решит这些问题 через перехват реальных запросов в браузере
