## Learnings


### Task 1.1 - Project Initialization (2026-02-10)
- All Kotlin DSL (.kts) files created successfully at repo root alongside existing main.py
- Kotlin 2.1.0 uses `org.jetbrains.kotlin.plugin.compose` plugin — no separate compose compiler version needed
- Mapbox Maven credentials use `providers.gradleProperty()` with `.getOrElse("")` for safe fallback
- Compose BOM libraries (material3, ui, etc.) don't need explicit version.ref — BOM manages them
- Room/Hilt compilers use `ksp()` configuration, not `kapt()`
- Theme name in themes.xml must match android:theme in AndroidManifest.xml (Theme.FBReco)
- Test directories need .gitkeep files since git doesn't track empty dirs
- Project structure: root has settings.gradle.kts + build.gradle.kts, app/ has its own build.gradle.kts

### Task: themes.xml XML parent theme fix (2026-02-10)
- Material3 themes (Theme.Material3.DayNight.NoActionBar) are NOT available as XML resources — only in Compose via Material3 Compose libraries
- For Compose apps with XML theme scaffolding, use `android:Theme.Material.Light.NoActionBar` as parent
- This provides basic Android theming without requiring AppCompat dependency
- Style name (Theme.FBReco) must match the reference in AndroidManifest.xml

