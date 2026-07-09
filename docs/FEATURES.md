# Функционал Plugin Development Studio

## 1. Plugin Browser

Браузер плагинов для загрузки и управления `.lua` файлами.

**Возможности:**
- Открыть папку с `.lua` файлами (через File → Open Folder)
- Авто-обнаружение: поиск всех `.lua` файлов в папке и подпапках
- Отображение метаданных: id, name, version, baseUrl, language
- Статус функций: какие из 7 обязательных присутствуют/отсутствуют (зелёный/красный индикатор)
- Статус опциональных функций: getFilterList, parsePage, getSettingsSchema и т.д.
- Группировка по языку (en, ru, zh, ja, id, fr, mtl)
- Поиск/фильтрация по имени и id

**UI:**
```
┌────────────────────────────────────┐
│ Plugin Browser         [Search...] │
├────────────────────────────────────┤
│ ▼ English (14)                     │
│   ✓ Royal Road       v1.0.2  [8/9]│
│   ✓ ScribbleHub      v1.0.1  [8/9]│
│   ✗ NovelFull        v1.0.3  [5/9]│ ← отсутствуют функции
│   ✓ NovelBin         v1.1.1  [9/9]│
│ ▼ Russian (5)                      │
│   ✓ RanobeLib        v1.0.5  [7/9]│
│ ▼ Chinese (7)                      │
│   ✓ Quanben5         v1.0.0  [7/9]│
│ ▼ Indonesian (3)                   │
│ ─────────────────────────────────  │
│ [Open Folder]  [Refresh]  [New]    │
└────────────────────────────────────┘
```

## 2. Function Runner

Запуск любой функции плагина с просмотром результата.

**Возможности:**
- Выбор функции из дропдауна
- Авто-генерация полей ввода на основе сигнатуры функции:
  - `getCatalogList(index)` → поле `index` (Int, default 0)
  - `getBookTitle(bookUrl)` → поле `bookUrl` (String)
  - `getChapterText(html, url)` → поле `html` (можно вставить URL для загрузки) + `url`
  - `parsePage(bookUrl, page)` → поля `bookUrl` + `page`
- Кнопка [Run] с асинхронным выполнением
- Отображение результата:
  - **Tab: Tree** — структурированное дерево (раскрывающиеся таблицы/массивы)
  - **Tab: Raw** — сырой JSON/Lua вывод
  - **Tab: HTML Preview** — для `getChapterText`, рендеринг текста
  - **Tab: Error** — если функция упала с ошибкой (стектрейс)

**UI:**
```
┌────────────────────────────────────────────────┐
│ Plugin: Royal Road    Func: [getCatalogList ▼] │
│ Index: [0    ]  [Run]  [Auto-fill params]      │
├────────────────────────────────────────────────┤
│ Result  │ Raw │ HTML Preview │ Error           │
│ ┌────────────────────────────────────────────┐ │
│ │ [0] PagedList                              │ │
│ │   list: [3 items]                          │ │
│ │     [0] BookResult                         │ │
│ │       title: "The Primal Hunter"          │ │
│ │       url: "https://royalroad.com/..."    │ │
│ │       coverImageUrl: "https://..."         │ │
│ │     [1] BookResult                         │ │
│ │       title: "Defiance of the Fall"        │ │
│ │       ...                                  │ │
│ │   isLastPage: false                        │ │
│ └────────────────────────────────────────────┘ │
├────────────────────────────────────────────────┤
│ Network: 2 requests, 342ms                    │
│ ✓ GET /fictions/best-rated          200 150ms │
│ ✓ GET /fictions/best-rated?page=2  200 192ms │
└────────────────────────────────────────────────┘
```

## 3. Site Explorer

Анализ любого URL для понимания структуры сайта.

**Возможности:**
- Ввод URL → загрузка HTML + JS → полный анализ
- **CMS Detection**: WordPress, Madara, NovelBin clone, ScribbleHub, Next.js, Custom
- **DOM Analysis**:
  - Повторяющиеся блоки (каталог/списки)
  - Заголовки, ссылки, изображения
  - Формы поиска (method, action, поля)
  - Пагинация
  - Embedded JSON (JSON-LD, __NEXT_DATA__)
- **JS Scanner**: API endpoints, fetch/XHR вызовы, config variables
- **Encoding Detection**: UTF-8, GBK, другие
- **Характеристики**: requiresLogin, charset, baseUrl кандидаты

