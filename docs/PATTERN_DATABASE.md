# Pattern Database

База знаний структурных паттернов, извлечённых из 31 существующего Lua-плагина для Novela.

## 1. Кластеры сайтов

### Кластер A: NovelBin Clone

**Плагины:** novelfull, allnovel, freewebnovel, novelbin, readnovelfull, novelbuddy, novelarrow

**Характерные признаки:**
- CMS: NovelBin (пиратский движок)
- HTML структура: `.col-truyen-main .row` для списков

**Catalog:**
```yaml
container: ".col-truyen-main .row"
title: "div.col-xs-7 > div > h3 > a"
url: "same as title href"
cover: "auto-generated (transformCatalogCover)"
pagination: "#items > 0"  # hasNext if any items
```

**Book Page:**
```yaml
title: "h3.title"
cover: ".book img[src]"
description: ".desc-text"
genres: ".info div h3:text=Genre: ~ a"
```

**Chapter List:**
```yaml
type: html
selector: "ul.list-chapter li a"
title: "a[title] || a.text"
url: "a.href"
pagination: "#list-chapter > ul > li.last > a"
# Если есть пагинация: http_get_batch для всех страниц
```

**Chapter Text:**
```yaml
content: "#chapter-content"
remove: ["script", ".ads"]
text_transform: "applyStandardContentTransforms"
```

**Search:**
```yaml
url_template: "search?keyword={query}"
pagination: "&page={page}"
# Та же структура что и catalog
```

---

### Кластер B: Madara (WordPress Theme)

**Плагины:** novelfire, novelhall, novelnice, wuxiaworld.site

**Характерные признаки:**
- CMS: WordPress + Madara theme
- HTML: `.c-tabs-item__content` для поиска, `.page-item-detail` для каталога

**Catalog:**
```yaml
container: ".page-item-detail"
title: ".post-title h3 a"
url: "same as title"
cover: ".c-image-hover img[data-src] || img[src]"
pagination: "#items > 0"
```

**Search:**
```yaml
url_template: "?s={query}&post_type=wp-manga"
container: ".c-tabs-item__content"
```

**Book Page:**
```yaml
title: "h1"
cover: ".summary_image img[data-src] || img[src]"
description: ".summary__content"
genres: ".genres-content a"
```

**Chapter List:**
```yaml
type: ajax
ajax_url: "{bookUrl}/ajax/chapters/"
method: POST
headers: { "X-Requested-With": "XMLHttpRequest" }
selector: "li.wp-manga-chapter a[href]"
reverse_order: true  # newest-first from API
```

**Chapter Text:**
```yaml
content: ".reading-content"
remove: ["script", ".ads", ".advertisement", ".social-share"]
```

---

### Кластер C: ScribbleHub Style

**Плагины:** scribblehub, royalroad

**Catalog (ScribbleHub):**
```yaml
container: ".search_main_box"
title: ".search_title a"
cover: ".search_img img[src]"
pagination: "pg={page}"
```

**Catalog (RoyalRoad):**
```yaml
container: ".fiction-list-item"
title: "h2 a"
cover: "img[src]"
pagination: "?page={page+1}"
```

**Chapter List (ScribbleHub):**
```yaml
type: ajax
ajax_url: "{baseUrl}wp-admin/admin-ajax.php"
method: POST
body: "action=wi_getreleases_pagination&pagenum=-1&mypostid={seriesId}"
headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest" }
selector: ".toc_w a[href]"
reverse_order: true
```

**Chapter List (RoyalRoad):**
```yaml
type: html
selector: "tr.chapter-row td:first-child a[href]"
title: "a[title] || a.text"
```

**Chapter Text:**
```yaml
scribblehub: "#chp_raw"
royalroad: ".chapter-content"
```

---

### Кластер D: API-First

**Плагины:** ranobelib, ranobehub, wuxiaworld.site (variation)

**Характерные признаки:**
- Нет характерных CSS-классов для контента
- Данные через JSON API
- В JS найдены API endpoint'ы

**Catalog (RanobeLib):**
```yaml
type: api
endpoint: "https://api.cdnlibs.org/api/manga/"
method: GET
params:
  site_id[0]: "3"
  page: "{page}"
  sort_by: "rating_score"
headers:
  "Site-Id": "3"
  "Referer": "https://ranobelib.me/"
response:
  items: "$.data[*]"
  title: "rus_name || eng_name || name"
  url: "baseUrl .. 'ru/' .. slug"
  cover: "$.cover.default"
  hasNext: "$.meta.has_next_page"
```

**Book Details (RanobeLib):**
```yaml
type: api
endpoint: "https://api.cdnlibs.org/api/manga/{slug}"
title: "$.data.names.rus || .eng || .name"
cover: "$.data.cover.default"
description: "$.data.summary || .description"
genres: "$.data.genres[*].name + $.data.tags[*].name"
```

**Chapter List (RanobeLib):**
```yaml
type: api
endpoint: "https://api.cdnlibs.org/api/manga/{slug}/chapters"
response:
  items: "$.data[*]"
  title: "Том {volume} Глава {number} {name}"
  url: "baseUrl .. 'ru/' .. slug .. '/read/v' .. volume .. '/c' .. number"
```

