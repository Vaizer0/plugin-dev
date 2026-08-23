id = "web_test"
name = "Web Test Source"
version = "1.0.2"
baseUrl = "http://localhost:8080/demo"
language = "en"
description = "Demo plugin for testing the web studio pipeline"

function getCatalogList(index)
  return {
    items = {
      { title = "Test Book One", url = baseUrl .. "/book/1", cover = baseUrl .. "/cover1.jpg" },
      { title = "Test Book Two", url = baseUrl .. "/book/2", cover = "" }
    },
    hasNext = false
  }
end

function getCatalogSearch(index, input)
  return {
    items = {
      { title = "Search result: " .. input, url = baseUrl .. "/book/s1", cover = "" }
    },
    hasNext = false
  }
end

function getFilterList()
  return {
    { type = "select", key = "genre", label = "Genre", defaultValue = "all",
      options = { { value = "all", label = "All" }, { value = "fantasy", label = "Fantasy" }, { value = "scifi", label = "Sci-Fi" } } },
    { type = "select", key = "status", label = "Status", defaultValue = "any",
      options = { { value = "any", label = "Any" }, { value = "ongoing", label = "Ongoing" }, { value = "completed", label = "Completed" } } }
  }
end

function getCatalogFiltered(index, filters)
  local g = type(filters) == "table" and (filters.genre or "all") or "all"
  local s = type(filters) == "table" and (filters.status or "any") or "any"
  return {
    items = {
      { title = "Filtered book (" .. tostring(g) .. "/" .. tostring(s) .. ")", url = baseUrl .. "/book/f1", cover = "" }
    },
    hasNext = false
  }
end

function getBookTitle(bookUrl)
  return "Test Book One"
end

function getBookCoverImageUrl(bookUrl)
  return baseUrl .. "/cover1.jpg"
end

function getBookDescription(bookUrl)
  return "A test description used to verify the Quick Test pipeline renders book details correctly."
end

function getBookGenres(bookUrl)
  return { "Fantasy", "Adventure" }
end

function getChapterList(bookUrl)
  return {
    { title = "Chapter 1: The Beginning", url = baseUrl .. "/chapter/1" },
    { title = "Chapter 2: The Middle", url = baseUrl .. "/chapter/2" },
    { title = "Chapter 3: The End", url = baseUrl .. "/chapter/3" }
  }
end

function getChapterText(html, url)
  return "It was a dark and stormy night in the test suite.\n\nThe pipeline fetched this chapter text through the Lua engine, proving that the full catalog -> book -> chapter flow works end to end."
end
