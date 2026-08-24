id = "libread"
name = "Lib Read"
version = "1.0.0"
baseUrl = "https://libread.com"
language = "en"

local _pageCache = {}
local function fetchPage(url)
    if _pageCache[url] then return _pageCache[url] end
    local r = http_get(url)
    if r.success then
        _pageCache[url] = r.body
        return r.body
    end
    return nil
end

local function absUrl(href)
    if not href or href == "" then return nil end
    return url_resolve(baseUrl, href)
end

function getCatalogList(index)
    if index > 0 then return { items = {}, hasNext = false } end
    local html = fetchPage(baseUrl)
    if not html then return { items = {}, hasNext = false } end
    local items = {}
    for _, card in ipairs(html_select(html, "div.li")) do
        local titleEl = html_select_first(card.html, "h3.tit a")
        if not titleEl then titleEl = html_select_first(card.html, "h3.tit") end
        if titleEl then
            local title = string_clean(titleEl.text)
            local url = absUrl(titleEl.href)
            local cover = html_attr(card.html, "img:nth-child(1)", "src")
            local coverUrl = cover and cover ~= "" and absUrl(cover) or nil
            if title and url then
                table.insert(items, { title = title, url = url, cover = coverUrl })
            end
        end
    end
    return { items = items, hasNext = #items > 0 }
end

function getCatalogSearch(index, query)
    if index > 0 then return { items = {}, hasNext = false } end
    local url = baseUrl .. "/?s=" .. url_encode(query)
    local html = fetchPage(url)
    if not html then return { items = {}, hasNext = false } end
    local items = {}
    for _, card in ipairs(html_select(html, "div.li")) do
        local titleEl = html_select_first(card.html, "h3.tit a")
        if not titleEl then titleEl = html_select_first(card.html, "h3.tit") end
        if titleEl then
            local title = string_clean(titleEl.text)
            local url = absUrl(titleEl.href)
            local cover = html_attr(card.html, "img:nth-child(1)", "src")
            local coverUrl = cover and cover ~= "" and absUrl(cover) or nil
            if title and url then
                table.insert(items, { title = title, url = url, cover = coverUrl })
            end
        end
    end
    return { items = items, hasNext = false }
end

function getBookTitle(bookUrl)
    local html = fetchPage(bookUrl)
    if not html then return nil end
    local el = html_select_first(html, "h1")
    return el and string_clean(el.text) or nil
end

function getBookCoverImageUrl(bookUrl)
    local html = fetchPage(bookUrl)
    if not html then return nil end
    local cover = html_attr(html, "meta[property='og:image']", "content")
    return cover and cover ~= "" and absUrl(cover) or nil
end

function getBookDescription(bookUrl)
    local html = fetchPage(bookUrl)
    if not html then return nil end
    local el = html_select_first(html, "div.desc")
    if el then return string_trim(el.text) end
    return nil
end

function getBookGenres(bookUrl)
    local html = fetchPage(bookUrl)
    if not html then return {} end
    local genres = {}
    for _, a in ipairs(html_select(html, "dl.d2 a")) do
        local g = string_clean(a.text)
        if g and g ~= "" then
            table.insert(genres, g)
        end
    end
    return genres
end

function getChapterList(bookUrl)
    local chapters = {}
    local pageUrl = bookUrl
    for i = 0, 30 do
        local html = fetchPage(pageUrl)
        if not html then break end
        local found = false
        for _, a in ipairs(html_select(html, "a.con")) do
            local title = string_clean(a.text)
            local href = a.href
            if href and title ~= "" then
                table.insert(chapters, { title = title, url = absUrl(href) })
                found = true
            end
        end
        if not found then
            for _, a in ipairs(html_select(html, "a:nth-child(2)")) do
                local title = string_clean(a.text)
                local href = a.href
                if href and title ~= "" then
                    table.insert(chapters, { title = title, url = absUrl(href) })
                    found = true
                end
            end
        end
        local nextLink = html_select_first(html, "a:contains(Next)")
        if not nextLink or i == 30 then break end
        pageUrl = bookUrl .. "/" .. (i + 2)
    end
    return chapters
end

function getChapterText(html, url)
    local cleaned = html_remove(html, "script", "style", "dl.d2", "div.reader-ad-skip", "label.lab", "form", "nav", "header", "footer", "div.item", "a.a1", "a.index-container-btn")
    local body = html_select_first(cleaned, "body")
    if body then
        return string_normalize(html_text(body.html))
    end
    return string_normalize(html_text(cleaned))
end