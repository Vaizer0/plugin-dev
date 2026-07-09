# UI Wireframes

## Главное окно

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Plugin Development Studio                         [File] [Tools] [Help] │
├───────┬──────────────────────────────────────────────────────────────────┤
│       │  Tab Bar                                                        │
│       │  [Plugin Browser] [Site Explorer] [Function Runner] [Editor]    │
│       ├──────────────────────────────────────────────────────────────────┤
│       │                                                                  │
│  SIDE │  MAIN CONTENT AREA                                              │
│  BAR  │                                                                  │
│       │                                                                  │
│       │                                                                  │
│       │                                                                  │
│       │                                                                  │
│       │                                                                  │
│       │                                                                  │
│       │                                                                  │
├───────┴──────────────────────────────────────────────────────────────────┤
│  Console: [Errors: 0] [Warnings: 2] [Info: 5]  [Clear]  [Auto-scroll] │
│  [12:00:01] INFO  Loaded plugin: royal_road v1.0.2                     │
│  [12:00:02] WARN  Missing optional function: getFilterList              │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Plugin Browser

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Plugin Browser                                        [Search...] [≡] │
├──────────────────────────────────┬───────────────────────────────────────┤
│  📁 Folder: external-sources/   │  Plugin Details (selected: Royal Road)│
│  ─────────────────────────────── │  ┌─────────────────────────────────┐ │
│  ▼ English (14)                  │  │ id:       royal_road            │ │
│    ✓ Royal Road       v1.0.2    │  │ name:     Royal Road            │ │
│      [lang: en] [9/9 funcs]    │  │ version:  1.0.2                  │ │
│    ✓ ScribbleHub      v1.0.1    │  │ baseUrl:  royalroad.com         │ │
│      [lang: en] [8/9 funcs]    │  │ language: en                     │ │
│    ✓ NovelFull       v1.0.3    │  │ icon:     ✓                      │ │
│      [lang: en] [9/9 funcs]    │  ├─────────────────────────────────┤ │
│    ✓ NovelBin        v1.1.1    │  │ Functions: [9/9]                 │ │
│      [lang: en] [8/9 funcs]    │  │ ✅ getCatalogList                 │ │
│    ...                          │  │ ✅ getCatalogSearch              │ │
│  ▼ Russian (5)                  │  │ ✅ getBookTitle                  │ │
│    ✓ RanobeLib       v1.0.5    │  │ ✅ getBookCoverImageUrl           │ │
│    ✓ Jaomix          v1.0.3    │  │ ✅ getBookDescription             │ │
│    ✓ IFreedom        v1.0.1    │  │ ✅ getChapterList                 │ │
│    ✓ BookHamster     v1.0.0    │  │ ✅ getChapterText                 │ │
│    ✓ RanobeHub       v1.0.2    │  │ ✅ getFilterList                  │ │
│  ▼ Chinese (7)                  │  │ ⬜ getBookGenres [optional]       │ │
│    ✓ Quanben5        v1.0.0    │  │ ⬜ parsePage     [optional]       │ │
│    ✓ 69shuba         v1.0.1    │  ├─────────────────────────────────┤ │
│    ✓ PiaoTia         v1.0.0    │  │ [Validate] [Run Functions]       │ │
│    ...                          │  └─────────────────────────────────┘ │
│  ▼ Indonesian (3)               │                                       │
│  ▼ Japanese (1)                 │                                       │
│  ▼ French (1)                   │                                       │
│  ▼ MTL (1)                      │                                       │
├──────────────────────────────────┴───────────────────────────────────────┤
│  Console: [12:00:01] INFO  Loaded 31 plugins from external-sources/      │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Site Explorer

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Site Explorer                               URL: [https://...] [Analyze]│
├──────────────────────────────────────────────────────────────────────────┤
│  Tabs: [Summary] [DOM Analysis] [JS Scanner] [Raw HTML] [Raw JS]       │
│                                                                          │
│  ┌──── Summary ──────────────────────────────────────────────────────┐ │
│  │                                                                    │ │
│  │  🌐 https://ranobelib.me/                                          │ │
│  │  ─────────────────────────────────────────────────────             │ │
│  │                                                                    │ │
│  │  📋 CMS: Custom API-based (not WordPress)                        │ │
│  │  🔤 Encoding: UTF-8                                              │ │
│  │  🔑 Login required: No                                           │ │
│  │  📂 Base URL: https://ranobelib.me/                               │ │
│  │                                                                    │ │
│  │  📊 Data Source: JSON API (рекомендуется)                         │ │
│  │                                                                    │ │
│  │  ┌─ Found API Endpoints ───────────────────────────────────────┐ │ │
│  │  │ 🔵 GET /api/manga/?site_id[0]=3&page=...                    │ │ │
│  │  │     → Catalog (listing)                                     │ │ │
│  │  │ 🔵 GET /api/manga/?site_id[0]=3&q=...                       │ │ │
│  │  │     → Search                                                │ │ │
│  │  │ 🔵 GET /api/manga/{slug}                                    │ │ │
│  │  │     → Book details                                          │ │ │
│  │  │ 🔵 GET /api/manga/{slug}/chapters                           │ │ │
│  │  │     → Chapter list                                          │ │ │
│  │  │ 🔵 GET /api/manga/{slug}/chapter?volume=..&number=..        │ │ │
│  │  │     → Chapter text (ProseMirror JSON)                       │ │ │
│  │  └─────────────────────────────────────────────────────────────┘ │ │
│  │                                                                    │ │
│  │  [Generate Plugin] [Edit Manually]                                 │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌──── DOM Analysis ────────────────────────────────────────────────┐ │
│  │  No significant HTML structure detected.                           │ │
│  │  Site appears to be SPA (Single Page Application).                │ │
│  │  All data loaded via API (see JS Scanner tab).                    │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌──── JS Scanner ──────────────────────────────────────────────────┐ │
│  │  JS Files Analyzed: 8                                             │ │
│  │  ────────────────────────────                                     │ │
│  │  /js/app.js:                                                      │ │
│  │    ✅ Config: window.__APP_CONFIG__                               │ │
│  │       ├─ apiBase = "https://api.cdnlibs.org/api/manga/"          │ │
│  │       └─ siteId = "3"                                            │ │
│  │    ✅ Headers: {"Site-Id": "3"}                                   │ │
│  │    ✅ fetch() calls detected                                      │ │
│  │                                                                  │ │
│  │  /js/utils.js:                                                    │ │
│  │    ✅ URL patterns: /api/manga/{slug}/chapters                   │ │
│  │    ✅ URL patterns: /api/manga/{slug}/chapter?volume=..           │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Plugin Wizard (Шаг 3 — Анализ каталога)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  New Plugin Wizard                                                       │
│  [1. Info] [2. URLs] [3. Catalog] [4. Novel] [5. Chapter] [6. Generate] │
│                                                                          │
│  Step 3: Catalog Page Analysis                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Catalog URL: https://site.com/novels?page=1                     │   │
│  │                                                                  │   │
│  │  🔍 Found 24 repeating items with structure:                     │   │
│  │                                                                  │   │
│  │  ┌─ Detected Container ───────────────────────────────────────┐ │   │
│  │  │  div.page-item-detail (24 matches)                         │ │   │
│  │  │                                                             │ │   │
│  │  │  Inside each item:                                         │ │   │
│  │  │    📌 Title:    h3.post-title a          → "Novel Title"   │ │   │
│  │  │    🔗 URL:      same as title href        → /novel/title/  │ │   │
│  │  │    🖼 Cover:    img.c-image-hover[data-src]→ https://...   │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │                                                                  │   │
│  │  ├─ Detected Pagination: Next page link exists                  │   │
│  │  │  └─ hasNext = true (если есть элементы)                       │   │
│  │                                                                  │   │
│  │  [✅ Confirm All]  [✏ Edit]  [🔄 Rescan]  [🔍 Show DOM Tree]   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  CPU [Back]                                    [Next: Novel Page →]    │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Function Runner

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Function Runner                    Plugin: [royal_road ▼]              │
├──────────────────────────────────────────────────────────────────────────┤
│  Function: [getCatalogList   ▼]   Parameters: index=[0]  [Run] [Stop] │
├──────────────────────────────────────────────────────────────────────────┤
│  Result  │ Raw Data │ Network │ Error                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ Response<PagedList<BookResult>>                                  │   │
│  │ ├─ list: [3 items]                                               │   │
│  │ │  ├─ [0] BookResult                                            │   │
│  │ │  │  ├─ title: "The Primal Hunter"                             │   │
│  │ │  │  ├─ url: "https://www.royalroad.com/fiction/1"            │   │
│  │ │  │  └─ coverImageUrl: "https://..."                           │   │
│  │ │  ├─ [1] BookResult                                            │   │
│  │ │  │  ├─ title: "Defiance of the Fall"                          │   │
│  │ │  │  ├─ url: "https://www.royalroad.com/fiction/2"            │   │
│  │ │  │  └─ coverImageUrl: "https://..."                           │   │
│  │ │  └─ [2] BookResult                                            │   │
│  │ │     ├─ title: "Mother of Learning"                            │   │
│  │ │     └─ ...                                                   │   │
│  │ └─ isLastPage: false                                             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────────────────────────┤
│  Console:                                                                │
│  [12:00:01] HTTP  GET https://www.royalroad.com/fictions/best-rated     │
│  [12:00:01] HTTP  → 200 OK (342ms, 45.2 KB)                            │
│  [12:00:01] Lua   getCatalogList returned 25 items                      │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Plugin Repair (Diff View)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Plugin Repair: novelfull.lua                                           │
├──────────────────────────────────────────────────────────────────────────┤
│  ┌─ Plugin Health Report ───────────────────────────────────────────┐   │
│  │  Function           Status    Old Selector     New Selector      │   │
│  │  ─────────────────────────────────────────────────────────────   │   │
│  │  getCatalogList     ✅ OK     .col-truyen-main .row              │   │
│  │  getCatalogSearch   ✅ OK     .col-truyen-main .row              │   │
│  │  getBookTitle       ❌ BROKEN h3.title          h1.entry-title   │   │
│  │  getBookCoverUrl    ✅ OK     .book img[src]                      │   │
│  │  getBookDesc        ❌ BROKEN .desc-text        .summary-content │   │
│  │  getChapterList     ✅ OK     ul.list-chapter li a               │   │
│  │  getChapterText     ❌ BROKEN #chapter-content  .entry-content   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Selected: getBookTitle                                                  │
│  ┌── Diff ──────────────────────────────────────────────────────────┐   │
│  │  function getBookTitle(bookUrl)                                   │   │
│  │      local r = http_get(bookUrl)                                  │   │
│  │      if not r.success then return nil end                         │   │
│  │  -   local el = html_select_first(r.body, "h3.title")             │   │
│  │  +   local el = html_select_first(r.body, "h1.entry-title")      │   │
│  │      return el and string_clean(el.text) or nil                   │   │
│  │  end                                                              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  [Apply This Fix] [Apply All Fixes] [Skip] [View on Site]              │
└──────────────────────────────────────────────────────────────────────────┘
```

## Экран: Code Editor (Phase 4)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Code Editor: royal_road.lua                          [Save] [Test Run]│
├──────────────────────────────────┬───────────────────────────────────────┤
│  ┌─ Editor ────────────────────┐ │  ┌─ Live Preview ─────────────────┐ │
│  │ id = "royal_road"          │ │  │  getCatalogList(0) Result:     │ │
│  │ name = "Royal Road"        │ │  │  ┌───────────────────────────┐ │ │
│  │ version = "1.0.2"          │ │  │  │ 25 items loaded          │ │ │
│  │ baseUrl = "https://..."    │ │  │  │ The Primal Hunter        │ │ │
│  │                            │ │  │  │ Defiance of the Fall    │ │ │
│  │ function getCatalogList(i) │ │  │  │ ...                      │ │ │
│  │   local url = baseUrl ..   │ │  │  └───────────────────────────┘ │ │
│  │     "/fictions/best-rated" │ │  ├───────────────────────────────┤ │
│  │   local r = http_get(url) │ │  │  Network: 2 requests, 342ms  │ │
│  │   ...                     │ │  │  ✓ GET best-rated 200 150ms │ │
│  │ end                       │ │  │  ✓ GET best-rated 200 192ms │ │
│  └───────────────────────────┘ │  └───────────────────────────────┘ │
│                                 │                                     │
│  [Insert Selector ▼] [Insert    │  [Auto-Refresh: ✅]                │
│   Template ▼]                  │                                     │
└──────────────────────────────────┴───────────────────────────────────────┘
```
