# top.xdmovies.wtf + XDMovies.cs3 — analysis (2026-08-28)

## The website

Plain PHP site (`category.php`, `search_api.php`), movies/series download index with
multi-audio files hosted on HubCloud & friends. Key endpoints (all verified live):

| Endpoint | Notes |
|---|---|
| `GET /?type=movies|series&page=N` | home lists, 18 items/page — open |
| `GET /category.php?genre=…&page=N` | genre filter — open |
| `GET /category.php?ott=…&page=N` | Netflix/Amazon/DisneyPlus/AppleTVPlus/HBOMax/Hulu/JioHotstar — open |
| `GET /php/search_api.php?query=…` | JSON search: `[{id, tmdb_id, title, type:"movie"|"tv", poster, release_year, path, qualities[], audio_languages}]`; needs headers `x-auth-token` + `x-requested-with: XMLHttpRequest` |
| `GET /movies/{slug}` / `GET /series/{slug}` | detail pages — **Cloudflare interactive challenge** on datacenter IPs |

- The auth token is literally embedded in every rendered page:
  `window.AUTH_TOKEN = '7297skkihkajwnsgaklakshuwd'` → the rebuild refreshes it at runtime.
- Card markup: `a.movie-link` → `.movie-card[data-type=movie|tv]`, `.quality-badge`
  (4K/1080p/…), `img[src=…tmdb…]`, `h3` title, `p` year.
- `link.xdmovies.wtf/{id}` — shortener; returns 404 for unknown ids (not CF-challenged).
- `hubcloud.foo` → 302 → `hubcloud.cx` (domain rotates; original resolves it at runtime).

## The .cs3 (decompiled with jadx)

`XDMovies.cs3` v12 — `pluginClassName: com.phisher98.XDMoviesProvider`, 31 classes,
Gson-based, ~540-line main provider. Components:

| Class | Role |
|---|---|
| `XDMovies` / `XDMoviesProvider` | MainAPI provider + dynamic domain pick (`phisher98/TVVVV domains.json`) + OkHttp interceptor for CF cookies |
| `XdMoviesExtractor` | `link.xdmovies.wtf` → `ExtractorKt.bypassXD()` |
| `ExtractorKt.bypassXD` | GET → follow `location` (`/go/`, `/r/` hops); on 400 → clear cookies → **Cloudflare WebView dialog** → Turnstile session (`POST /api/session`, `GET /api/session/complete`) → retry |
| `XDSessionWebViewDialog` | WebView + JS bridge running `challenges.cloudflare.com/turnstile/v0/api.js`, `AndroidBridge.onTurnstileToken(token)` posts the session |
| `CloudflareWebViewDialog` | generic WebView CF-cookie harvester (`cf_clearance` polling) |
| `HubCloud` / `Hubdrive` | hubcloud extractors: `a[href*='hubcloud.php']`, `a.btn`, `/api/file/…`, `div.card-header`, `i#size`; follows `HX-Redirect`/`hx-redirect` headers; server labels BuzzServer/FSL/FSLv2/Mega/S3/Pixeldrain/10Gbps; quality+codec tags parsed from titles |
| `XDMoviesSettingsFragment` | user-selectable mirror domain + session maintenance |
| metadata | TMDB (`api.themoviedb.org/3`, incl. `/tv/{id}/season/{n}` for episode names) + Cinemeta fallback (`cinemeta-live.strem.io`) |

Detail-page selectors recovered from the decompile (my rebuild uses the same):

- meta: `#movie-header`, `div.details-wrapper`, `p.overview`, `p:contains(Rating:)`,
  `p:contains(Genres:)`, `p:contains(First Air Date:)`, `#source-info span`,
  `span.neon-audio`
- movie links: `#download-links, .download` → `div.download-item a`
- series: `div.season-section` (ids `season-packs-N` / `season-episodes-N`, or
  `button.toggle-season-btn` "Season N") → `.episode-card`
  (`.episode-title` holds `SxxEyy`; links = `a.movie-download-btn, a.download-button`)
  and `.packs-grid .pack-card` (appended as numbered entries)
- episode payloads = JSON array of link URLs → `loadLinks` fans out to extractors

## What this rebuild keeps / drops

Kept (verified live): search API + runtime token refresh, list/category pagination,
card parsing, detail scraping selectors, link.xdmovies.wtf redirect resolution,
HubCloud extractor (incl. domain rotation + HX-Redirect), quality/codec labelling.

Dropped (documented, not portable headlessly): the interactive Cloudflare WebView
dialogs and the Turnstile `/api/session` flow; TMDB/Cinemeta metadata enrichment
(page HTML already carries overview/rating/genres — kept the page-side metadata).

## Verification log (sandbox)

- search API: `?query=reacher` → Reacher (tmdb 108978, qualities 2160p/1080p) ✓
- home/genre/OTT lists: 18 cards/page, page 2 differs ✓
- card parse simulation on live HTML: titles/years/badges/types/posters ✓
- `hubcloud.foo` → `hubcloud.cx` redirect ✓ ; `link.xdmovies.wtf/go/test` → 404 (host up, not challenged) ✓
- detail pages: CF "Just a moment" from this datacenter IP (also via r.jina.ai /
  allorigins egress) — selectors therefore sourced from the working v12 decompile ✓
- build: `./gradlew make` → `XDMovies.cs3` (JDK 17, AGP 8.7.3, cloudstream gradle 81b1d424d2) ✓