**Chapter Text (RanobeLib):**
```yaml
type: api
endpoint: "https://api.cdnlibs.org/api/manga/{slug}/chapter?volume={v}&number={n}"
response_format: "json"  # ProseMirror JSON → конвертация в HTML
content_path: "$.data.content"  # Дерево ProseMirror
attachments: "$.data.attachments"  # Карта id→url для изображений
```

---

### Кластер E: Russian WordPress

**Плагины:** jaomix, ifreedom, bookhamster

**Характерные признаки:**
- CMS: WordPress
- Русский язык в HTML
- Своя структура (не Madara)

**Catalog (Jaomix):**
```yaml
container: "div.block-home > div.one"
title: "div.title-home"
url: "div.img-home > a[href]"
cover: "div.img-home > a > img[src]"
has_cover_transform: "regex_replace(url, '-150x150', '')"
pagination: "?gpage={page}"
```

**Book Page:**
```yaml
title: "h1"
cover: "div.img-book > img"
description: "#desc-tab"
genres: "#info-book > p:text=Жанры:"
```

**Chapter List (Jaomix):**
```yaml
type: ajax + paginated
ajax_url: "{baseUrl}wp-admin/admin-ajax.php"
method: POST
body: "action=loadpagenavchapstt&page={page}"
selector: "div.title a[href]"
subtitle: "h2"  # внутри ссылки
reverse_both_levels: true  # и страницы, и главы внутри страницы
total_pages_detection: "select.sel-toc option || select[onchange*='loadChaptList'] option"
```

**Chapter Text:**
```yaml
content: ".entry-content"
remove: ["script", "style", ".ads", ".adblock-service", ".lazyblock", ".clear"]
```

---

### Кластер F: Chinese Sites

**Плагины:** quanben5, piaotia, novel543, shuba69, ttkan, twkan

**Характерные признаки:**
- Charset: GBK (китайская кодировка)
- Custom encoding (quanben5: кастомный base64)
- POST search с form data
- Специфическая структура

**Catalog (Quanben5):**
```yaml
container: ".pic_txt_list"
title: "h3 a"
cover: ".pic img[src]"
pagination: "category/1_{page}.html"
```

**Search (Quanben5):**
```yaml
type: jsonp + custom base64
endpoint: "?c=book&a=search.json&callback=search"
method: GET
params:
  keywords: "{encodeURI(query)}"
  b: "{customBase64(encodeURI(query))}"  # из JS функции
  t: "{timestamp}"
response_parse: "extract HTML from JSONP callback"
container: ".pic_txt_list"  # HTML внутри JSONP
```

**Search (shuba69):**
```yaml
type: post_form
endpoint: "https://www.69shuba.com/modules/article/search.php"
method: POST
body: "searchkey={url_encode_charset(query, 'GBK')}&searchtype=all"
charset: "GBK"
container: "div.newbox ul li"
title: "h3 a:last-child"
cover: "a.imgbox img[data-src]"
```

**Chapter List (Quanben5):**
```yaml
selector: "ul.list li a"
url_suffix: "/xiaoshuo.html"  # отдельная страница глав
```

**Chapter List (shuba69):**
```yaml
selector: "div#catalog ul li a"
reverse_order: true
id_extraction: "/(\\d+)\\.htm$"
```

**Chapter Text:**
```yaml
quanben5: "#content"
shuba69: "div.txtnav"  # с перезагрузкой страницы в GBK
remove: ["h1", "div.txtinfo", "script", ".ads"]
```

---

## 2. CMS Detection Probes

```yaml
wordpress:
  probes:
    - html_contains: 'wp-content/themes'
    - html_contains: 'wp-admin/admin-ajax.php'
    - html_contains: '<meta name="generator" content="WordPress'
    - script_contains: 'wpApiSettings'
  confidence: 0.9

madara_theme:
  probes:
    - html_contains: 'wp-content/themes/madara'
    - html_contains: '.c-tabs-item__content'
    - html_contains: '.reading-content'
    - html_contains: '.post-title h3'
  parent: wordpress
  confidence: 0.8

novelbin_clone:
  probes:
    - html_contains: '.col-truyen-main'
    - html_contains: 'list-chapter'
    - html_contains: '#chapter-content'
  confidence: 0.8

scribblehub:
  probes:
    - html_contains: '.search_main_box'
    - html_contains: '.fic_title'
    - html_contains: '.wi_fic_desc'
  confidence: 0.85

royalroad:
  probes:
    - html_contains: '.fiction-list-item'
    - html_contains: '.font-white'
    - html_contains: 'royalroad.com'
  confidence: 0.9

custom_api:
  probes:
    - script_contains: '/api/'
    - script_contains: 'fetch('
    - html_contains: '__NEXT_DATA__'
    - no_cms_markers: true  # отрицательный признак
  confidence: 0.6

chinese_site:
  probes:
    - meta_charset: 'GBK'
    - meta_charset: 'gb2312'
  confidence: 0.7
```

## 3. Pagination Types

| Type | Detection | Пример |
|------|-----------|--------|
| `items_count` | `hasNext = #items > 0` | RoyalRoad, NovelFull |
| `next_link` | Найден `a` с текстом "next", "›", "»" | ScribbleHub |
| `page_param` | URL содержит `?page=N` или `_N.html` | Quanben5, shuba69 |
| `ajax_pages` | Данные из AJAX с номерами страниц | Jaomix |
| `api_meta` | JSON содержит `meta.has_next_page` | RanobeLib |
| `single_page` | Все результаты на одной странице | Quanben5 search |