**UI:**
```
┌────────────────────────────────────────────────────────────┐
│ Site Explorer                      URL: [https://...] [Go] │
├────────────────────────────────────────────────────────────┤
│ Summary                                                    │
│ ├─ CMS: WordPress + Madara Theme                           │
│ ├─ Encoding: UTF-8                                         │
│ ├─ Login required: No                                      │
│ └─ Base URL: https://site.com                              │
│                                                            │
│ ┌─────── DOM Analysis ──────────────────────────────────┐  │
│ │ 📋 Catalog items: .c-tabs-item__content (24 elems)   │  │
│ │   ├─ Title: .post-title h3 a                          │  │
│ │   ├─ URL:   a[href] (from .post-title h3 a)          │  │
│ │   └─ Cover: .c-image-hover img[data-src]             │  │
│ │ 📄 Chapter list: POST /wp-admin/admin-ajax.php        │  │
│ │   └─ action: manga_get_chapters                      │  │
│ │ 📖 Chapter text: .reading-content                    │  │
│ │ 🔍 Search: ?s=QUERY&post_type=wp-manga               │  │
│ └──────────────────────────────────────────────────────┘  │
│                                                            │
│ ┌─────── JS Scanner ───────────────────────────────────┐  │
│ │ Found in main.js:                                     │  │
│ │   var ajaxurl = 'https://site.com/wp-admin/...'      │  │
│ │   action: 'manga_get_chapters'                       │  │
│ │   action: 'manga_get_book_info'                      │  │
│ │                                                       │  │
│ │ Found in listing.js:                                  │  │
│ │   /api/manga/listing?page=                            │  │
│ │   /api/manga/search?q=                                │  │
│ └──────────────────────────────────────────────────────┘  │
│                                                            │
│ [Generate Plugin] [Copy as Template] [Test Endpoint]      │
└────────────────────────────────────────────────────────────┘
```

## 4. Plugin Wizard

Пошаговый мастер создания нового плагина.

### Шаг 1: Базовая информация
- ID плагина (проверка на уникальность)
- Name (отображаемое имя)
- Base URL сайта
- Language (выбор из списка ISO 639-1)
- Charset (UTF-8 по умолчанию, опционально GBK)
- Icon URL (опционально)

### Шаг 2: URL страниц
Пользователь указывает URL для каждой секции (хотя бы 1 обязателен):

| URL | Для функции | Опциональность |
|-----|-------------|---------------|
| Catalog URL | `getCatalogList` | Required |
| Search URL | `getCatalogSearch` | Required |
| Novel URL | `getBookTitle/Description/Cover/Genres`, `getChapterList` | Required |
| Chapter URL | `getChapterText` | Required |

Кнопка [Fetch All] — загружает все страницы сразу.

### Шаг 3: Анализ страницы каталога/поиска

Site Analyzer показывает найденные повторяющиеся элементы. Пользователь подтверждает/корректирует:

```
┌──────────────────────────────────────────────────────────┐
│ Catalog Page Analysis                                     │
├──────────────────────────────────────────────────────────┤
│ 🔍 Found 24 repeating items                               │
│                                                          │
│ Container: [.c-tabs-item__content        ] ▼ 24 matches │
│ Title:     [.post-title h3 a             ] ▼             │
│ URL:       [same as title href           ] ▼             │
│ Cover:     [.c-image-hover img[data-src] ] ▼             │
│                                                          │
│ hasNext: [✅ #items > 0] [⬜ explicit next page link]     │
│          [⬜ page number in URL]                          │
│                                                          │
│ [Preview Selection] [DOM Tree Viewer]                     │
└──────────────────────────────────────────────────────────┘
```

### Шаг 4: Анализ страницы новеллы

```
┌──────────────────────────────────────────────────────────┐
│ Novel Page Analysis                                       │
├──────────────────────────────────────────────────────────┤
│ Title:       [h1                   ] ▼ "My Novel Title" │
│ Cover:       [.summary_image img   ] ▼ https://...      │
│ Description: [.summary__content    ] ▼ "Long desc..."   │
│ Genres:      [.genres-content a    ] ▼ [Action, Fantasy]│
│                                                          │
│ Chapter List:                                            │
│ ○ On-page: [ul.chapter-list li a  ] ▼ 45 chapters      │
│ ● AJAX:    POST /wp-admin/admin-ajax.php                 │
│            action: [manga_get_chapters      ]            │
│ ○ API:     [GET /api/manga/{id}/chapters    ]            │
│ ○ JSONP:   [callback=...                    ]            │
│                                                          │
│ [Detect Automatically]                                    │
└──────────────────────────────────────────────────────────┘
```

