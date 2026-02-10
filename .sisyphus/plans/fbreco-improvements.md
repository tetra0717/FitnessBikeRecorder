# FBReco Improvements v2 — Real-time Display, Map Enhancements, Bug Fixes

## TL;DR

> **Quick Summary**: Fix the time accumulation integer-division bug, add real-time data display to Home screen and Map, replace hardcoded Tokyo with device location, add destination search via Photon API, add destination reset, and show a live progress marker on the map.
> 
> **Deliverables**:
> - Fixed time accumulation (millisecond-remainder approach)
> - Real-time `StateFlow<RideSnapshot>` in BikeForegroundService
> - Home screen showing live time/distance (1-second updates)
> - Map starting at device's current location (Tokyo fallback)
> - Destination search via Photon geocoding API
> - Live "current position" marker on start→destination line
> - Destination reset button with confirmation dialog
> 
> **Estimated Effort**: Medium
> **Parallel Execution**: YES — 3 waves
> **Critical Path**: Task 1 → Task 2 → Task 3 → Task 5 → Task 6

---

## Context

### Original Request
User reported 6 improvements needed after real-device testing of FBReco:
1. 走行時間の一番下の桁が1秒ずつ増えない＆1分こいでも数値が増えないバグ
2. ホーム画面がリアルタイムに反映されない（通知は反映されている）
3. マップのスタート位置が東京固定→現在位置にしてほしい
4. 目的地の検索機能がほしい
5. マップの走行反映がリアルタイムでない＋現在位置マーカーがない
6. 目的地の再設定機能がない

### Interview Summary
**Key Discussions**:
- User confirmed all 6 issues and their priority
- User explicitly requested confirmation before implementation: 「使う技術や仕様を確定した際は私に確認を取ってください」
- User approved: Photon API for geocoding, FusedLocationProviderClient for location, millis-remainder fix for time bug, StateFlow for real-time UI

**Research Findings**:
- Integer division bug confirmed at `RideAccumulator.kt:28` — `clampedIntervalMs / 1000L` yields 0 for intervals < 1000ms
- HomeViewModel only observes Room DB via `dailyRecordRepository.observeToday()` — 30s lag
- Notification already reads `accumulator.accumulatedTimeSeconds` directly at 1s intervals
- `ACCESS_FINE_LOCATION` already in AndroidManifest.xml with runtime permission flow
- OkHttp is available as transitive dependency via MapLibre
- MapLibre Compose 0.12.1 uses `org.maplibre.spatialk.geojson.Position` (confirmed)

### Metis Review
**Identified Gaps** (addressed):
- StateFlow source of truth: DB remains canonical storage; StateFlow is live view combining DB + accumulator for display only
- Location permission denied: Silent fallback to Tokyo with no extra prompts (permission already in existing flow)
- Photon API failure: 5s timeout, error state in UI, graceful degradation
- Battery/perf: Throttled at 1s (same as existing notification cadence)
- Service not running when Home screen active: Show DB-only data (current behavior = graceful degradation)
- BLE interval jitter: Millis-remainder approach handles any interval correctly
- First map snapshot with no prior point: Show marker at start point (0% progress)

---

## Work Objectives

### Core Objective
Fix the time accumulation bug, make Home screen and Map display data in real-time (1-second cadence), and add destination search + reset + current location features to the Map.

### Concrete Deliverables
- `RideAccumulator.kt` — fixed time accumulation with millis remainder
- `BikeForegroundService.kt` — new `currentSnapshot: MutableStateFlow<RideSnapshot>` in companion object
- `HomeViewModel.kt` — observes service snapshot flow for real-time display
- `HomeScreen.kt` — displays live time/distance from snapshot
- `DestinationViewModel.kt` — current location init, search, reset, real-time progress
- `DestinationScreen.kt` — search UI, reset button, live progress marker, current location camera
- `LocationModule.kt` (new) — Hilt provider for `FusedLocationProviderClient`
- `GeocodingService.kt` (new) — Photon API client
- `libs.versions.toml` + `app/build.gradle.kts` — play-services-location dependency
- `RideAccumulatorTest.kt` — updated/new tests for sub-second interval accumulation

### Definition of Done
- [ ] `./gradlew testDebugUnitTest` passes with all existing + new tests
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Time accumulation works correctly for 500ms BLE intervals (verified by unit test)
- [ ] Home screen updates every ~1 second when bike is active
- [ ] Map starts at device location (not Tokyo)
- [ ] Destination search returns results from Photon API
- [ ] Map shows live progress marker on the route line
- [ ] Destination can be reset mid-journey

### Must Have
- Millisecond-remainder approach for time accumulation (not float/double conversion)
- StateFlow in `BikeForegroundService.companion` for cross-component real-time data
- Tokyo (139.6503, 35.6762) as fallback when location unavailable
- Photon API with no API key requirement
- Confirmation dialog before destination reset
- OSM attribution (already present via MapLibre)

### Must NOT Have (Guardrails)
- No changes to BLE parsing (`FtmsParser.kt`) — only `RideAccumulator` time logic changes
- No Room schema changes — `RideSnapshot` is a display-only data class, not persisted
- No server-side components or paid APIs
- No offline geocoding or map caching
- No road-following route display (straight line only)
- No GPS real-time tracking (BLE distance only for progress)
- No redesign of existing UI — only add minimal elements (search bar, reset button, progress marker)
- No changes to the 30s DB flush interval — real-time display uses StateFlow, DB writes stay at 30s
- No Retrofit — use OkHttp directly (simpler for single endpoint)

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> ALL tasks in this plan MUST be verifiable WITHOUT any human action.
> This is NOT conditional — it applies to EVERY task, regardless of test strategy.

### Test Decision
- **Infrastructure exists**: YES — JUnit 4 + kotlinx-coroutines-test
- **Automated tests**: YES (Tests-after for new code, update existing tests)
- **Framework**: JUnit 4 via `./gradlew testDebugUnitTest`
- **Test command**: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest`
- **Build command**: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug`

