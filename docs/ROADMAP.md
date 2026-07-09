# План реализации

## Фаза 1: Core Engine

**Цель:** Запустить LuaEngine на десктопе, загружать плагины и выполнять функции.

### Задачи:

- [ ] 1.1 Создать Gradle multi-module проект Compose Desktop
- [ ] 1.2 Портировать из `scraper` модуля:
  - `SourceInterface.kt` (убрать Android-зависимости)
  - `LuaEngine.kt` (убрать Context, Base64 → java.util)
  - `LuaSourceAdapter.kt` (убрать Context)
  - `LuaFilterSupport.kt` (без изменений)
  - `LuaSettingsSupport.kt` (без изменений)
  - `TextExtractor.kt` (без изменений)
  - Модели: `BookResult`, `ChapterResult`, `Response`, `PagedList`
- [ ] 1.3 Портировать OkHttp NetworkClient (без Android-специфики)
- [ ] 1.4 Реализовать Plugin Browser:
  - Открыть папку `external-sources/`
  - Загрузить все `.lua` файлы через LuaEngine
  - Отобразить метаданные и статус функций
- [ ] 1.5 Реализовать Function Runner:
  - Выбор плагина и функции
  - Поля для параметров
  - Запуск и отображение результата (Tree + Raw)
- [ ] 1.6 Network Inspector:
  - Перехват HTTP запросов LuaEngine
  - Timeline с длительностью
  - Просмотр request/response

**Результат:** Можно открыть любой `.lua` плагин, выполнить любую его функцию, увидеть результат и HTTP запросы.

---

## Фаза 2: Site Analyzer + JS Scanner

**Цель:** Анализировать сайты: находить CSS-селекторы, API в JS, определять CMS.

### Задачи:

- [ ] 2.1 DOM Analyzer:
  - Построить дерево DOM из HTML (через Jsoup)
  - Найти повторяющиеся блоки (одинаковые CSS-пути)
  - Определить заголовки, ссылки, изображения
  - Найти формы поиска (method, action, поля)
  - Найти пагинацию (next, page numbers)
  - Найти embedded JSON (JSON-LD, __NEXT_DATA__)
- [ ] 2.2 CMS Detection:
  - WordPress: `wp-content`, `wp-json`, `admin-ajax.php`
  - Madara theme: `.c-tabs-item__content`, `.post-title`, `.reading-content`
  - NovelBin clone: `.col-truyen-main .row`, `.list-chapter`, `#chapter-content`
  - ScribbleHub: `.search_main_box`, `.fic_title`, `.wi_fic_desc`
  - RoyalRoad: `.fiction-list-item`, `.chapter-content`
  - Custom/API-based: по косвенным признакам
- [ ] 2.3 JS Scanner:
  - Скачать все `<script src="...">` файлы
  - Применить regex-паттерны для поиска API
  - Найти fetch/XHR/axios вызовы
  - Найти config variables (apiBase, ajaxurl, siteId)
  - Найти WordPress AJAX actions
  - Найти кастомные encode/decode функции
- [ ] 2.4 Pattern Database:
  - Создать структуру данных для хранения паттернов
  - Заполнить из 31 существующего плагина
  - Реализовать Pattern Matcher (сравнение DOM → паттерн)
- [ ] 2.5 Site Explorer UI:
  - Поле ввода URL
  - Вкладки: DOM Analysis, JS Scanner, Summary
  - Рекомендации по подходу (HTML/API/AJAX)

**Результат:** По URL сайта определяет CMS, структуру, находит API в JS, рекомендует селекторы.

---

## Фаза 3: Plugin Wizard + Repair

**Цель:** Полу-автоматическое создание и починка плагинов.

### Задачи:

- [ ] 3.1 Plugin Wizard (6 шагов):
  - Шаг 1: Базовая информация (id, name, baseUrl)
  - Шаг 2: URL страниц (каталог, поиск, новелла, глава)
  - Шаг 3: Анализ каталога (подтверждение селекторов)
  - Шаг 4: Анализ новеллы (заголовок, обложка, описание, главы)
  - Шаг 5: Анализ текста главы (контент, очистка)
  - Шаг 6: Генерация и тест
- [ ] 3.2 Code Generator:
  - Шаблоны для каждого типа (HTML/API/AJAX)
  - Подстановка селекторов в шаблоны
  - Генерация вспомогательных функций (absUrl, applyStandardContentTransforms, fetchPage)
- [ ] 3.3 Plugin Repair:
  - Загрузка существующего `.lua`
  - Тест каждой функции
  - Для упавших: перезапуск Site Analyzer, сравнение, предложение фиксов
  - Diff view старого → нового кода
- [ ] 3.4 Batch Validator:
  - Прогнать все 31 плагин
  - Отчёт по каждому: pass/fail, время выполнения, ошибки

**Результат:** Можно создать новый плагин за 5 минут через UI. Можно починить сломанный плагин автоматически.

---

## Фаза 4: IDE + Polish

**Цель:** Полноценная среда разработки плагинов.

### Задачи:

- [ ] 4.1 Code Editor:
  - Подсветка синтаксиса Lua
  - Быстрые кнопки вставки селекторов из Site Analyzer
  - Авто-форматирование
- [ ] 4.2 Live Preview:
  - Боковая панель с результатом выполнения при редактировании
  - Авто-перезапуск функции при изменении кода
- [ ] 4.3 Network Proxy Mode (JCEF):
  - Встроенный Chromium браузер
  - Перехват всех XHR/fetch запросов
  - Показ API вызовов в реальном времени
  - Возможность кликнуть на элемент для получения селектора
- [ ] 4.4 In-App Documentation:
  - Встроенная версия `lua-plugin-guide.md`
  - Интерактивный справочник API функций
  - Примеры из существующих плагинов
- [ ] 4.5 Export & Pack:
  - Экспорт плагина в YAML index
  - Проверка синтаксиса перед экспортом
  - Копирование в буфер обмена / сохранение файла

**Результат:** Полноценная IDE для разработки Lua-плагинов.

---

## Оценка времени

| Фаза | Примерное время | Зависимости |
|------|---------------|-------------|
| Фаза 1: Core Engine | 2-3 дня | Нет |
| Фаза 2: Site Analyzer | 3-4 дня | Фаза 1 |
| Фаза 3: Wizard + Repair | 4-5 дней | Фаза 2 |
| Фаза 4: IDE + Polish | 3-4 дня | Фаза 3 |
| **Итого** | **12-16 дней** | |
