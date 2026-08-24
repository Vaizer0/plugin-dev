# Plugin Dev Studio

**Desktop + Web GUI for creating, debugging, testing and repairing Lua plugins for Novela (NoveLA).**

Plugin Dev Studio analyzes the structure of novel-source websites, discovers CSS selectors and
API endpoints (including those hidden in JavaScript), and semi-automatically generates working,
NoveLA-compatible Lua plugins — validated live before you ever touch a code editor.

---

## Requirements

- **Java JDK 17+** (JDK 21 tested; on Android use [Termux](https://f-droid.org/en/packages/com.termux/) `pkg install openjdk-21`)
- A Chromium-based browser (Chrome) for the Web UI — same device or LAN
- ~500 MB free disk for Gradle caches; internet access for first build
- Optional: an existing NoveLA sources folder (`external-sources/<lang>/*.lua` + `index.yaml`)

## Installation

```bash
git clone https://github.com/Vaizer0/plugin-dev.git
cd plugin-dev
```

No manual build is required — `plugindevstart` builds on first run.

## Localhost setup

The repo ships two commands (symlinked into `$PREFIX/bin` automatically on Termux install):

```bash
plugindevstart   # build if needed, start, wait until healthy → http://localhost:8080/
plugindevstop    # stop cleanly, verify port freed, no orphan processes
```

Manual equivalent:

```bash
./gradlew :web:installDist --no-daemon
setsid nohup ./web/build/install/web/bin/web >/dev/null 2>&1 &
```

Open **http://localhost:8080/** in Chrome. From another device on the same network use
`http://<device-ip>:8080/`.

Environment overrides: `PORT` (default 8080), `PLUGIN_SOURCES_DIR` (default resolution mirrors the
desktop app: `../external-sources`, `./external-sources`, `../sources`, `./sources`).

---

## The Web UI

A faithful replica of the Compose Desktop GUI:

| Section | What it does |
|---|---|
| **Library → Plugins** | Load any plugin folder, inspect metadata/function support, run functions |
| **Tools → Quick Test** | Full pipeline: catalog / search / filters → book details → chapter text, with live HTTP log |
| **Tools → Run** | Function Runner with parameter hints and selector hints |
| **Analysis → Analyze** | Site Analyzer: CMS detection, DOM containers, forms, pagination, JS findings, endpoint suggestions |
| **Analysis → Patterns** | Pattern database: cluster matching against 31 known plugin families |
| **Network → Network Logs** | Every HTTP request made by every loaded plugin, with headers/bodies |
| **Create → New Plugin** | **Analyze & Generate Plugin** (below) |

## Analyze & Generate Plugin (Create → New Plugin)

Fully automatic pipeline:

1. **Crawl** — home page → search-results discovery (form or common paths) → one book page → one chapter page.
2. **API probing** — endpoint suggestions from JS scanning are fetched live; JSON list APIs that
   answer with title/url fields are classified. **Validated APIs always beat HTML scraping.**
3. **Strategy selection** — merges live API evidence, SelectorAnalyzer candidates (confidence × match count),
   repeating-container field roles, and PatternRegistry cluster templates (Madara, NovelBin, ScribbleHub…).
4. **Generation** — up to 3 strategy variants are emitted as complete NoveLA-convention Lua
   (helpers, catalog/search/details/genres/chapters/chapter-text, literal selectors so Studio shows hints).
5. **Live validation** — each variant loads through the real Lua engine and runs against the target site:

```
✓ getCatalogList        — 6 items
✓ getBookTitle          — Demo Novel One
✓ getBookCoverImageUrl  — https://…/cover.png
✓ getBookDescription    — 210 chars
✓ getBookGenres         — 3 genres
✓ getChapterList        — 8 chapters
✓ getChapterText        — 1801 chars extracted
✓ getCatalogSearch      — 6 results
```

A plugin is only offered as success when catalog + chapters + content all pass live.
6. **Save** — staged under `generated/<lang>/<id>.lua`; “Save to library & reload” copies it into your
   NoveLA sources folder, appends the `index.yaml` entry, and reloads the Plugins list.

### Deterministic self-test

The service embeds a demo novel site at `/demo/*`. Generating from
`http://localhost:8080/demo/catalog` exercises the entire pipeline offline and must yield a
9/9 validation matrix.

## Protected sites (Cloudflare) — browser-assisted workflow

Some sites serve bot-challenges to server-side clients. No bypasses are used: you lend your own
authenticated browser session.

In **New Plugin → “Protected site?”** copy the two bookmarklets:

1. Create bookmarks named e.g. `PDS Recorder` / `PDS Send`, pasting each code as the URL.
2. Open the protected site in this browser and complete verification once.
3. Tap **PDS Recorder** — it arms capture of `fetch`/XHR on the page.
4. Visit the pages a plugin needs: catalog, search results, one book, one chapter.
5. After each page tap **PDS Send** — the rendered DOM plus captured runtime requests are POSTed to
   the studio (`/api/capture/page`). Re-arm the recorder after full page navigations.
6. Back in New Plugin: tick **“Use captured pages”** and run **Analyze & Generate**.

The generator then uses your captured pages as the corpus and treats captured runtime API requests
as highest-priority evidence. Because live re-validation may still receive degraded pages,
capture-flow results can be reported as *partially validated* (catalog+search verified live;
selectors verified against your real captured pages).


## Analyze & Generate Plugin (Create → New Plugin)

Three-stage architecture:

```
Deterministic Site Analyzer   →   Structured Evidence   →   AI Lua Generation
        ↓
Real Lua Validation (live)    →   AI Repair (≤3 rounds) →   Validated NoveLA Plugin
```

1. **Analyze** — the built-in analyzer crawls representative pages (home, search,
   book details, chapter), probes JS-discovered endpoints live, and distills a
   compact structured evidence package (containers, selector candidates, forms,
   pagination, JSON APIs — no raw HTML dumped anywhere).
2. **AI generation** — an OpenAI-compatible model writes the complete Lua source,
   grounded in the evidence plus the official NoveLA plugin guide
   (`external-sources/lua-plugin-guide-en.md`). Models are instructed to never
   invent endpoints/selectors.
3. **Live validation & repair** — the generated plugin loads through the real Lua
   engine and every function runs against the target site. Failures are fed back
   to the model for repair (max 3 attempts). A plugin counts as success only when
   catalog/chapters/content actually work.
4. **💬 Interactive repair chat** — after generation, chat directly with the AI
   about the plugin ("chapter list uses the wrong container", "search must POST").
   It sees the full evidence (including real chapter URL samples per candidate
   selector), the current Lua and live validation results; any corrected Lua it
   returns is automatically re-validated live. You can also feed extra pages into
   the evidence on demand.
5. **Save/export** — stage locally, then "Save to library" copies it into your
   NoveLA sources folder, updates `index.yaml`, and reloads the Plugins list.

Measured: libread.com end-to-end in ~150s (attempt 1: 7/9 → repair → attempt 2: 8/9 PASS).

## AI providers

Default provider is **OpenCode Zen** (`https://opencode.ai/zen/v1`, OpenAI-compatible):

- `GET /models` works without a key; some `*-free` models also serve completions
  keylessly (e.g. `hy3-free`). Availability rotates — check the Zen catalog.
- For guaranteed access create a free API key at opencode.ai and paste it in
  **⚙ AI Settings**.

**Custom providers**: any OpenAI-compatible endpoint works (OpenRouter, DeepSeek,
Mistral, local Ollama…). In ⚙ AI Settings add: ID, base URL, API key(s), mode
(Chat Completions / Responses), default model, max tokens, optional extra headers.

- Multiple keys per provider (comma/newline separated) rotate automatically on 401/429.
- Keys are stored locally in `.pds-ai.json` (gitignored, chmod 600), never logged,
  never included in generated plugins.
- **Test** button runs a tiny live completion so config mistakes surface instantly.

## Browser-assisted capture (protected sites)

## Generated-plugin testing checklist

After saving, the plugin appears in **Plugins**. Verify in order:

1. **Info tab** — expected ✓ marks per function.
2. **Quick Test** — Load catalog → open a book → open a chapter (this exercises everything).
3. **Run tab** — spot-check `getCatalogList(0)`, `getCatalogSearch(0,"<term>")`.
4. **Patterns tab** — confirm the CMS cluster matches expectations.
5. Optionally export `<id>.lua` into your real NoveLA sources and test inside Novela itself.

## Project layout

```
plugin-dev-studio/
├── app/            # Compose Desktop GUI
├── core-engine/    # LuaEngine (LuaJ), adapters, PluginLoader, FunctionRunner
├── site-analyzer/  # DomAnalyzer, JsScanner, SelectorAnalyzer, SiteAnalyzer
├── pattern-db/     # CMS cluster registry + plugin pattern analyzer
├── plugin-gen/     # Analyze & Generate: crawler, API prober, strategy picker, Lua emitter, validator
├── web/            # Ktor localhost server + browser UI replica
├── bin/            # plugindevstart / plugindevstop
├── generated/      # staging area for generated plugins
└── docs/
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `port already answers … leaving it alone` | Something else uses 8080: stop it or set `PLUGINDEV_PORT`/`PORT`. |
| `Failed to download page — blocked by bot protection` | Normal for Cloudflare sites → use the browser-assisted workflow above. |
| Validation fails but site works in Chrome | Site serves bot-variants to servers; use capture flow. |
| First start slow | Gradle downloads dependencies once (~2–5 min). |
| Build fails on Termux out-of-memory | Ensure ≥2 GB RAM free; close other apps; retry `sh gradlew :web:installDist --no-daemon`. |
| Orphan process after crash | `plugindevstop` sweeps strays; verify with `pgrep -f MainKt`. |
| AI error 401 AuthError | Provider needs a key — ⚙ AI Settings → paste it (Zen keys: opencode.ai). |
| AI error 429 FreeUsageLimitError | Keyless quota exhausted for that model; wait, switch model in Settings, or add a key. |
| "chapter list rendered client-side" | SPA site: server HTML has no chapters → use capture flow. |
| Generation slow (>2 min) | Free reasoning models can be slow; pick a faster model in Settings. |
| Generated plugin calls undefined helper / wrong function names | AI gets an explicit function-name contract + automatic helper shims (`absUrl` etc.), and every attempt is live-validated — check the matrix and use 💬 chat to correct. |
| Chat says "chapter list wrong" fixed but still 0 | Make sure you analyzed the right book page — use ➕ Analyze extra page in the chat panel. |

## Roadmap

See `docs/ROADMAP.md`. Phase status: core engine ✅ · analyzers ✅ · pattern DB ✅ · desktop GUI ✅ ·
localhost web UI ✅ · Analyze & Generate ✅ · browser-assisted capture ✅.