### Agent-Executed QA Scenarios (MANDATORY — ALL tasks)

> Every task includes QA scenarios verified by the executing agent.
> UI verification is done via build success + unit tests (no Playwright — Android native app).
> Logic verification via unit tests.
> API verification via unit tests with mocked HTTP.

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately):
├── Task 1: Fix time accumulation bug + update tests (no dependencies)
└── Task 4: Add play-services-location dependency + LocationModule (no dependencies)

Wave 2 (After Wave 1):
├── Task 2: Add StateFlow<RideSnapshot> to BikeForegroundService (depends: 1)
├── Task 3: Real-time Home screen (depends: 2)
└── Task 5: Current location + destination search + reset in DestinationViewModel (depends: 4)

Wave 3 (After Wave 2):
└── Task 6: Real-time map progress + search UI + reset UI in DestinationScreen (depends: 2, 5)

Wave 4 (After Wave 3):
└── Task 7: Final integration build + test + commit (depends: all)

Critical Path: Task 1 → Task 2 → Task 3, Task 6
Parallel Speedup: ~30% faster than sequential
```

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|---------------------|
| 1 | None | 2 | 4 |
| 2 | 1 | 3, 6 | 5 |
| 3 | 2 | 7 | 5, 6 |
| 4 | None | 5 | 1 |
| 5 | 4 | 6 | 2, 3 |
| 6 | 2, 5 | 7 | 3 |
| 7 | 3, 6 | None | None (final) |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Agents |
|------|-------|-------------------|
| 1 | 1, 4 | task(category="quick", ...) — small focused changes |
| 2 | 2, 3, 5 | task(category="business-logic", ...) — data flow and logic |
| 3 | 6 | task(category="visual-engineering", load_skills=["frontend-ui-ux"], ...) — UI composition |
| 4 | 7 | task(category="quick", ...) — build verification + commit |

---

## TODOs

- [ ] 1. Fix time accumulation integer-division bug in RideAccumulator

  **What to do**:
  - Add `private var accumulatedTimeMillisRemainder: Long = 0L` field to `RideAccumulator`
  - In `onBikeData()`, replace line 28 (`accumulatedTimeSeconds += clampedIntervalMs / 1000L`) with:
    ```kotlin
    val totalMs = accumulatedTimeMillisRemainder + clampedIntervalMs
    accumulatedTimeSeconds += totalMs / 1000L
    accumulatedTimeMillisRemainder = totalMs % 1000L
    ```
  - In `prepareFlush()`, reset `accumulatedTimeMillisRemainder = 0L` alongside the other resets (line 61-62)
  - In `reset()`, reset `accumulatedTimeMillisRemainder = 0L` (line 71)
  - Update `RideAccumulatorTest.kt`:
    - Add new test: `onBikeData accumulates time correctly with sub-second intervals` — call `onBikeData` with 500ms intervals × 4 times → assert `accumulatedTimeSeconds == 2L`
    - Add new test: `onBikeData accumulates time correctly with 200ms intervals` — 200ms × 10 → assert `accumulatedTimeSeconds == 2L`
    - Add new test: `onBikeData remainder carries across calls` — 700ms + 700ms → assert `accumulatedTimeSeconds == 1L` (remainder 400ms)
    - Verify existing test `onBikeData accumulates time and distance when active` still passes (1000ms interval → 1 second)

  **Must NOT do**:
  - Do NOT change distance calculation (it uses Double, no integer division issue)
  - Do NOT change `FtmsParser.kt`
  - Do NOT change the `clampedIntervalMs` clamping logic (5s max is correct)
  - Do NOT use floating-point for time (keep Long millis arithmetic)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Small, focused bug fix in a single file + test updates
  - **Skills**: []
    - No special skills needed — pure Kotlin logic change
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: Not a UI task
    - `playwright`: Android native, no browser

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Task 4)
  - **Blocks**: Task 2 (needs correct accumulator before building StateFlow on top)
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/service/RideAccumulator.kt:22-36` — The `onBikeData()` method containing the bug. Line 28 is the integer division. Lines 60-62 are the reset in `prepareFlush()`. Lines 69-73 are `reset()`.
  - `app/src/main/java/com/fbreco/service/RideAccumulator.kt:10-17` — Current field declarations. Add `accumulatedTimeMillisRemainder` here with same pattern.

  **Test References**:
  - `app/src/test/java/com/fbreco/service/RideAccumulatorTest.kt` — Full existing test file (182 lines, 10 tests). Follow exact same test pattern: `@Before` setup, descriptive backtick names, `assertEquals` assertions. Tests use `FtmsParser.BikeData` constructor with `speedKmh`, `cadenceRpm`, `powerWatts`, `isActive`.
  - `app/src/test/java/com/fbreco/service/RideAccumulatorTest.kt:20-30` — Example of accumulation test pattern using `nowMs` timestamps.

  **API/Type References**:
  - `app/src/main/java/com/fbreco/ble/FtmsParser.kt` — `FtmsParser.BikeData` data class and `calculateDistanceMeters(speedKmh, intervalMs)` static method.

  **WHY Each Reference Matters**:
  - `RideAccumulator.kt:28` — This is THE bug. The fix is here.
  - `RideAccumulatorTest.kt:20-30` — Follow this pattern to write new sub-second tests.
  - `RideAccumulator.kt:60-62` — Must also reset the remainder here or it leaks across flush cycles.

  **Acceptance Criteria**:

  - [ ] `accumulatedTimeMillisRemainder` field exists in `RideAccumulator`
  - [ ] `onBikeData()` uses millis remainder approach (no `/ 1000L` integer division on raw interval)
  - [ ] `prepareFlush()` resets `accumulatedTimeMillisRemainder` to 0
  - [ ] `reset()` resets `accumulatedTimeMillisRemainder` to 0

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Sub-second intervals accumulate time correctly
    Tool: Bash
    Preconditions: Project builds
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest --tests "com.fbreco.service.RideAccumulatorTest"
      2. Assert: All tests PASS including new sub-second tests
      3. Assert: Output contains "Tests run: 13" (10 existing + 3 new) or similar count
    Expected Result: All tests pass, 0 failures
    Evidence: Test output captured in terminal

  Scenario: Build still compiles after changes
    Tool: Bash
    Preconditions: Tests pass
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: Debug APK builds without errors
    Evidence: Build output captured
  ```

  **Commit**: YES (groups with Task 2)
  - Message: `fix(accumulator): use millis remainder to fix integer division time bug`
  - Files: `RideAccumulator.kt`, `RideAccumulatorTest.kt`
  - Pre-commit: `./gradlew testDebugUnitTest --tests "com.fbreco.service.RideAccumulatorTest"`

---

- [ ] 2. Add real-time RideSnapshot StateFlow to BikeForegroundService

  **What to do**:
  - Define `data class RideSnapshot(val totalTimeSeconds: Long, val totalDistanceMeters: Double)` — can be at top of `BikeForegroundService.kt` or in its own file in `service/` package
  - Add to `BikeForegroundService.companion`:
    ```kotlin
    val currentSnapshot = MutableStateFlow(RideSnapshot(0L, 0.0))
    ```
  - In `startNotificationUpdater()` (the 1-second loop), after `updateNotification()`, also update the snapshot:
    ```kotlin
    val totalTime = todayTotalTimeSeconds + accumulator.accumulatedTimeSeconds
    val totalDist = todayTotalDistanceMeters + accumulator.accumulatedDistanceMeters
    currentSnapshot.value = RideSnapshot(totalTime, totalDist)
    ```
    Note: `updateNotification()` already computes these exact values (line 133-134). Refactor to avoid duplication by extracting the computation, or just duplicate the two lines since it's simple.
  - In `onDestroy()`, reset snapshot: `currentSnapshot.value = RideSnapshot(0L, 0.0)`
  - In `flushAccumulatedData()`, after updating `todayTotalTimeSeconds`/`todayTotalDistanceMeters` (lines 200-201), also update snapshot immediately so it reflects the new base values.
  - In `loadTodayStats()` (lines 219-227), after loading DB values, also emit initial snapshot so UI gets the stored value immediately on service start.

  **Must NOT do**:
  - Do NOT change the 30s flush interval or notification update interval
  - Do NOT change how `accumulator` works (that's Task 1)
  - Do NOT make `RideSnapshot` a Room entity
  - Do NOT change the notification text format

  **Recommended Agent Profile**:
  - **Category**: `business-logic`
    - Reason: Data flow architecture — connecting accumulator to UI via reactive flow
  - **Skills**: []
    - No special skills needed — Kotlin coroutines + StateFlow
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: Not a UI task, this is service-layer plumbing

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 1)
  - **Parallel Group**: Wave 2 (with Tasks 3, 5 after their respective dependencies)
  - **Blocks**: Tasks 3, 6 (both consume the snapshot flow)
  - **Blocked By**: Task 1 (accumulator must be fixed first so snapshot values are correct)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:31-39` — Existing companion object with `isRunning: MutableStateFlow<Boolean>`. Add `currentSnapshot` in the same pattern.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:129-148` — `updateNotification()` method. Lines 133-134 compute `totalTime` and `totalDist`. These same values feed the snapshot.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:208-215` — `startNotificationUpdater()` — the 1-second loop. Add snapshot emission here.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:182-204` — `flushAccumulatedData()` where DB totals are updated.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:219-227` — `loadTodayStats()` where initial DB values are loaded.

  **API/Type References**:
  - `app/src/main/java/com/fbreco/service/RideAccumulator.kt:10-11` — `accumulatedTimeSeconds` and `accumulatedDistanceMeters` properties read by the service.

  **WHY Each Reference Matters**:
  - `BikeForegroundService.kt:31-39` — Pattern for static StateFlow in companion. Follow exactly.
  - `BikeForegroundService.kt:129-148` — Shows the computation already exists. Reuse same formula.
  - `BikeForegroundService.kt:208-215` — This is WHERE to emit the snapshot (inside the 1s loop).

  **Acceptance Criteria**:

  - [ ] `RideSnapshot` data class exists with `totalTimeSeconds: Long` and `totalDistanceMeters: Double`
  - [ ] `BikeForegroundService.currentSnapshot` is a `MutableStateFlow<RideSnapshot>` in companion
  - [ ] Snapshot is updated every ~1 second (inside notification updater loop)
  - [ ] Snapshot is reset to zeros on service destroy
  - [ ] Snapshot is initialized with DB values on service create

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build compiles with new StateFlow
    Tool: Bash
    Preconditions: Task 1 complete
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: No compilation errors from new StateFlow or RideSnapshot
    Evidence: Build output captured

  Scenario: All existing tests still pass
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: All tests PASS, 0 failures
    Expected Result: No regressions
    Evidence: Test output captured
  ```

  **Commit**: YES (group with Task 1)
  - Message: `feat(service): add real-time RideSnapshot StateFlow for live UI updates`
  - Files: `BikeForegroundService.kt`
  - Pre-commit: `./gradlew assembleDebug`

---

- [ ] 3. Make Home screen display real-time data from service StateFlow

  **What to do**:
  - In `HomeViewModel.kt`:
    - Add a `serviceSnapshot` StateFlow that observes `BikeForegroundService.currentSnapshot`
    - Combine `todayRecord` (Room DB) with `serviceSnapshot` to produce the display values
    - When service IS running (`BikeForegroundService.isRunning.value == true`), use snapshot values (they already include DB base + accumulator)
    - When service is NOT running, fall back to `todayRecord` from DB (current behavior)
    - Expose a combined `displayTimeSeconds: StateFlow<Long>` and `displayDistanceMeters: StateFlow<Double>` or keep using `todayRecord` but override with snapshot when available
  - In `HomeScreen.kt`:
    - Change `TodayStatsCard` to read from the new real-time values instead of `todayRecord?.totalTimeSeconds ?: 0L`
    - Lines 101-104 currently read from `todayRecord` — change to read from ViewModel's real-time combined state

  **Implementation approach** (simplest):
  ```kotlin
  // HomeViewModel.kt — add these:
  val serviceRunning: StateFlow<Boolean> = BikeForegroundService.isRunning
  val serviceSnapshot: StateFlow<RideSnapshot> = BikeForegroundService.currentSnapshot

  // HomeScreen.kt — change lines 101-104 to:
  val serviceRunning by viewModel.serviceRunning.collectAsState()
  val snapshot by viewModel.serviceSnapshot.collectAsState()
  
  TodayStatsCard(
      totalTimeSeconds = if (serviceRunning) snapshot.totalTimeSeconds else (todayRecord?.totalTimeSeconds ?: 0L),
      totalDistanceMeters = if (serviceRunning) snapshot.totalDistanceMeters else (todayRecord?.totalDistanceMeters ?: 0.0),
  )
  ```

  **Must NOT do**:
  - Do NOT remove the Room DB observation (it's the fallback when service isn't running)
  - Do NOT change TodayStatsCard's internal layout or styling
  - Do NOT add polling or timers in the ViewModel — StateFlow is push-based

  **Recommended Agent Profile**:
  - **Category**: `business-logic`
    - Reason: Connecting data flows between service and UI layer
  - **Skills**: []
    - No special skills needed — standard ViewModel + Compose state
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: UI layout isn't changing, only data source

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 2)
  - **Parallel Group**: Wave 2 (after Task 2, parallel with Task 5)
  - **Blocks**: Task 7 (integration)
  - **Blocked By**: Task 2 (StateFlow must exist to observe)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/ui/home/HomeViewModel.kt:29-30` — Current `todayRecord` StateFlow from Room. Keep this but add real-time override.
  - `app/src/main/java/com/fbreco/ui/home/HomeScreen.kt:46-50` — Where state is collected. Add `serviceRunning` and `snapshot` here.
  - `app/src/main/java/com/fbreco/ui/home/HomeScreen.kt:100-104` — Where `TodayStatsCard` is called with `todayRecord` values. Change data source here.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:31-39` — companion object with `isRunning` and (after Task 2) `currentSnapshot`.

  **WHY Each Reference Matters**:
  - `HomeViewModel.kt:29-30` — Shows existing data flow pattern. Add parallel flow for service data.
  - `HomeScreen.kt:100-104` — THE lines to change for real-time display.
  - `BikeForegroundService.kt:31-39` — Source of the StateFlows to observe.

  **Acceptance Criteria**:

  - [ ] HomeViewModel exposes `serviceRunning` and `serviceSnapshot` StateFlows
  - [ ] HomeScreen uses snapshot values when service is running
  - [ ] HomeScreen falls back to todayRecord when service is not running
  - [ ] Import for `RideSnapshot` and `BikeForegroundService` added to HomeViewModel

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build succeeds with real-time Home screen
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: No compilation errors
    Evidence: Build output captured

  Scenario: All tests pass
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: All tests PASS
    Expected Result: No regressions
    Evidence: Test output captured
  ```

  **Commit**: YES (group with Tasks 1, 2)
  - Message: `feat(home): display real-time ride data from service StateFlow`
  - Files: `HomeViewModel.kt`, `HomeScreen.kt`
  - Pre-commit: `./gradlew assembleDebug`

