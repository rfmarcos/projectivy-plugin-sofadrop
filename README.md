# Sofadrop

**Movie & TV backdrops for Projectivy Launcher.**


A wallpaper provider plugin for [Projectivy Launcher](https://github.com/spocky/miproja1) (Android TV) that generates wallpapers of **movies and TV shows directly on your TV** from the lists you choose (TMDB, plus optionally Trakt, Simkl and MDBList), and lets you configure everything from inside the launcher with a live preview.

> ⚠️ Projectivy marks third-party wallpaper plugins as a **Premium** feature (the built-in Reddit source is free).

It is the on-device version of [androidtvbackground-reddit](https://github.com/rfmarcos/androidtvbackground-reddit): same selection rules and the same design, but no server, GitHub Actions or Reddit needed.

![Example wallpaper](https://raw.githubusercontent.com/rfmarcos/androidtvbackground-reddit/main/docs/images/hero_toy_story_5_en.jpg)

## ✨ Features

* 🎬 **Lists** (pick several): TMDB trending, popular, in cinemas, upcoming, top rated and on the air; optionally Trakt trending / popular / anticipated / box office, your Trakt recommendations and watchlist, Simkl trending anime / movies / shows and your Plan to watch and Watching lists, and any public MDBList list (no account needed) or your own.
* 👀 **Live preview** in the settings and a notice when changed settings are waiting for the next update.
* 📱 **Nothing to type on the TV**: Trakt, Simkl, MDBList and your TMDB account are connected by scanning a QR code with the phone.
* ✅ **Watched content** (with Trakt and/or Simkl): mark watched titles ("✓ Watched", "▶ Watching · 3/8") or hide them.
* 🔎 **Explore lists**: see the titles of each list in order and what happens to each one (used, in reserve, filtered and why).
* 🖼️ Full-bleed backdrop (textless when available) with gradients that keep the lower half free for the launcher rows.
* 🏷️ Official logo (dark logos are inverted to stay visible) or a fitted title, ★ rating, year, runtime / seasons, genres and synopsis.
* 📺 Streaming services available in your country (TMDB Watch Providers, powered by JustWatch). Tiers and channels of the same service are grouped.
* 🎯 **Filters**: maximum age (6 months to no limit), *global hits only* (skip titles only popular in a local market unless they are worldwide hits, ≥ 1000 TMDB votes) and adult content. By default they apply only to the automatic TMDB / Trakt lists, so lists you curate are used as they are.
* 🌍 Wallpaper languages: English (US/UK), Spanish (Spain/Mexico), Portuguese (Brazil/Portugal), French, Italian and German.
* ⏱️ Refreshes in the background with WorkManager and tells Projectivy when new wallpapers are ready.

## ⚙️ Settings

Open them from **Projectivy → Settings → Appearance → Wallpaper → Configure** (with *Sofadrop* selected as the launcher wallpaper), or from the plugin icon in the apps list.

| Setting | Default | |
|---|---|---|
| Update now | — | Also shows the status: how many wallpapers, when they were updated, or the last error. |
| View wallpapers | — | The current set full screen (◀ ▶ to browse). **OK** shows that wallpaper in the settings preview (OK again goes back to automatic). |
| Lists | TMDB trending movies + TV shows | One screen per service: **TMDB**, **Trakt**, **Simkl** and **MDBList**, each with its account (QR login) and its lists as checkboxes, plus **Explore lists**. Titles repeated across lists are only used once. |
| Titles per list | 10 | 5, 10, 15 or 20 per list. |
| Order | Top of each list | *Top of each list* takes the first usable titles; *Random from the whole list* picks them from up to 100 titles, for more variety on each update. |
| Filters | 1 year · global hits · no adult | *Maximum age* 6 months, 1, 2 or 5 years or no limit (release date, or last air date for shows); *Global hits only*; *Exclude adult content*; *Watched titles*; *Apply to* trending & popular lists only or all lists. |
| Wallpaper details | device language · automatic · 1080p | *Language* of titles, synopsis, logos and labels; *Streaming services*: don't show, automatic country or a chosen country; *Synopsis* full (4 lines), short (2 lines) or hidden, so the text stays clear of the launcher rows (the streaming row moves up with it); *Resolution* 1080p (recommended) or 4K. |
| Update every | 24 hours | 6 h, 12 h, 24 h, 3 days or 7 days. |
| About | — | Version, support the project (Ko-fi QR), source code and credits. |

In **Filters → Watched titles** you choose what happens with titles you have watched on **Trakt and/or Simkl** (a title counts as watched if either says so): show normally, show with a *Watched* badge ("✓ Watched", "▶ Watching · 3/8") or hide them (shows in progress are kept and marked).

The left side of the screen renders a **live preview** with the current settings. While the settings differ from the current set, *Not applied yet* is shown under the preview: the changes are applied when you leave the settings, or right away with **Update now**. With no list selected nothing is regenerated and the current wallpapers are kept.

### Trakt (optional)

Choose **Lists → Trakt → Your Trakt account** and scan the QR code (or enter the code on [trakt.tv/activate](https://trakt.tv/activate)). Trakt's public lists (trending, popular, anticipated, box office), your recommendations and your watchlist can then be ticked, and watched titles can be marked or hidden.

> Why a Client ID? Trakt only talks to registered apps. The Client ID belongs to the *app*, not to the user, so a published build ships with it (`trakt.clientId` in `local.properties`) and users only scan the QR code. New Trakt apps get no secret: the device login uses PKCE.

### Simkl (optional)

**Lists → Simkl** offers Simkl's weekly **trending anime, movies and shows** with no account (anime ignores *Global hits only*, which would leave it almost empty). Choose **Your Simkl account** and scan the QR code to add your **Plan to watch** and **Watching** lists; your Simkl history also feeds *Watched titles*. Anime series are matched through their IMDb id, because Simkl links anime seasons to TMDB season ids.

### MDBList (optional)

Choose **Lists → MDBList**:

* **Public lists, no account needed**: tick one of the suggested lists (Netflix, HBO, IMDb Top 250…) or choose **Add a public list** and type `user/list` or the list URL (e.g. `https://mdblist.com/lists/hdlists/crazy-plot-twists`). They are read from the list's public JSON export.
* **Your account** (optional): scan the QR code to approve the app on mdblist.com and your own lists (including private ones) are loaded. Tick them like any other list, and use **Reload my lists** after creating new ones.

### App credentials (for builds you publish)

Users never type keys: the app ships with its own credentials, set in `local.properties`:

| Property | Where to get it | |
|---|---|---|
| `tmdb.appKey` | themoviedb.org → Settings → API | Built-in TMDB key. TMDB limits requests roughly per client IP (~40 req/s), so each TV counts on its own. |
| `trakt.clientId` (+ `trakt.clientSecret` only for apps created before Oct 2026) | [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications/new), redirect URI `https://github.com/rfmarcos/projectivy-plugin-sofadrop` (never opened: the login uses the device code flow, but the token refresh must send the registered URI) | Trakt device login (QR). |
| `simkl.clientId` | [simkl.com/settings/developer](https://simkl.com/settings/developer/), an **AUTH V2** app for devices (not *Server apps & services*: no secret is shipped) | Simkl device login (QR). Trending lists work without it. |
| `mdblist.clientId` | [mdblist.com/developer](https://mdblist.com/developer/), *Device Code App* (no secret, no redirect URI) | MDBList device login (QR). |
| `support.url` / `source.url` | e.g. your Ko-fi page and the GitHub repository | Shown as QR codes in **About** (hidden when empty). |

How often the wallpaper *changes on screen* is configured in Projectivy itself; the plugin decides how often the *set of wallpapers* is regenerated.

## 📥 Installation

1. Download the APK from the [releases](../../releases) (or build it, see below).
2. Install it on your Android TV (e.g. with *Downloader*, *Send Files to TV* or `adb install`).
3. In Projectivy go to **Settings → Appearance → Wallpaper → Launcher wallpaper** and pick **🔌 Sofadrop** (at the end of the list).
4. Open **Configure**, enter your TMDB key and choose **Update now**.

### 🔑 Getting a TMDB key

Create a free account at [themoviedb.org](https://www.themoviedb.org/), go to **Settings → API** and copy the **API Key** (or the API Read Access Token).

## 🛠️ Building

Requirements: Android SDK (API 36) and JDK 17+ (the one bundled with Android Studio works).

```bash
./gradlew :app:assembleDebug
```

For personal builds you can bake default keys into the APK by adding them to `local.properties` (never commit it):

```properties
tmdb.token=YOUR_TMDB_KEY_OR_TOKEN
mdblist.key=YOUR_MDBLIST_KEY           # optional
trakt.clientId=YOUR_TRAKT_CLIENT_ID     # optional
trakt.clientSecret=YOUR_TRAKT_SECRET    # optional
```

The TMDB and MDBList keys are only included in **debug** builds; release builds never contain them. The Trakt client id/secret identify the *app* (not a user) and are included in every build.

Instrumented tests (`connectedDebugAndroidTest`) uninstall the app when they finish, so point them at an emulator, e.g. `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.

To build a signed release (`./gradlew :app:assembleRelease`), point `local.properties` to your keystore:

```properties
release.storeFile=C:/path/to/keystore.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

### How refreshing works

* A periodic **WorkManager** job (Android JobScheduler) regenerates the set every *Update every* hours. The app does not need to stay open: Android wakes it up when the job is due, and the job survives reboots. Only *Force stop* cancels it until the plugin is opened again.
* Projectivy asks the plugin for its list every 30 minutes (a cheap call: the plugin only returns the files it already has). That is when a newly generated set shows up, and if the set is older than the interval (or the settings changed) the plugin regenerates it right away as a safety net.
* After each generation the plugin also broadcasts `WALLPAPER_PROVIDER_UPDATED`. Tested with Projectivy 4.72 on a Chromecast with Google TV, this does not trigger an immediate reload, so new wallpapers appear at Projectivy's next request (≤ 30 min).

### Project structure

```
api/                     # Projectivy plugin API (AIDL), unchanged from the official template
app/src/main/java/com/rfmarcos/sofadrop/
  WallpaperProviderService.kt  # Service called by Projectivy
  GenerateWallpapersWorker.kt  # Background generation + scheduling
  TmdbClient.kt / TmdbCatalog.kt  # TMDB API, TMDB lists and wallpaper data
  TraktClient.kt / SimklClient.kt / MdbListClient.kt / Sources.kt  # optional list providers
  WallpaperFactory.kt          # download + render pipeline (generation and preview)
  WallpaperRenderer.kt         # Canvas rendering of the wallpaper
  WallpaperStore.kt            # Generated files, index and content:// URIs
  SettingsFragment.kt (main) / ListsFragment.kt, ProviderListsFragment.kt, MdbListFragment.kt (lists) / DetailsFragment.kt / PreviewActivity.kt
```

## 🧾 Credits

* Based on the official [Projectivy wallpaper provider template](https://github.com/spocky/projectivy-plugin-wallpaper-provider) by spocky (Apache 2.0).
* Movie and TV data, images and logos from [TMDB](https://www.themoviedb.org/). *This product uses the TMDB API but is not endorsed or certified by TMDB.*
* Lists and watch history from [Simkl](https://simkl.com/).
* Streaming availability from TMDB Watch Providers, powered by [JustWatch](https://www.justwatch.com/).
* [Roboto](https://github.com/googlefonts/roboto) font (Apache 2.0) and Material Icons (Apache 2.0).

## 📜 License

Apache License 2.0, see [LICENSE](LICENSE).
