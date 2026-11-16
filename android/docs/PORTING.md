# Porting notes (iOS → Android)

Tender for Android is a port of the iOS app in `../frigate_ios` (read-only reference). Follow
`../frigate_ios/docs/UX.md` for the UX rules, adapted to Material 3, and Frigate's web UI in
`../frigate` for names, flows and API use.

## Layout

```
app/src/main/java/nl/wiebenieuwenhuis/tender/
  api/        FrigateClient (OkHttp), Models (lenient JSON), ServerAddress, Format, Json helpers
  app/        AppModel (app state, singleton), ServerConnection (one server: status, config,
              stats, websocket), ServerStore (ServerConfig, Prefs, Secrets)
  ui/         AppRoot (NavHost, tab bar / rail), Routes (shared routes, ItemCache, LocalNav),
              DeepLinks, components/ (shared building blocks), theme/
  ui/<feature>/  one package per feature
```

## Conventions

- State: Compose snapshot state (`mutableStateOf`, `mutableStateMapOf`) in plain classes;
  `AppModel` and `ServerConnection` are the iOS `AppModel` / `ServerConnection`. Screens keep
  their own state with `remember`/`rememberSaveable` and load with `LaunchedEffect`.
- Navigation: type-safe Navigation Compose. Shared routes are in `ui/Routes.kt`; a feature's
  private routes and its `NavGraphBuilder.<feature>Destinations()` live in its own package.
  Navigate with `LocalNav.current.navigate(route)`. Hand objects to the next screen with
  `ItemCache.put(item)` before navigating (the screen falls back to loading by id).
- Pushed screens use `ScreenScaffold(title, showsBack = true)`; top-level sections pass
  `showsBack = false`.
- API: `FrigateClient` has the iOS client's calls. A feature adds its own calls as extension
  functions in `api/<Feature>Api.kt` (e.g. `suspend fun FrigateClient.faces()`), using
  `json()`, `list()`, `post()`, `send()`, `sendRaw()`, `data()` and the `JsonElement` helpers
  (`obj()`, `arr()`, `str()`, `dbl()`, `int()`, `bool()`, `strings()`, `["key"]`).
- Strings: English in `res/values/strings_<feature>.xml`, Dutch in
  `res/values-nl/strings_<feature>.xml`, names prefixed with the feature (`review_mark_reviewed`).
  The iOS app's Dutch is in `tools/ios-strings-nl.json` (English key → Dutch); look up with
  `tools/nl.sh "text"`. iOS `%@` → `%1$s`, `%lld` → `%1$d`; plurals with `<plurals>`.
  Outside composables use `L(R.string.x)` / `Lq(R.plurals.x, n, n)`.
- Object labels: `Format.labelName(label)` (translated), `LabelIcon`, `LabelTag`,
  `Palette.labelColor(label)`.
- Images: `RemoteImage(url)` (Coil, with the login cookie), `RefreshingImage(url, intervalMs)`
  for camera frames. Video: `rememberVideoPlayer()` + `player.load(url)` + `VideoSurface(player)`
  (ExoPlayer; plays Frigate's HLS `vod` and MP4, HEVC included).
- UX rules that bite most often:
  - every change that waits for Frigate shows a spinner in the control that started it and
    disables it (`ProgressButton`, `SmallSpinner`, `Chip(busy = true)`);
  - every delete/remove confirmation has Cancel (`ConfirmDialog`);
  - destructive menu items are red (`DestructiveMenuItem`);
  - problems show at the top of their screen only while something is wrong (`ErrorBanner`);
  - with more than one server, everything that belongs to a server says which (`ServerBadge`);
  - hide what does not apply (no face library without face recognition, admin-only actions
    only for admins: `ServerConnection.isAdmin`);
  - text in buttons and chips never wraps (`maxLines = 1`), check Dutch.
- The app is always dark, as the iOS app (`TenderTheme`, dark system bars), with dynamic colours
  where available. Use `MaterialTheme.colorScheme`; no hard-coded colours except behind video.