---

- [ ] 4. Add play-services-location dependency and LocationModule for Hilt

  **What to do**:
  - In `gradle/libs.versions.toml`:
    - Add version: `play-services-location = "21.3.0"` in `[versions]`
    - Add library: `play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "play-services-location" }` in `[libraries]`
  - In `app/build.gradle.kts`:
    - Add: `implementation(libs.play.services.location)` in dependencies block
  - Create `app/src/main/java/com/fbreco/di/LocationModule.kt`:
    ```kotlin
    package com.fbreco.di

    import android.content.Context
    import com.google.android.gms.location.FusedLocationProviderClient
    import com.google.android.gms.location.LocationServices
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.android.qualifiers.ApplicationContext
    import dagger.hilt.components.SingletonComponent
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    object LocationModule {
        @Provides
        @Singleton
        fun provideFusedLocationProviderClient(
            @ApplicationContext context: Context
        ): FusedLocationProviderClient {
            return LocationServices.getFusedLocationProviderClient(context)
        }
    }
    ```

  **Must NOT do**:
  - Do NOT add any new permissions to AndroidManifest.xml — `ACCESS_FINE_LOCATION` is already there
  - Do NOT add location tracking or continuous updates — just the Hilt provider
  - Do NOT modify existing `DatabaseModule.kt`

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple dependency addition + boilerplate Hilt module
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - All — this is a trivial configuration task

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Task 1)
  - **Blocks**: Task 5 (DestinationViewModel needs location client)
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/di/DatabaseModule.kt` — Exact Hilt module pattern to follow. Same annotations: `@Module`, `@InstallIn(SingletonComponent::class)`, `@Provides`, `@Singleton`, `@ApplicationContext`.
  - `gradle/libs.versions.toml:1-61` — Version catalog format. Add entries following existing patterns.
  - `app/build.gradle.kts:51-80` — Dependencies section. Add `implementation(libs.play.services.location)` alongside other implementations.

  **WHY Each Reference Matters**:
  - `DatabaseModule.kt` — Copy this exact pattern for `LocationModule.kt`. Same Hilt boilerplate.
  - `libs.versions.toml` — Must match the exact catalog syntax (group, name, version.ref).
  - `build.gradle.kts:51-80` — Shows where to add the new dependency line.

  **Acceptance Criteria**:

  - [ ] `libs.versions.toml` has `play-services-location = "21.3.0"` version and library entry
  - [ ] `app/build.gradle.kts` has `implementation(libs.play.services.location)`
  - [ ] `LocationModule.kt` exists at `app/src/main/java/com/fbreco/di/LocationModule.kt`
  - [ ] `LocationModule` provides `FusedLocationProviderClient` as `@Singleton`

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build succeeds with new location dependency
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: Dependency resolves and Hilt module compiles
    Evidence: Build output captured
  ```

  **Commit**: YES (standalone)
  - Message: `feat(di): add play-services-location and LocationModule for current position`
  - Files: `libs.versions.toml`, `app/build.gradle.kts`, `LocationModule.kt`
  - Pre-commit: `./gradlew assembleDebug`