### Task 1.2 - AndroidManifest & Hilt Setup (2026-02-10)
- BLE permissions: BLUETOOTH_SCAN (with neverForLocation flag), BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
- Foreground service requires both FOREGROUND_SERVICE and FOREGROUND_SERVICE_CONNECTED_DEVICE (API 34+)
- uses-feature for bluetooth_le with required="true" ensures Play Store filtering
- Hilt generates Hilt_FBRecoApplication.java with deprecation warning — this is normal/expected
- Service declaration in manifest can reference .service.BikeForegroundService even before the class exists (build still passes since it's just metadata)
- @HiltAndroidApp on Application + @AndroidEntryPoint on Activity = minimum Hilt wiring

### Task 2.1 & 2.2 - Room Data Layer (2026-02-10)
- Room entities, DAOs, type converters, and database class all created in one pass — no issues
- KSP annotation processing for Room works cleanly with Kotlin 2.1.0
- TypeConverters using ISO formatters (ISO_LOCAL_DATE, ISO_LOCAL_DATE_TIME) for java.time types
- @Upsert annotation available in Room 2.6.1 — combines insert/update semantics
- exportSchema = false on @Database avoids needing schema export directory configuration
- Kotlin LSP not installed in this environment — rely on Gradle build for validation
- DestinationDao.complete() takes completedAt as String (not LocalDateTime) since it's a raw SQL @Query
- File structure: data/entity/ for entities+converters, data/dao/ for DAOs, data/ for database class

### Task 3.1 - FtmsParser (2026-02-10)
- FTMS Indoor Bike Data (0x2AD2): flags uint16 + speed uint16 always present, then conditional fields per flag bits
- main.py reference only handles bits 2 (cadence), 5 (resistance), 6 (power) — the specific bike doesn't set bits 1, 3, 4
- Full FTMS spec has bits 1 (avg speed), 3 (avg cadence), 4 (total distance/uint24) that must be skipped when present
- Speed: uint16 × 0.01 km/h; Cadence: uint16 × 0.5 rpm; Power: sint16 × 1W
- Total Distance is uint24 (3 bytes), not uint16 — important for correct offset advancement
- Distance not sent by bike — must be computed: speedKmh × (intervalMs / 3600000.0) × 1000.0 = meters
- ByteBuffer with LITTLE_ENDIAN order handles the byte-swapping cleanly in Kotlin
- No Android SDK imports needed — pure JVM kotlin (java.nio.ByteBuffer)
- Kotlin LSP not installed; rely on gradlew assembleDebug for build verification

### Task 2.3 - Repository Layer & Hilt DI Module (2026-02-10)
- Repository classes use @Singleton + @Inject constructor for Hilt constructor injection — no @Binds/@Provides needed for repos
- DatabaseModule is object with @Module/@InstallIn(SingletonComponent::class) — standard Hilt pattern
- Room.databaseBuilder needs @ApplicationContext Context, class ref, and database name string
- DAO providers in module dont need @Singleton since database instance is already singleton
- Hilt annotation processing (hiltJavaCompileDebug) generates Hilt_FBRecoApplication.java with deprecation warning — still normal
- Build passed in 12s with 11 tasks executed, 29 up-to-date

### Task 3.2 - FtmsParser Unit Tests (2026-02-10)
- ByteArray concatenation in Kotlin: + operator returns Array<Byte> not ByteArray — need a manual concat helper with copyInto
- Spread operator (*) works on ByteArray for vararg Byte parameters
- ByteBuffer.allocate().order(LITTLE_ENDIAN).putShort().array() is clean for building test payloads
- 15 test methods covering: speed-only, cadence, power, bike format (bits 2/5/6), all flags (bits 1-6), isActive detection (3 cases), zero speed, corrupted/short data, empty array, distance calculation (3 cases)
- Test runner: ./gradlew testDebugUnitTest --tests "com.fbreco.ble.FtmsParserTest"
- Removed .gitkeep from app/src/test/java/com/fbreco/ since real test file now exists

### Task 2.4 - Repository Unit Tests (2026-02-10)
- Fake DAO approach (Option A) works perfectly for testing repository logic without Robolectric or Android framework
- Fake DAOs implement the DAO interface with in-memory maps, mimicking SQL behavior (e.g., addDistance accumulates, complete sets isActive=false)
- kotlinx.coroutines.test.runTest works with suspend functions and Flow.first() for testing coroutine-based repos
- MutableStateFlow used in fake DAOs to provide testable Flow implementations
- DailyRecordRepositoryTest: 5 tests (create new, accumulate, midnight boundary, observeToday, getRange)
- DestinationRepositoryTest: 6 tests (insert, deactivate previous, accumulate distance, auto-complete, no-op without active, flow of active)
- Total: 11 new tests, all passing — 0 failures, 0 errors
- Test file location: app/src/test/java/com/fbreco/data/ (alongside existing ble/ test directory)
- Section divider comments (// ── N. description ──) follow FtmsParserTest.kt convention

### Task 3.3 - BleManager (2026-02-10)
- BLE GATT descriptor.value and gatt.writeDescriptor() deprecated in API 33 — still needed for minSdk 26 backward compat, produces build warnings (not errors)
- onCharacteristicChanged(BluetoothGatt, BluetoothGattCharacteristic) deprecated in API 33 — new signature adds ByteArray param, but old one needed for pre-33 devices
- Two-phase BLE notification setup: (1) setCharacteristicNotification (local) + (2) writeDescriptor on CCCD (remote) — both required
- CCCD UUID 00002902 is standard Client Characteristic Configuration Descriptor
- BluetoothDevice.TRANSPORT_LE as 4th param to connectGatt avoids dual-mode ambiguity
- Exponential backoff: `INITIAL_BACKOFF * (1L shl attempts).coerceAtMost(MAX)` — use coerceAtMost(14) on shift to prevent overflow
- SharedFlow with extraBufferCapacity=64 + tryEmit for BLE notification callbacks (callback thread can't suspend)
- ScanFilter with ParcelUuid(FTMS_SERVICE_UUID) filters at OS level — more battery efficient than post-filtering
- SharedPreferences for last device address — separate prefs file "ble_prefs" to avoid conflicts
- Hilt_FBRecoApplication.java deprecation warning is normal/expected (noted in task 1.2, still present)

### Task 3.4 - BikeForegroundService (2026-02-10)
- LifecycleService + @AndroidEntryPoint works for Hilt-injected foreground services — lifecycleScope available for coroutines
- startForeground with ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE requires Build.VERSION_CODES.Q (API 29) guard
- runBlocking used in onDestroy for final data flush since lifecycleScope is already being cancelled at that point
- NotificationChannel creation is idempotent — safe to call in onCreate every time
- BLE notification interval clamping (5s max) prevents distance/time spikes after reconnect gaps
- Midnight boundary handling: compare LocalDate.now() with lastFlushDate on each flush, write to old date if changed, reset accumulators
- todayTotalTimeSeconds/todayTotalDistanceMeters loaded from repository on service start for accurate notification display
- Notification update every 1s, data flush every 30s — different cadences for UI vs persistence
- companion object isRunning: MutableStateFlow<Boolean> exposes service state to UI layer without binding
- android.R.drawable.ic_menu_directions as temporary notification icon — will need app-specific icon eventually
- Build: 42s, 11 executed, 29 up-to-date (KSP + Kotlin + Hilt recompiled for new service class)

### Task 4.1 - Navigation Structure (2026-02-10)
- Material 3 NavigationBar + NavigationBarItem (NOT deprecated BottomNavigation) for bottom nav
- dynamicColorScheme requires Build.VERSION_CODES.S (API 31) — fallback to lightColorScheme()/darkColorScheme() for minSdk 26
- NavHost from androidx.navigation.compose with composable() routes — already in deps as navigation.compose
- Screen sealed class with companion object `val all = listOf(...)` simplifies iteration in NavigationBar
- NavDestination.hierarchy for checking current route selection in NavigationBarItem
- popUpTo(findStartDestination().id) + saveState + launchSingleTop + restoreState = standard back stack behavior
- data object (Kotlin 1.9+) preferred over object for sealed class members — better toString/equals
- File structure: ui/navigation/ for Screen.kt + FBRecoNavigation.kt, ui/theme/ for FBRecoTheme.kt
- PlaceholderScreen composable is private to FBRecoNavigation.kt — will be replaced by real screens in later tasks

### Task 5.1 - HistoryScreen with Tabs (2026-02-10)
- Material 3 TabRow + Tab composable for tab switching — not deprecated, works well
- rememberSaveable with mutableIntStateOf for tab state preservation across config changes
- WeekFields.ISO for ISO week grouping — weekBasedYear() and weekOfWeekBasedYear() for correct year-boundary handling
- LocalDate.with(weekFields.dayOfWeek(), 1) gives Monday (ISO week start)
- DateTimeFormatter.ofPattern with Locale.JAPANESE for Japanese day-of-week abbreviations (月, 火, etc.)
- Unicode escapes used for Japanese strings in Kotlin source to avoid encoding issues
- String.format with Locale.US for numeric formatting (avoids locale-specific decimal separators)
- LazyColumn items with key = date.toString() for stable recomposition
- CardDefaults.cardColors(containerColor = surfaceVariant) for subtle card background in M3
- File structure: ui/history/ for HistoryScreen.kt + HistoryViewModel.kt
- ViewModel exposes two StateFlows: allRecords (raw) and weeklySummaries (aggregated) — both from same repository source
- Build passed with 1 executed task (kspDebugKotlin) + 39 up-to-date in 31s

### Task 4.2 - HomeScreen (2026-02-10)
- collectAsState() used instead of collectAsStateWithLifecycle() — lifecycle-runtime-compose not in deps, and MUST NOT add deps
- SharedFlow (bikeData) can be .map{}.stateIn() to derive isActive StateFlow in ViewModel — no need for custom combine logic
- BleConnectionState is sealed class with object/data class members — use `is` checks in when() for pattern matching
- AnimatedVisibility with fadeIn/fadeOut for activity indicator — simple, no complex animations per requirements
- StatusInfo private data class for mapping BleConnectionState → (label, color) keeps composable clean
- Color.copy(alpha = 0.12f) for subtle badge backgrounds with full-color dot + text — good M3 pattern
- formatTime/formatDistance as internal package-level functions for potential reuse + testability
- hiltViewModel() from hilt-navigation-compose (already in deps) provides ViewModel in composable
- File structure: ui/home/ for HomeScreen.kt + HomeViewModel.kt
- Build passed: compileDebugKotlin --rerun-tasks succeeded, only pre-existing BleManager deprecation warnings

### Task - RideAccumulator extraction & tests (2026-02-10)
- Extract-and-delegate pattern: pull testable logic from Android service into pure-Kotlin class, keep service as thin coordinator
- RideAccumulator.lastActiveTimestamp uses 0L as sentinel for "no previous data" — test timestamps must be > 0 to trigger accumulation
- prepareFlush does NOT reset lastActiveTimestamp — only resets accumulatedTime/Distance. This means post-flush onBikeData calls still measure interval from last active timestamp
- Midnight boundary detection: compare flush date vs lastFlushDate; when crossed, flush data is attributed to lastFlushDate (old day)
- BikeForegroundService reduced from 263 to 222 lines by delegating accumulation to RideAccumulator
- 10 pure JVM unit tests covering: accumulation, inactive reset, 5s clamping, first-call no-accumulate, null flush, flush-and-reset, midnight boundary, same-date flush, multi-cycle independence, distance accuracy
- Total project tests: 36 (15 FtmsParser + 11 Repository + 10 RideAccumulator), all passing

### Task - BLE Scan Dialog & Pairing UI (2026-02-10)
- ScanDialog.kt: Material 3 AlertDialog with LazyColumn for discovered devices, CircularProgressIndicator while scanning
- DiscoveredDevice data class lives in ScanDialog.kt, imported by HomeViewModel for scan results
- HomeViewModel scan timeout (10,500ms) slightly exceeds BleManager SCAN_TIMEOUT_MS (10,000ms) to ensure BLE scan completes first
- SecurityException catch around device.name needed since BLUETOOTH_CONNECT permission may not be granted at scan time
- ScanDialog rendered outside Column scope but inside HomeScreen composable - dialog overlay doesn't affect layout
- FilledTonalButton for scan trigger, TextButton for disconnect - consistent M3 emphasis hierarchy
- Kotlin LSP not installed in env - rely solely on gradlew assembleDebug/testDebugUnitTest for validation

### Task 6.1 - DestinationScreen with Mapbox Compose (2026-02-10)
- Mapbox Compose extension is a SEPARATE artifact: `com.mapbox.extension:maps-compose:11.8.0` (NOT `com.mapbox.maps:extension-compose`)
- Group ID changed between versions: v11.8.0 uses `com.mapbox.extension`, latest v11.18.x uses same
- MapboxMap composable from `com.mapbox.maps.extension.compose.MapboxMap`
- rememberMapViewportState from `com.mapbox.maps.extension.compose.animation.viewport`
- Point.fromLngLat(lng, lat) — note: LONGITUDE first, LATITUDE second (GeoJSON convention)
- setCameraOptions { center(...); zoom(...) } inside rememberMapViewportState init block
- Tokyo coordinates: lat 35.6762, lng 139.6503 → Point.fromLngLat(139.6503, 35.6762)
- Unicode escapes for "目的地を設定": \u76EE\u7684\u5730\u3092\u8A2D\u5B9A
- PlaceholderScreen removed from FBRecoNavigation.kt since all routes now have real screens
- Removed unused imports (Box, fillMaxSize, Alignment, MaterialTheme) from navigation file after PlaceholderScreen removal
- Build: --no-daemon needed when Gradle daemon runs out of heap space (512 MiB default)
- Build passed with only pre-existing BleManager deprecation warnings
- All 36 existing tests still pass

### Task 6.2 - Destination Selection (Map Long-Press) (2026-02-10)
- MapboxMap composable accepts `onMapLongClickListener: OnMapLongClickListener? = null` directly as parameter — no need for MapEffect
- OnMapLongClickListener is `(Point) -> Boolean` — return true to consume the event
- CircleAnnotation composable from `com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation`
- CircleAnnotation placed inside MapboxMap content lambda (MapboxMapComposable scope)
- CircleAnnotation state init block: circleRadius, circleColor (Color), circleStrokeWidth, circleStrokeColor
- Haversine formula for straight-line distance — pure math, no external deps: R=6371000m, radians conversion, sin²+cos² formula
- calculateStraightLineDistance made `fun` (not private) so DestinationScreen can call it for dialog display
- AlertDialog pattern: onDismissRequest, title, text, confirmButton, dismissButton — same as ScanDialog.kt
- Distance displayed as km with 1 decimal: `String.format("%.1f", distance / 1000.0)`
- Japanese text all as Unicode escapes: 目的地の確認=\u76EE\u7684\u5730\u306E\u78BA\u8A8D, etc.
- Prompt text updated: "地図を長押しして目的地を設定" (instructive) replaces "目的地を設定" (declarative)
- Build: assembleDebug passed (1m17s), testDebugUnitTest passed (10s) — all 36 existing tests still green

### Task 6.3 - Progress Visualization (2026-02-10)
- PolylineAnnotation composable from `com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation`
- PolylineAnnotation placed inside MapboxMap content lambda (MapboxMapComposable scope), same as CircleAnnotation
- PolylineAnnotation state properties use `lineWidth`, `lineColor`, `lineOpacity` (NOT polylineWidth/polylineColor as initially expected)
- PolylineAnnotation takes `points: List<Point>` parameter for the line coordinates
- Color(0x664488FF) for semi-transparent blue line — alpha in highest byte (0x66 ≈ 40% opacity)
- lineOpacity set to 1.0 explicitly since color already includes alpha channel
- ViewModel derived StateFlows: progressPercent and isCompleted from activeDestination using .map{}.stateIn()
- coerceIn(0.0, 100.0) on progressPercent prevents over-100% display from timing edge cases
- isCompleted checks both dest.isActive AND accumulated >= target to avoid showing completion for already-completed destinations
- completeAndReset() delegates to repository.completeDestination(id) which sets isActive=false and completedAt
- Progress overlay at BottomCenter: Card with "X.X km / Y.Y km (Z%)" format using String.format("%.1f"/"%.0f")
- Completion dialog: non-dismissable (onDismissRequest = {}), both buttons call completeAndReset() to ensure cleanup
- Japanese strings: 目的地に到達=\u76EE\u7684\u5730\u306B\u5230\u9054, 新しい目的地を設定=\u65B0\u3057\u3044\u76EE\u7684\u5730\u3092\u8A2D\u5B9A
- Build: assembleDebug passed (53s), testDebugUnitTest passed (15s) — all 36 existing tests green

### Task 7.2 - Permission Request Flow (2026-02-10)
- PermissionScreen.kt uses built-in ActivityResultContracts.RequestMultiplePermissions — no accompanist dependency needed
- rememberLauncherForActivityResult for both permission requests and startActivityForResult (settings return)
- Three-step flow: Explanation → Denied (retry/settings) → Battery Optimization (request/skip)
- API-level branching: API 31+ needs BLUETOOTH_SCAN + BLUETOOTH_CONNECT + ACCESS_FINE_LOCATION; pre-31 only ACCESS_FINE_LOCATION
- Battery optimization: PowerManager.isIgnoringBatteryOptimizations check before launching ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission added to AndroidManifest.xml
- MainActivity wraps FBRecoNavigation with permission check: allPermissionsGranted() → show nav or PermissionScreen
- allPermissionsGranted() and requiredPermissions() are internal functions in PermissionScreen.kt, imported by MainActivity
- Japanese text as Unicode escapes (project convention): 権限の設定, 許可する, 再度許可する, 設定を開く, バッテリー最適化, スキップ
- Settings fallback: ACTION_APPLICATION_DETAILS_SETTINGS with Uri.fromParts("package", packageName, null)
- Build: assembleDebug passed (21s), testDebugUnitTest passed (5s) — all 36 existing tests green
