# Building Sofadrop

Notes for building and publishing the app yourself. To just use it, see the [README](README.md).

## Requirements

Android SDK (API 36) and JDK 17+ (the one bundled with Android Studio works).

```bash
./gradlew :app:assembleDebug
```

## Keys in `local.properties` (never commit it)

For personal builds you can bake your own keys into the APK:

```properties
tmdb.token=YOUR_TMDB_KEY_OR_TOKEN
mdblist.key=YOUR_MDBLIST_KEY           # optional
```

Personal keys (`tmdb.token`, `mdblist.key`) are only included in **debug** builds; release builds never contain them.

### App credentials (for builds you publish)

Users never type keys: a published build ships with the *app's* own credentials, which identify the app, not a user:

| Property | Where to get it | |
|---|---|---|
| `tmdb.appKey` | themoviedb.org → Settings → API | Built-in TMDB key. TMDB limits requests roughly per client IP (~40 req/s), so each TV counts on its own. |
| `trakt.clientId` (+ `trakt.clientSecret` only for apps created before Oct 2026) | [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications/new), redirect URI `https://github.com/rfmarcos/projectivy-plugin-sofadrop` (never opened: the login uses the device code flow, but the token refresh must send the registered URI) | Trakt device login (QR). New Trakt apps get no secret: the device login uses PKCE. |
| `simkl.clientId` | [simkl.com/settings/developer](https://simkl.com/settings/developer/), an **AUTH V2** app for devices (not *Server apps & services*: no secret is shipped) | Simkl device login (QR). Trending lists work without it. |
| `mdblist.clientId` | [mdblist.com/developer](https://mdblist.com/developer/), *Device Code App* (no secret, no redirect URI) | MDBList device login (QR). |
| `support.url` / `source.url` | e.g. your Ko-fi page and the GitHub repository | Shown as QR codes in **About** (hidden when empty). `source.url` also enables in-app updates from that repository's GitHub releases. |

## Signed release

```properties
release.storeFile=C:/path/to/keystore.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

```bash
./gradlew :app:assembleRelease
```

Release builds are minified with R8 (full mode): smoke-test them on a device or emulator before publishing (`app/proguard-rules.pro` keeps the classes WorkManager creates by reflection).

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Instrumented tests (`connectedDebugAndroidTest`) uninstall the app when they finish, so point them at an emulator, e.g. `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.

## How refreshing works

* A periodic **WorkManager** job regenerates the set every *Update every* hours. The app does not need to stay open: Android wakes it up when the job is due, and the job survives reboots. Only *Force stop* cancels it until the plugin is opened again.
* Projectivy asks the plugin for its list every 10 minutes (`itemsCacheDurationMillis`; a cheap call that returns the files already generated). That is when a new set shows up; if the set is older than the interval, the plugin regenerates it right away as a safety net.
* After each generation the plugin broadcasts `WALLPAPER_PROVIDER_UPDATED`. Tested with Projectivy 4.72 on a Chromecast with Google TV, this does not trigger an immediate reload, so new wallpapers appear at Projectivy's next request.
* Wallpapers are served read-only by `WallpaperContentProvider` (no per-URI grants, which Android drops when the app is updated), and the set Projectivy was last given is kept until it asks again.

## Project structure

```
api/                     # Projectivy plugin API (AIDL), unchanged from the official template
app/src/main/java/com/rfmarcos/sofadrop/
  WallpaperProviderService.kt  # Service called by Projectivy
  WallpaperContentProvider.kt  # Read-only access to the generated images
  GenerateWallpapersWorker.kt  # Background generation + scheduling
  TmdbClient.kt / TmdbCatalog.kt  # TMDB API, TMDB lists and wallpaper data
  TraktClient.kt / SimklClient.kt / MdbListClient.kt / Sources.kt  # optional list providers
  WallpaperFactory.kt          # download + render pipeline (generation and preview)
  WallpaperRenderer.kt         # Canvas rendering of the wallpaper
  WallpaperStore.kt            # Generated sets and their index
  Updater.kt                   # In-app updates from the GitHub releases
  SettingsFragment.kt (main) / ListsFragment.kt, ProviderListsFragment.kt, MdbListFragment.kt,
  ListExplorerFragment.kt (lists and filters) / DetailsFragment.kt / AboutFragment.kt / PreviewActivity.kt
```