---

- [ ] 5. Add current location, destination search, real-time progress, and reset to DestinationViewModel

  **What to do**:

  **5a. Current Location (replace hardcoded Tokyo)**:
  - Inject `FusedLocationProviderClient` into `DestinationViewModel` constructor
  - Change `startPoint` from `val` to `MutableStateFlow<Position>` with Tokyo as initial value:
    ```kotlin
    private val _startPoint = MutableStateFlow(Position(longitude = 139.6503, latitude = 35.6762))
    val startPoint: StateFlow<Position> = _startPoint.asStateFlow()
    ```
  - In `init {}`, launch a coroutine to get current location:
    ```kotlin
    init {
        viewModelScope.launch {
            try {
                val location = fusedLocationClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null // CancellationToken
                ).await()
                if (location != null) {
                    _startPoint.value = Position(longitude = location.longitude, latitude = location.latitude)
                }
            } catch (e: SecurityException) {
                // Permission not granted — keep Tokyo fallback
            }
        }
    }
    ```
  - Add `import kotlinx.coroutines.tasks.await` for Google Play Services Task→suspend conversion
  - Update `confirmDestination()` and `calculateStraightLineDistance` calls to use `_startPoint.value` instead of `startPoint` (since it's now a StateFlow, not a val)

  **5b. Destination Search (Photon API)**:
  - Create `app/src/main/java/com/fbreco/data/GeocodingService.kt`:
    ```kotlin
    package com.fbreco.data

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import org.json.JSONObject
    import java.util.concurrent.TimeUnit
    import javax.inject.Inject
    import javax.inject.Singleton

    data class GeocodingResult(
        val name: String,
        val country: String?,
        val city: String?,
        val latitude: Double,
        val longitude: Double,
    )

    @Singleton
    class GeocodingService @Inject constructor() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        suspend fun search(query: String): List<GeocodingResult> = withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            try {
                val url = "https://photon.komoot.io/api/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseGeoJson(body)
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun parseGeoJson(json: String): List<GeocodingResult> {
            val root = JSONObject(json)
            val features = root.getJSONArray("features")
            return (0 until features.length()).map { i ->
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                val properties = feature.getJSONObject("properties")
                GeocodingResult(
                    name = properties.optString("name", "Unknown"),
                    country = properties.optString("country", null),
                    city = properties.optString("city", null),
                    latitude = coordinates.getDouble(1),
                    longitude = coordinates.getDouble(0),
                )
            }
        }
    }
    ```
  - In `DestinationViewModel`:
    - Inject `GeocodingService`
    - Add search state:
      ```kotlin
      private val _searchQuery = MutableStateFlow("")
      val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
      private val _searchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
      val searchResults: StateFlow<List<GeocodingResult>> = _searchResults.asStateFlow()
      private val _isSearching = MutableStateFlow(false)
      val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
      ```
    - Add debounced search trigger in `init`:
      ```kotlin
      viewModelScope.launch {
          _searchQuery.debounce(300).collectLatest { query ->
              if (query.length >= 2) {
                  _isSearching.value = true
                  _searchResults.value = geocodingService.search(query)
                  _isSearching.value = false
              } else {
                  _searchResults.value = emptyList()
              }
          }
      }
      ```
    - Add methods:
      ```kotlin
      fun updateSearchQuery(query: String) { _searchQuery.value = query }
      fun selectSearchResult(result: GeocodingResult) {
          _selectedPoint.value = Position(longitude = result.longitude, latitude = result.latitude)
          _searchQuery.value = ""
          _searchResults.value = emptyList()
      }
      fun clearSearch() {
          _searchQuery.value = ""
          _searchResults.value = emptyList()
      }
      ```

  **5c. Real-time Progress**:
  - Add observation of `BikeForegroundService.currentSnapshot`:
    ```kotlin
    val serviceSnapshot: StateFlow<RideSnapshot> = BikeForegroundService.currentSnapshot
    ```
  - Add computed real-time progress for the map (combining DB distance + live accumulator):
    ```kotlin
    val liveProgressPercent: StateFlow<Double> = combine(
        activeDestination,
        BikeForegroundService.currentSnapshot
    ) { dest, snapshot ->
        if (dest == null || dest.targetDistanceMeters <= 0.0) 0.0
        else {
            // snapshot.totalDistanceMeters is today's total; dest.accumulatedDistanceMeters is DB-persisted
            // We need: DB persisted distance + unflushed accumulator distance
            // But snapshot already contains todayDB + accumulator...
            // The unflushed amount = snapshot.totalDistanceMeters - todayDBDistance
            // Since we can't easily get todayDBDistance here, simpler approach:
            // Just use dest.accumulatedDistanceMeters from DB + the accumulator's unflushed part
            // Actually, BikeForegroundService tracks todayTotalDistanceMeters + accumulator.accumulatedDistanceMeters in snapshot
            // dest.accumulatedDistanceMeters already includes all flushed data (all days)
            // The unflushed portion = accumulator.accumulatedDistanceMeters (not yet flushed to DB)
            // We can't directly access accumulator from ViewModel...
            // So: track accumulatorDistance via the difference between snapshot and last known DB value
            // OR: simplify - just observe snapshot and use it for "unflushed bonus"
            // Best approach: expose accumulatedDistanceMeters separately in RideSnapshot
            // REVISED RideSnapshot: add accumulatorDistanceMeters field
            (dest.accumulatedDistanceMeters / dest.targetDistanceMeters * 100.0).coerceIn(0.0, 100.0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    ```
    
    **IMPORTANT DESIGN NOTE**: To make real-time map distance work, `RideSnapshot` should ALSO include `accumulatorDistanceMeters: Double` (the unflushed portion only). This way:
    - `dest.accumulatedDistanceMeters` = all flushed distance (from DB, all days)
    - `snapshot.accumulatorDistanceMeters` = currently unflushed distance
    - Real-time total for destination = `dest.accumulatedDistanceMeters + snapshot.accumulatorDistanceMeters`
    
    Update `RideSnapshot` in Task 2 (or here) to:
    ```kotlin
    data class RideSnapshot(
        val totalTimeSeconds: Long,
        val totalDistanceMeters: Double,
        val accumulatorDistanceMeters: Double,  // unflushed portion only
    )
    ```
    And in `BikeForegroundService`, when emitting snapshot:
    ```kotlin
    currentSnapshot.value = RideSnapshot(
        totalTimeSeconds = todayTotalTimeSeconds + accumulator.accumulatedTimeSeconds,
        totalDistanceMeters = todayTotalDistanceMeters + accumulator.accumulatedDistanceMeters,
        accumulatorDistanceMeters = accumulator.accumulatedDistanceMeters,
    )
    ```

  **5d. Destination Reset**:
  - Add method:
    ```kotlin
    fun resetDestination() {
        val dest = activeDestination.value ?: return
        viewModelScope.launch {
            destinationRepository.completeDestination(dest.id)
        }
    }
    ```

  **Must NOT do**:
  - Do NOT add GPS continuous tracking
  - Do NOT add offline geocoding cache
  - Do NOT change Room schema or entities
  - Do NOT change `DestinationRepository` — `completeDestination()` already exists and does what reset needs
  - Do NOT use Retrofit — use OkHttp directly as shown

  **Recommended Agent Profile**:
  - **Category**: `business-logic`
    - Reason: Complex ViewModel with multiple data flows, API integration, location services
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: This is ViewModel logic, not UI layout

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 2, 3 once Task 4 is done)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 6 (UI needs these ViewModel APIs)
  - **Blocked By**: Task 4 (needs `FusedLocationProviderClient` from LocationModule)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/ui/destination/DestinationViewModel.kt` — Full current file (92 lines). Every function here will be modified or extended.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationViewModel.kt:19-21` — Constructor injection pattern. Add `FusedLocationProviderClient` and `GeocodingService` here.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationViewModel.kt:30` — The hardcoded `startPoint` to replace.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationViewModel.kt:50-55` — `completeAndReset()` — same pattern for `resetDestination()`.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationViewModel.kt:65-77` — `confirmDestination()` — update to use `_startPoint.value` instead of `startPoint`.

  **API/Type References**:
  - `app/src/main/java/com/fbreco/data/repository/DestinationRepository.kt:52-57` — `completeDestination(id)` — already exists, used by reset.
  - `app/src/main/java/com/fbreco/data/entity/Destination.kt` — Entity shape with `accumulatedDistanceMeters`, `targetDistanceMeters`, `isActive`.
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:31-39` — companion `isRunning` and `currentSnapshot` StateFlows.

  **External References**:
  - Photon API: `https://photon.komoot.io/api/?q={query}&limit=5` — Returns GeoJSON FeatureCollection. `geometry.coordinates` is `[longitude, latitude]`. `properties` has `name`, `country`, `city`.
  - `kotlinx.coroutines.tasks.await` — Extension to convert Google Play Services `Task<T>` to suspend function. Available in `org.jetbrains.kotlinx:kotlinx-coroutines-play-services`.

  **IMPORTANT**: Need to add `kotlinx-coroutines-play-services` dependency for `await()`:
  - In `libs.versions.toml`: `coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutines-test" }` (same version as coroutines-test: 1.9.0)
  - In `app/build.gradle.kts`: `implementation(libs.coroutines.play.services)`
  - **OR** use the callback-based approach with `suspendCancellableCoroutine` to avoid the extra dependency. Either is fine — executor's choice.

  **WHY Each Reference Matters**:
  - `DestinationViewModel.kt:30` — This is THE line to change (Tokyo hardcode).
  - `DestinationViewModel.kt:19-21` — Constructor pattern for adding new injections.
  - `DestinationRepository.kt:52-57` — Confirms `completeDestination()` exists and handles deactivation. No new repo method needed for reset.
  - Photon API docs — Executor needs exact endpoint URL and response parsing format.

  **Acceptance Criteria**:

  - [ ] `startPoint` is a `StateFlow<Position>` (not a plain `val`)
  - [ ] ViewModel fetches current location on init, falls back to Tokyo on failure
  - [ ] `GeocodingService.kt` exists with `search(query): List<GeocodingResult>`
  - [ ] ViewModel exposes `searchQuery`, `searchResults`, `isSearching` StateFlows
  - [ ] `updateSearchQuery()`, `selectSearchResult()`, `clearSearch()` methods exist
  - [ ] Search is debounced at 300ms with minimum 2 character query
  - [ ] `resetDestination()` method exists, calls `completeDestination()`
  - [ ] `serviceSnapshot` or real-time distance flow is exposed for map consumption
  - [ ] `RideSnapshot` includes `accumulatorDistanceMeters` for unflushed distance tracking

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build succeeds with all ViewModel changes
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: All new code compiles correctly
    Evidence: Build output captured

  Scenario: Photon API returns valid results (unit test)
    Tool: Bash
    Preconditions: GeocodingService created
    Steps:
      1. If a test file for GeocodingService exists, run it:
         export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest --tests "com.fbreco.data.GeocodingServiceTest"
      2. Alternatively, verify the parseGeoJson logic with a sample JSON string test
    Expected Result: Tests pass
    Evidence: Test output captured
  ```

  **Commit**: YES (standalone or group with Task 6)
  - Message: `feat(destination): add current location, search, real-time progress, and reset`
  - Files: `DestinationViewModel.kt`, `GeocodingService.kt`, possibly `libs.versions.toml`, `build.gradle.kts`
  - Pre-commit: `./gradlew assembleDebug`

---

- [ ] 6. Update DestinationScreen UI — search bar, reset button, live progress marker, current location camera

  **What to do**:

  **6a. Camera starts at current location**:
  - Collect `startPoint` from ViewModel as StateFlow (it's now dynamic)
  - Use `LaunchedEffect` to move camera when `startPoint` changes:
    ```kotlin
    val startPoint by viewModel.startPoint.collectAsState()
    
    LaunchedEffect(startPoint) {
        cameraState.animateTo(CameraPosition(target = startPoint, zoom = 5.0))
    }
    ```
  - Change initial `firstPosition` to use a static Tokyo default (it will be immediately overridden by LaunchedEffect when real location arrives)

  **6b. Search Bar UI**:
  - Add a `SearchBar` or `OutlinedTextField` at the top of the Box (above the map), showing when no active destination:
    ```kotlin
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    ```
  - Show a Column with the TextField and LazyColumn for results:
    - TextField with `onValueChange = { viewModel.updateSearchQuery(it) }`
    - Results list: each item shows `name, city, country` → on click: `viewModel.selectSearchResult(result)` (which sets selectedPoint and triggers the existing confirmation dialog)
  - Hide search when active destination exists
  - Add `import androidx.compose.foundation.lazy.LazyColumn` and `items`
  - Add `import androidx.compose.material3.OutlinedTextField`
  - Add `import androidx.compose.material3.CircularProgressIndicator` for loading state

  **6c. Reset Button**:
  - In the `activeDestination?.let { dest -> ... }` card (lines 107-121), add a "リセット" (Reset) button:
    ```kotlin
    var showResetDialog by remember { mutableStateOf(false) }
    
    // Inside the card, add:
    TextButton(onClick = { showResetDialog = true }) {
        Text("目的地をリセット")
    }
    ```
  - Add a confirmation AlertDialog:
    ```kotlin
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("目的地のリセット") },
            text = { Text("現在の目的地をリセットしますか？進捗は失われます。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetDestination()
                    showResetDialog = false
                }) { Text("リセット") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("キャンセル") }
            },
        )
    }
    ```

  **6d. Live Progress Marker**:
  - When an active destination exists, compute a "current position" on the straight line:
    ```kotlin
    val snapshot by viewModel.serviceSnapshot.collectAsState()
    
    // Compute live progress position
    val currentPositionOnRoute = remember(startPoint, activeDestination, snapshot) {
        val dest = activeDestination ?: return@remember null
        val totalDistance = dest.accumulatedDistanceMeters + snapshot.accumulatorDistanceMeters
        val ratio = (totalDistance / dest.targetDistanceMeters).coerceIn(0.0, 1.0)
        val lat = startPoint.latitude + (dest.latitude - startPoint.latitude) * ratio
        val lng = startPoint.longitude + (dest.longitude - startPoint.longitude) * ratio
        Position(longitude = lng, latitude = lat)
    }
    ```
  - Add this position as a distinct CircleLayer (different color, e.g., green) or add it to the existing points GeoJSON with a property to distinguish it
  - Update `buildPointsGeoJson` to accept the current position marker, or add a separate GeoJSON source for the marker
  - Use a different color (e.g., `Color(0xFF00CC00)` green) and slightly larger radius to distinguish from start/end markers

  **6e. Real-time progress in card text**:
  - Update the progress card text to use real-time distance:
    ```kotlin
    val liveDistance = dest.accumulatedDistanceMeters + snapshot.accumulatorDistanceMeters
    val livePercent = (liveDistance / dest.targetDistanceMeters * 100.0).coerceIn(0.0, 100.0)
    Text(
        text = "${String.format("%.1f", liveDistance / 1000.0)} km / ..." +
            "(${String.format("%.0f", livePercent)}%)",
        ...
    )
    ```

  **Must NOT do**:
  - Do NOT add road-following route (straight line only)
  - Do NOT redesign the overall map layout or color scheme
  - Do NOT add complex map animations
  - Do NOT remove the long-press destination selection (keep both search AND long-press)

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Compose UI layout with map layers, search bar, dialogs
  - **Skills**: [`frontend-ui-ux`]
    - `frontend-ui-ux`: Composable layout, Material 3 components, UX for search flow
  - **Skills Evaluated but Omitted**:
    - `playwright`: Android native, no browser testing

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Tasks 2 and 5)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 7 (integration)
  - **Blocked By**: Task 2 (needs RideSnapshot for live marker), Task 5 (needs ViewModel APIs)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt` — Full current file (201 lines). This is the primary file to modify.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:43-48` — Camera initialization. Change to use `LaunchedEffect` for dynamic startPoint.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:50-56` — GeoJSON remember blocks. Add current position marker source.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:69-91` — MaplibreMap content with layers. Add new CircleLayer for progress marker.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:83-90` — Existing CircleLayer for points. Follow same pattern for progress marker layer.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:93-105` — Hint card when no destination. Replace/supplement with search UI.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:107-121` — Active destination progress card. Add reset button here.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:124-146` — Destination confirmation dialog pattern. Follow same style for reset dialog.
  - `app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt:172-200` — GeoJSON builder functions. Add/modify for progress marker.

  **API/Type References**:
  - `org.maplibre.compose.layers.CircleLayer` — Used at line 83. Same API for progress marker layer.
  - `org.maplibre.compose.sources.GeoJsonData.JsonString` — Used at line 70. Same for marker source.
  - `org.maplibre.compose.camera.CameraPosition` — Used at line 44. For `animateTo()`.

  **WHY Each Reference Matters**:
  - `DestinationScreen.kt:43-48` — Camera code to change for current location.
  - `DestinationScreen.kt:83-90` — Pattern for adding new CircleLayer (progress marker).
  - `DestinationScreen.kt:93-105` — Space to add search bar UI.
  - `DestinationScreen.kt:107-121` — Card where reset button goes.

  **Acceptance Criteria**:

  - [ ] Camera animates to device location when available (not hardcoded Tokyo)
  - [ ] Search bar visible when no active destination
  - [ ] Typing in search bar triggers Photon API (debounced 300ms)
  - [ ] Search results appear as list, tapping one sets selectedPoint and shows confirmation dialog
  - [ ] Active destination card has "目的地をリセット" button
  - [ ] Reset confirmation dialog works and clears the destination
  - [ ] Progress marker (green circle) visible on the route line at correct interpolated position
  - [ ] Progress card text shows real-time distance (not just DB-flushed)
  - [ ] Long-press destination selection still works when no active destination

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build succeeds with all UI changes
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: All Compose code compiles
    Evidence: Build output captured

  Scenario: All tests pass after UI changes
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: All tests PASS
    Expected Result: No regressions
    Evidence: Test output captured
  ```

  **Commit**: YES (group with Task 5)
  - Message: `feat(map): add search, reset, live progress marker, and current location`
  - Files: `DestinationScreen.kt`
  - Pre-commit: `./gradlew assembleDebug`