### Шаг 5: Анализ текста главы

```
┌──────────────────────────────────────────────────────────┐
│ Chapter Text Analysis                                     │
├──────────────────────────────────────────────────────────┤
│ Content container: [.reading-content       ] ▼           │
│ Remove elements:   [script, style, .ads    ] ▼           │
│                                                          │
│ Preview:                                                 │
│ ┌────────────────────────────────────────────────────┐  │
│ │ Chapter 1: The Beginning                           │  │
│ │                                                    │  │
│ │ It was a dark and stormy night when the adventure  │  │
│ │ first began. The wind howled through the trees...  │  │
│ │ ...                                                │  │
│ └────────────────────────────────────────────────────┘  │
│                                                          │
│ Text quality: ✅ Good (no ads, clean paragraphs)         │
│ [Regenerate Preview]                                     │
└──────────────────────────────────────────────────────────┘
```

### Шаг 6: Генерация
- Полный предпросмотр сгенерированного `.lua` кода
- Кнопка [Generate] → создаёт файл
- Кнопка [Test Run] → сразу прогоняет все функции
- Кнопка [Save] → сохраняет на диск

## 5. Plugin Repair

Починка сломанного/неполного плагина.

**Возможности:**
- Открыть `.lua` файл → парсинг всех функций и селекторов
- Для каждой функции:
  - Выполнить → проверить результат
  - Если падает → запустить Site Analyzer для соответствующего URL
  - Сравнить старый DOM/API с новым
  - Предложить исправления
- **Diff View**: подсветка изменений (старый селектор → новый)
- **Batch Repair**: исправить все сломанные функции разом

**UI:**
```
┌──────────────────────────────────────────────────────────┐
│ Plugin Repair: novelfull.lua (3 broken functions)        │
├──────────────────────────────────────────────────────────┤
│ Function     │ Status │ Old Selector   │ New Suggestion │
│──────────────┼────────┼────────────────┼────────────────│
│ getBookTitle │ ❌     │ h3.title       │ h1.entry-title │
│ getBookDesc  │ ❌     │ .desc-text     │ .summary        │
│ getChapterT  │ ❌     │ #chapter-con   │ .entry-content  │
│              │        │ tent           │                 │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ ┌── Diff: getBookTitle ──────────────────────────────┐  │
│ │ - local el = html_select_first(r.body, "h3.title") │  │
│ │ + local el = html_select_first(r.body,             │  │
│ │ +     "h1.entry-title")                            │  │
│ └────────────────────────────────────────────────────┘  │
│                                                          │
│ [Apply All Fixes] [Apply Selected] [View Report]         │
└──────────────────────────────────────────────────────────┘
```

## 6. Network Inspector

Просмотр всех HTTP запросов, выполненных плагином.

**Возможности:**
- Хронология запросов с таймингами
- Request: URL, method, headers, body
- Response: status code, headers, body (с подсветкой HTML/JSON)
- Cache indicator (если используется fetchPage)

## 7. Console

Логи и ошибки выполнения.

| Level | Source | Пример |
|-------|--------|--------|
| ERROR | Lua | `attempt to index a nil value (global 'r')` |
| ERROR | HTTP | `GET https://... 404 Not Found` |
| WARN | Validator | `Missing optional: getFilterList` |
| INFO | Plugin | `Lua: royalroad: loaded 25 chapters` |
| INFO | System | `Loaded plugin: royal_road v1.0.2` |

## 8. Validator Report

Пакетная проверка всех плагинов.

| Плагин | getCatalogList | getBookTitle | getChapterList | getChapterText | ... | Счёт |
|--------|---------------|-------------|---------------|---------------|-----|------|
| royal_road | ✅ | ✅ | ✅ | ✅ | ... | 9/9 |
| scribblehub | ✅ | ✅ | ✅ | ⚠️ пустой | ... | 8/9 |
| novelfull | ✅ | ❌ 404 | ✅ | ✅ | ... | 7/9 |
