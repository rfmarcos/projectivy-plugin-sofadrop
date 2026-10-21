# Projectivy Plugin: TMDB Wallpapers

A wallpaper provider plugin for [Projectivy Launcher](https://github.com/spocky/miproja1) (Android TV) that generates wallpapers of **movies and TV shows directly on your TV** from the lists you choose (TMDB, plus optionally Trakt and MDBList), and lets you configure everything from inside the launcher with a live preview.

> ⚠️ Projectivy marks third-party wallpaper plugins as a **Premium** feature (the built-in Reddit source is free).

It is the on-device version of [androidtvbackground-reddit](https://github.com/rfmarcos/androidtvbackground-reddit): same selection rules and the same design, but no server, GitHub Actions or Reddit needed.

![Example wallpaper](https://raw.githubusercontent.com/rfmarcos/androidtvbackground-reddit/main/docs/images/hero_toy_story_5_en.jpg)

## ✨ Features

* 🎬 **Lists** (pick several): TMDB trending, popular, in cinemas, upcoming, top rated and on the air; optionally Trakt trending / popular / anticipated / box office, your Trakt recommendations and watchlist, and any MDBList list.
* 👀 **Live preview** in the settings and a notice when changed settings are waiting for the next update.
* 🖼️ Full-bleed backdrop (textless when available) with gradients that keep the lower half free for the launcher rows.
* 🏷️ Official logo (dark logos are inverted to stay visible) or a fitted title, ★ rating, year, runtime / seasons, genres and synopsis.
* 📺 Streaming services available in your country (TMDB Watch Providers, powered by JustWatch). Tiers and channels of the same service are grouped.
* 🎯 *Global hits only*: in TMDB trending/popular lists, titles only popular in a local market are skipped unless they are worldwide hits (≥ 1000 TMDB votes).
* 🌍 Wallpaper languages: English (US/UK), Spanish (Spain/Mexico), Portuguese (Brazil/Portugal), French, Italian and German.
* ⏱️ Refreshes in the background with WorkManager and tells Projectivy when new wallpapers are ready.

## ⚙️ Settings

Open them from **Projectivy → Settings → Appearance → Wallpaper → Configure** (with *TMDB Wallpapers* selected as the launcher wallpaper), or from the plugin icon in the apps list.

| Setting | Default | |
|---|---|---|
| TMDB API key or token | — | **Required.** A v3 *API Key* (32 characters, easier to type with a remote) or a v4 *API Read Access Token*. |
| Lists | TMDB trending movies + TV shows | One or more lists; titles repeated across lists are only used once. |
| Titles per list | 10 | 5, 10, 15 or 20 per list. |
| Language | device language | Language of titles, synopsis, logos and labels. |
| Streaming country | from language | Country used for the streaming services. |
| Update every | 24 hours | 6 h, 12 h, 24 h, 3 days or 7 days. |
| Resolution | 1080p | 1080p (recommended) or 4K. |
| Global hits only | On | In TMDB trending/popular lists, skip local-market titles unless they are worldwide hits. Lists you curate (Trakt, MDBList) are used as they are. |
| Show streaming services | On | |
| Trakt | — | Optional, see below. |
| MDBList | — | Optional, see below. |

The left side of the screen renders a **live preview** with the current settings. Changed settings are **not applied immediately**: a notice tells you they will be applied in the next scheduled update, or you can choose **Update now**. The screen also shows the status of the last update and a full-screen view of the current set (◀ ▶ to browse).

### Trakt (optional)

1. Create an app at [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications/new) with redirect URI `urn:ietf:wg:oauth:2.0:oob`.
2. In **Settings → Trakt** enter its *Client ID* and *Client Secret*: Trakt's public lists (trending, popular, anticipated, box office) become available.
3. Choose **Connect account**: the TV shows a code to enter on [trakt.tv/activate](https://trakt.tv/activate). Your recommendations and watchlist then appear in **Lists**.

### MDBList (optional)

Enter your API key (mdblist.com → Preferences → API) in **Settings → MDBList**. Your own lists are loaded automatically, and you can add public lists by typing `user/list` or the list URL.

How often the wallpaper *changes on screen* is configured in Projectivy itself; the plugin decides how often the *set of wallpapers* is regenerated.

## 📥 Installation

1. Download the APK from the [releases](../../releases) (or build it, see below).
2. Install it on your Android TV (e.g. with *Downloader*, *Send Files to TV* or `adb install`).
3. In Projectivy go to **Settings → Appearance → Wallpaper → Launcher wallpaper** and pick **🔌 TMDB Wallpapers** (at the end of the list).
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
app/src/main/java/com/rfmarcos/tmdbwallpapers/
  WallpaperProviderService.kt  # Service called by Projectivy
  GenerateWallpapersWorker.kt  # Background generation + scheduling
  TmdbClient.kt / TmdbCatalog.kt  # TMDB API, TMDB lists and wallpaper data
  TraktClient.kt / MdbListClient.kt / Sources.kt  # optional list providers
  WallpaperFactory.kt          # download + render pipeline (generation and preview)
  WallpaperRenderer.kt         # Canvas rendering of the wallpaper
  WallpaperStore.kt            # Generated files, index and content:// URIs
  SettingsFragment.kt (+ Trakt / MDBList screens) / PreviewActivity.kt
```

## 🧾 Credits

* Based on the official [Projectivy wallpaper provider template](https://github.com/spocky/projectivy-plugin-wallpaper-provider) by spocky (Apache 2.0).
* Movie and TV data, images and logos from [TMDB](https://www.themoviedb.org/). *This product uses the TMDB API but is not endorsed or certified by TMDB.*
* Streaming availability from TMDB Watch Providers, powered by [JustWatch](https://www.justwatch.com/).
* [Roboto](https://github.com/googlefonts/roboto) font (Apache 2.0) and Material Icons (Apache 2.0).

## 📜 License

Apache License 2.0, see [LICENSE](LICENSE).