---

- [ ] 7. Final integration build, full test suite, and commit

  **What to do**:
  - Run full test suite: `./gradlew testDebugUnitTest`
  - Run debug build: `./gradlew assembleDebug`
  - Run release build: `./gradlew assembleRelease`
  - If any tests fail, fix them
  - Create final commit(s) for any remaining uncommitted changes
  - Push to remote: `git push origin main`

  **Must NOT do**:
  - Do NOT change any logic — only fix compilation/test issues if found
  - Do NOT add new features

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Build verification and git operations
  - **Skills**: [`git-master`]
    - `git-master`: Final commit and push operations
  - **Skills Evaluated but Omitted**:
    - All others — this is purely verification

  **Parallelization**:
  - **Can Run In Parallel**: NO (final task)
  - **Parallel Group**: Wave 4 (after all others)
  - **Blocks**: None (final)
  - **Blocked By**: Tasks 3, 6 (all implementation must be complete)

  **References**:

  **Pattern References**:
  - Previous commits in git log for commit message style
  - Build commands from project setup

  **WHY Each Reference Matters**:
  - Commit history — follow existing message format (`type(scope): description`)

  **Acceptance Criteria**:

  - [ ] `./gradlew testDebugUnitTest` → All tests PASS
  - [ ] `./gradlew assembleDebug` → BUILD SUCCESSFUL
  - [ ] `./gradlew assembleRelease` → BUILD SUCCESSFUL
  - [ ] All changes committed to git
  - [ ] Changes pushed to `origin/main`

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Full test suite passes
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: All tests PASS, 0 failures
    Expected Result: Complete green test suite
    Evidence: Test output captured

  Scenario: Release build succeeds
    Tool: Bash
    Steps:
      1. Run: export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleRelease
      2. Assert: BUILD SUCCESSFUL
    Expected Result: Release APK generated
    Evidence: Build output captured

  Scenario: All changes pushed
    Tool: Bash
    Steps:
      1. Run: git status
      2. Assert: "nothing to commit, working tree clean"
      3. Run: git log --oneline -5
      4. Assert: New commits visible
    Expected Result: Clean working tree, all commits pushed
    Evidence: Git output captured
  ```

  **Commit**: YES (final push)
  - Pre-commit: `./gradlew testDebugUnitTest && ./gradlew assembleRelease`

---

## Commit Strategy

| After Task(s) | Message | Key Files | Verification |
|---------------|---------|-----------|--------------|
| 1 | `fix(accumulator): use millis remainder to fix integer division time bug` | RideAccumulator.kt, RideAccumulatorTest.kt | testDebugUnitTest |
| 2 | `feat(service): add real-time RideSnapshot StateFlow for live UI updates` | BikeForegroundService.kt | assembleDebug |
| 3 | `feat(home): display real-time ride data from service StateFlow` | HomeViewModel.kt, HomeScreen.kt | assembleDebug |
| 4 | `feat(di): add play-services-location and LocationModule for current position` | libs.versions.toml, build.gradle.kts, LocationModule.kt | assembleDebug |
| 5+6 | `feat(map): add search, current location, live progress, and destination reset` | DestinationViewModel.kt, DestinationScreen.kt, GeocodingService.kt | assembleDebug |
| 7 | (no new commit — push only) | — | assembleRelease |

---

## Success Criteria

### Verification Commands
```bash
export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk"

# All unit tests pass
./gradlew testDebugUnitTest
# Expected: BUILD SUCCESSFUL, all tests pass

# Debug build succeeds
./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL

# Release build succeeds
./gradlew assembleRelease
# Expected: BUILD SUCCESSFUL
```

### Final Checklist
- [ ] Time accumulation: Sub-second BLE intervals correctly accumulate (unit test proves it)
- [ ] Home screen: Displays real-time time/distance when service is running
- [ ] Map: Camera starts at device location (Tokyo fallback)
- [ ] Map: Destination search via Photon API works
- [ ] Map: Live progress marker shows current position on route
- [ ] Map: Destination reset button with confirmation dialog
- [ ] All existing tests still pass
- [ ] No Room schema changes
- [ ] No server-side dependencies
- [ ] No paid API usage
- [ ] All changes pushed to `origin/main`
