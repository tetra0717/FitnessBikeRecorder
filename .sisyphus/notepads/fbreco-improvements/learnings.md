# FBReco Improvements — Learnings

## Initial Context
- Project builds with: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug`
- Tests run with: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest`
- Existing test file has 10 tests in `RideAccumulatorTest.kt` (182 lines)
- `DatabaseModule.kt` is the pattern reference for Hilt modules
- `libs.versions.toml` has 15 version entries, 18 library entries
- Build uses JDK 17, AGP 8.7.3, Kotlin 2.1.0

## Task: LocationModule & Play Services Location

### Changes Made
1. **gradle/libs.versions.toml**
   - Added version: `play-services-location = "21.3.0"`
   - Added library entry with Group ID `com.google.android.gms`
   - Placed comment `# Location Services` for organization

2. **app/build.gradle.kts**
   - Added dependency: `implementation(libs.play.services.location)` after maplibre-compose

3. **Created LocationModule.kt** (new file)
   - Pattern copied from `DatabaseModule.kt`
   - Uses Hilt `@Module` + `@InstallIn(SingletonComponent::class)`
   - Provides `FusedLocationProviderClient` as `@Singleton`
   - Injects `@ApplicationContext` to create client via `LocationServices.getFusedLocationProviderClient()`

### Build Result
✓ `./gradlew assembleDebug` succeeded
✓ Generated `app-debug.apk` (60MB) with new dependency
✓ Kotlin compilation clean (only existing deprecation warnings in BleManager)

### Patterns Learned
- Hilt module structure: `@Module @InstallIn(SingletonComponent::class) object ModuleName { @Provides @Singleton fun provideXyz(...) }`
- Version catalog format: versions in `[versions]`, libraries in `[libraries]`, organized by comment sections
- Google Play Services convention: uses `com.google.android.gms` group ID, version 21.x is current stable for location

## Task: Fix RideAccumulator Integer-Division Bug + Add Tests

### Problem
Line 28 of `RideAccumulator.kt` used integer division that lost sub-second accumulation:
```kotlin
accumulatedTimeSeconds += clampedIntervalMs / 1000L
```
With BLE data at 500ms intervals, `500 / 1000 = 0` in integer division → time NEVER accumulated.

### Solution
Introduced millisecond remainder tracking with modulo arithmetic:
1. Added field: `private var accumulatedTimeMillisRemainder: Long = 0L`
2. Changed accumulation logic:
   ```kotlin
   val totalMs = accumulatedTimeMillisRemainder + clampedIntervalMs
   accumulatedTimeSeconds += totalMs / 1000L
   accumulatedTimeMillisRemainder = totalMs % 1000L
   ```
3. Reset remainder in `prepareFlush()` and `reset()` methods

### Implementation Details
- Remainder carries across calls (e.g., 500ms + 500ms = 1000ms = 1 second)
- Works with any interval size: 200ms, 500ms, 700ms, etc.
- No floating-point arithmetic (all Long operations)
- Test verification: Added 3 unit tests covering sub-second intervals

### Test Results
✓ 13 total tests (10 original + 3 new) — all pass
✓ Test 11: Sub-second accumulation (4× 500ms = 2 seconds)
✓ Test 12: Smaller intervals (10× 200ms = 2 seconds)
✓ Test 13: Remainder carry-across (2× 700ms = 1 second + 400ms remainder)

### Build Verification
✓ `./gradlew testDebugUnitTest --tests "com.fbreco.service.RideAccumulatorTest"` — 13 tests, 0 failures
✓ `./gradlew assembleDebug` — BUILD SUCCESSFUL in 12s

### Patterns Learned
- Modulo arithmetic for fixed-size unit conversion (ms to seconds with remainder)
- Test file organization: Comment headers mark test groups (e.g., `// ── 11. ...`)
- Test naming uses backtick syntax for clarity: `` `test name with spaces` ``
- XML test results at `app/build/test-results/testDebugUnitTest/TEST-*.xml`

## Task: RideSnapshot state flow for real-time UI

### Changes Made
- Added `RideSnapshot` data class in `BikeForegroundService.kt` for total time, total distance, and accumulator distance.
- Added `currentSnapshot: MutableStateFlow<RideSnapshot>` to the service companion object.
- Emitted snapshot every notification update tick using totals + accumulator values.
- Reset snapshot to zeros in `onDestroy()` and initialized from DB values in `loadTodayStats()`.
- Updated snapshot after `flushAccumulatedData()` to keep totals in sync with DB writes.

### Build & Test Results
- `./gradlew assembleDebug` succeeded.
- `./gradlew testDebugUnitTest` succeeded.

## Task: GeocodingService & Coroutines Play Services

### Problem
Needed to add Photon API geocoding client for destination search, integrated with coroutines for async/await support.

### Changes Made
1. **gradle/libs.versions.toml**
   - Added version: `okhttp = "4.12.0"` (4.x stable for Android)
   - Added version: `coroutines-play-services = "1.9.0"` (reuses coroutines-test version)
   - Added library entries in `[libraries]` section organized by comment sections

2. **app/build.gradle.kts**
   - Added: `implementation(libs.coroutines.play.services)` for Task-based API integration
   - Added: `implementation(libs.okhttp)` for direct HTTP requests to Photon API

3. **Created GeocodingService.kt**
   - Singleton class with `@Inject` for Hilt DI
   - `search(query: String)` suspend function using Photon API (`photon.komoot.io`)
   - Returns `List<GeocodingResult>` with name, country, city, latitude, longitude
   - Uses `withContext(Dispatchers.IO)` for background I/O
   - OkHttp configured with 5-second timeouts
   - Handles empty queries and network exceptions gracefully (returns empty list)
   - JSON parsing via `JSONObject` (built-in Android SDK)

### Implementation Details
- **optString() type fix**: Used `.optString("key", "").ifEmpty { null }` to convert empty strings to null while avoiding type mismatch warnings
- **API format**: Photon GeoJSON returns coordinates as `[longitude, latitude]` — reversed from typical lat/long order, handled correctly
- **Coroutines pattern**: `suspend fun` + `withContext(Dispatchers.IO)` matches project's async conventions

### Build Result
✓ `./gradlew clean assembleDebug` — BUILD SUCCESSFUL in 1m 18s
✓ Only pre-existing BleManager deprecation warnings (not related to GeocodingService)
✓ APK generated with OkHttp 4.12.0 + coroutines-play-services 1.9.0

### Patterns Learned
- OkHttp must be added explicitly even when pulling transitive deps (MapLibre doesn't depend on OkHttp)
- Version catalog `[versions]` section needs all top-level versions, including networking libraries
- Photon API returns GeoJSON with features array; each feature has geometry + properties
- Singleton services with @Inject work seamlessly with Hilt
- JSONObject null handling: use empty-string defaults then check `.ifEmpty { null }` to maintain type safety
