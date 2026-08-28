# XDMovies — CloudStream extension (clean-room rebuild)

CloudStream provider for **XDMovies** ([top.xdmovies.wtf](https://top.xdmovies.wtf)) — HD
movies & TV series (multi-audio: Hindi/English/Tamil/Telugu/…).

This is a **clean-room Kotlin rebuild** of the published `XDMovies.cs3` (v12 by
**phisher98** — all credit to them for the original reverse-engineering). The behaviour
was recovered by decompiling that .cs3 and re-verified against the live site; see
`ANALYSIS.md` for the full write-up.

## What it does

- **Main page** — 13 rows: Movies, TV Series, Netflix, Prime Video, Disney+, Apple TV+,
  HBO Max, Hulu, Hotstar + Action / Comedy / Thriller / Sci-Fi genres (all paginate).
- **Search** — the site's JSON API `GET /php/search_api.php?query=…` (token is embedded in
  every page as `window.AUTH_TOKEN` and auto-refreshed at runtime).
- **Detail** — movies: all `div.download-item a` links; series: `div.season-section` →
  `.episode-card` (numbering parsed from `.episode-title` `SxxEyy`) plus
  `.packs-grid .pack-card` season packs appended as extra entries.
- **Links** — `link.xdmovies.wtf` shortener resolved by following the redirect chain
  (`/go/`, `/r/`, `Location`) to the file host; a bundled **HubCloud extractor**
  (`hubcloud.cx` / `hubcloud.foo`, domain auto-resolved) turns server entries
  (BuzzServer, FSL/FSLv2, Mega, S3, Pixeldrain, 10Gbps) into direct playable
  mp4/m3u8 links with quality + codec tags; anything else delegates to CloudStream's
  built-in extractors.

## ⚠️ Cloudflare caveat (important)

Detail pages (`/movies/…`, `/series/…`) are behind a Cloudflare **interactive challenge**
on suspicious networks (datacenter IPs, some VPNs). List and search pages are open.

- On a normal residential/mobile network the provider works as-is.
- On blocked networks `load()` fails with a clear message instead of garbage.
- The original .cs3 carries an interactive WebView + Turnstile session bypass
  (`/api/session`) for those cases; that machinery is **not** part of this rebuild
  (it can't be ported headlessly with confidence). If you need it, keep the original
  .cs3 installed.

## Install

Either install the prebuilt `release/XDMovies.cs3` directly in CloudStream, or add a repo
served from your fork's `builds` branch (update `repo.json` accordingly):

```
https://raw.githubusercontent.com/kgicao29-ux/XDMovies/master/repo.json
```

## Build

Requires JDK 17 + Android SDK (platform 35):

```
./gradlew make            # -> XDMovies/build/XDMovies.cs3
./gradlew makePluginsJson # -> build/plugins.json
```

Pushing to `master` triggers the GitHub Actions workflow that publishes the artifacts to
the `builds` branch.

## Credits

- **phisher98** — original XDMovies CloudStream extension (the .cs3 that was
  decompiled & re-implemented here) and the `TVVVV` domain list.
- Structure follows the [recloudstream](https://github.com/recloudstream) plugin template.
