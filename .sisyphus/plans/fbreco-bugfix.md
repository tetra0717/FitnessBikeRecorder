# FBReco — Critical Bug Fixes (Real-Device Testing)

## TL;DR

> **Quick Summary**: Fix 3 critical bugs found during real-device testing — foreground service never started (root cause of data not accumulating + no background persistence), missing POST_NOTIFICATIONS permission (Android 13+), and Mapbox crash due to missing runtime access token.
> 
> **Deliverables**:
> - Foreground service starts automatically after permissions granted and BLE connects
> - POST_NOTIFICATIONS permission declared and requested on Android 13+
> - Mapbox access token configured via gradle.properties → BuildConfig
> - All 3 bugs resolved, app functional on real device
> 
> **Estimated Effort**: Short (3 focused tasks)
> **Parallel Execution**: NO — sequential (service fix must come first)
> **Critical Path**: Task 1 (service + permissions) → Task 2 (Mapbox token) → Task 3 (build verify)

---

## Context

### Original Request
User tested FBReco on a real Android device and reported 3 bugs:
1. 目的地タブを開いた瞬間アプリがクラッシュ (Mapbox crash on destination tab)
2. 走行時間と走行距離が加算されない (Time/distance not accumulating despite pedaling detection)
3. 通知も来ていない、バックグラウンドの常駐ができていない、通知の権限の要求も来ていない (No notification, no background persistence, no notification permission request)

User confirmed: BLE device detection, connection, and pedaling detection all work correctly.

### Investigation Summary
**Bug 2+3 Root Cause (CONFIRMED)**: `BikeForegroundService` is fully implemented with all data collection, accumulation, DB writes, and notification logic — but **no code anywhere calls `startForegroundService()` or `startService()`**. The service is declared in `AndroidManifest.xml` with `foregroundServiceType="connectedDevice"` but never launched.

This single missing piece causes:
- No data accumulation → time/distance stay at 0
- No foreground notification → no background persistence
- The "pedaling detected" indicator works because `HomeViewModel` subscribes directly to `bleManager.bikeData` — but without the service, nothing writes to the database

Additionally, `POST_NOTIFICATIONS` (required on Android 13+ / API 33) is neither declared in manifest nor requested at runtime.

**Bug 1 Root Cause (CONFIRMED)**: No Mapbox runtime access token configured anywhere in the codebase. `MAPBOX_DOWNLOADS_TOKEN` in `gradle.properties` is empty. No `MapboxOptions.accessToken` call, no `mapbox_access_token` string resource, no manifest meta-data. The `MapboxMap` composable crashes during initialization.

### Metis Review
**Identified Gaps** (addressed):
- Foreground service timing on Android 12+ (ForegroundServiceStartNotAllowedException risk): Mitigated by starting only from foreground Activity context after permissions granted
- `foregroundServiceType` declaration: Already present in manifest as `connectedDevice` ✅
- Permission denial graceful degradation: Notification denied → service still runs but notification won't show (acceptable for MVP)
- Mapbox token missing in builds: Use BuildConfig injection with empty-check at runtime

---

## Work Objectives

### Core Objective
Fix all 3 critical bugs so the app works correctly on real devices — accumulates time/distance, runs in background with notification, and doesn't crash on the destination tab.

### Concrete Deliverables
- `MainActivity.kt` — starts `BikeForegroundService` after permissions are granted
- `PermissionScreen.kt` — adds `POST_NOTIFICATIONS` to permission request (API 33+)
- `AndroidManifest.xml` — declares `POST_NOTIFICATIONS` permission
- `FBRecoApplication.kt` — initializes Mapbox access token from BuildConfig
- `app/build.gradle.kts` — reads `MAPBOX_ACCESS_TOKEN` from `gradle.properties` into BuildConfig
- `gradle.properties` — has `MAPBOX_ACCESS_TOKEN` placeholder
- All existing 36 unit tests still pass
- Debug APK builds cleanly

### Definition of Done
- [ ] `./gradlew assembleDebug` succeeds
- [ ] `./gradlew testDebugUnitTest` passes (36 tests)
- [ ] `startForegroundService` is called in app code
- [ ] `POST_NOTIFICATIONS` is in manifest and runtime permission flow
- [ ] Mapbox token is configured via BuildConfig

### Must Have
- Service starts automatically when app opens (after permissions granted)
- POST_NOTIFICATIONS permission requested on Android 13+
- Mapbox token injected via build config (not hardcoded in source)

### Must NOT Have (Guardrails)
- NO new features beyond fixing these 3 bugs
- NO architectural changes (keep existing patterns)
- NO additional notification types or channels
- NO changes to BLE connection logic (it works correctly)
- NO changes to RideAccumulator or FtmsParser (they work correctly)
- NO Mapbox styling or feature changes beyond token config
- NO refactoring of PermissionScreen flow beyond adding the notification permission step

---

## Verification Strategy

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> ALL tasks in this plan MUST be verifiable WITHOUT any human action.

### Test Decision
- **Infrastructure exists**: YES (36 unit tests with JUnit + Mockito)
- **Automated tests**: Tests-after (no new unit tests needed — these are integration/wiring bugs, not logic bugs)
- **Framework**: JUnit 5 via `./gradlew testDebugUnitTest`

### Agent-Executed QA Scenarios (MANDATORY — ALL tasks)

Verification will use build commands and file-content assertions. BLE-dependent behavior requires real-device testing (recommended manual follow-up), but all code correctness can be verified by agents.

---

## Execution Strategy

### Sequential Execution (No Parallelism)

```
Task 1: Start foreground service + POST_NOTIFICATIONS permission
   ↓
Task 2: Configure Mapbox access token
   ↓  
Task 3: Final build verification + all tests pass
```

**Rationale**: Tasks are small and tightly coupled. Service must be startable before verifying the full flow. Mapbox is independent but benefits from a clean build after Task 1.

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|---------------------|
| 1 | None | 3 | 2 (could parallelize, but tasks are small) |
| 2 | None | 3 | 1 (could parallelize, but tasks are small) |
| 3 | 1, 2 | None | None (final verification) |

### Agent Dispatch Summary

| Order | Task | Recommended Agent |
|-------|------|-------------------|
| 1 | Start service + permissions | task(category="quick", load_skills=[], ...) |
| 2 | Mapbox token | task(category="quick", load_skills=[], ...) |
| 3 | Build verification | task(category="quick", load_skills=[], ...) |

---

## TODOs

- [x] 1. Start BikeForegroundService + Add POST_NOTIFICATIONS Permission

  **What to do**:

  **Part A — Start the Foreground Service from MainActivity:**

  In `MainActivity.kt`, after `permissionsGranted` becomes `true`, start `BikeForegroundService`. The service should start when the main navigation loads (meaning all permissions are granted and we're in the foreground).

  Modify `MainActivity.kt`:
  - Add an import for `android.content.Intent` and `com.fbreco.service.BikeForegroundService`
  - After the `if (permissionsGranted)` block becomes true, use a `LaunchedEffect(permissionsGranted)` to call:
    ```kotlin
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            val intent = Intent(this@MainActivity, BikeForegroundService::class.java)
            startForegroundService(intent)
        }
    }
    ```
  - Place this INSIDE `setContent { FBRecoTheme { ... } }` block, BEFORE the `if (permissionsGranted)` conditional that shows `FBRecoNavigation()`
  - `startForegroundService()` is safe here because: (a) we're in a foreground Activity, (b) permissions are granted, (c) Android 10+ `foregroundServiceType` is already declared in manifest

  **Part B — Add POST_NOTIFICATIONS to AndroidManifest.xml:**

  Add to `AndroidManifest.xml` (after line 9, with the other permissions):
  ```xml
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  ```

  **Part C — Add POST_NOTIFICATIONS to PermissionScreen.kt runtime request:**

  Modify the `requiredPermissions()` function in `PermissionScreen.kt`:
  - For `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` (API 33), add `Manifest.permission.POST_NOTIFICATIONS` to the array
  - The API 33 check should be nested inside the existing API 31 (S) check, since API 33 > API 31
  - Updated function should be:
    ```kotlin
    internal fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    ```

  **Must NOT do**:
  - Do NOT change the service's `onCreate()`, notification channel, or data collection logic — it's all correct
  - Do NOT add any new UI for notification permission explanation (the existing flow handles denied state)
  - Do NOT change BleManager or HomeViewModel — they work correctly
  - Do NOT stop/restart the service on BLE disconnect — the service should persist

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Small, focused edits to 3 files with clear instructions
  - **Skills**: `[]`
    - No special skills needed — straightforward Kotlin/Android edits
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: Not a UI task
    - `playwright`: Not a browser task

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (Task 1)
  - **Blocks**: Task 3 (build verification)
  - **Blocked By**: None

  **References**:

  **Pattern References** (existing code to follow):
  - `app/src/main/java/com/fbreco/MainActivity.kt:17-33` — Current Activity structure; add service start here using `LaunchedEffect` inside `setContent`
  - `app/src/main/java/com/fbreco/ui/PermissionScreen.kt:47-58` — Current `requiredPermissions()` function to modify with TIRAMISU check
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:56-68` — Service `onCreate()` that runs when started (creates notification channel, starts foreground, begins data collection)
  - `app/src/main/java/com/fbreco/service/BikeForegroundService.kt:106-117` — `startForegroundWithNotification()` already handles Android Q+ foregroundServiceType

  **API/Type References**:
  - `app/src/main/AndroidManifest.xml:30-33` — Service declaration with `foregroundServiceType="connectedDevice"` already present
  - `app/src/main/AndroidManifest.xml:8-9` — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` already declared

  **Acceptance Criteria**:

  > **AGENT-EXECUTABLE VERIFICATION ONLY**

  - [ ] `app/src/main/AndroidManifest.xml` contains `android.permission.POST_NOTIFICATIONS`
  - [ ] `app/src/main/java/com/fbreco/ui/PermissionScreen.kt` contains `Manifest.permission.POST_NOTIFICATIONS` with `TIRAMISU` API check
  - [ ] `app/src/main/java/com/fbreco/MainActivity.kt` contains `startForegroundService` call for `BikeForegroundService`
  - [ ] `./gradlew assembleDebug` → exit code 0

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Build succeeds after service start + permission changes
    Tool: Bash
    Preconditions: Android SDK configured, project builds cleanly before changes
    Steps:
      1. export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: exit code 0
      3. Assert: APK exists at app/build/outputs/apk/debug/app-debug.apk
    Expected Result: Clean build with no errors
    Evidence: Build output captured

  Scenario: POST_NOTIFICATIONS declared in manifest
    Tool: Bash (grep)
    Preconditions: AndroidManifest.xml modified
    Steps:
      1. grep "POST_NOTIFICATIONS" app/src/main/AndroidManifest.xml
      2. Assert: at least one match found
    Expected Result: Permission is declared
    Evidence: grep output

  Scenario: startForegroundService called in MainActivity
    Tool: Bash (grep)
    Preconditions: MainActivity.kt modified
    Steps:
      1. grep "startForegroundService" app/src/main/java/com/fbreco/MainActivity.kt
      2. Assert: at least one match found
    Expected Result: Service start code is present
    Evidence: grep output

  Scenario: Existing unit tests still pass
    Tool: Bash
    Preconditions: None
    Steps:
      1. export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: exit code 0
      3. Assert: output contains "BUILD SUCCESSFUL"
    Expected Result: All 36 tests pass
    Evidence: Test output captured
  ```

  **Commit**: YES
  - Message: `fix(service): start BikeForegroundService and add POST_NOTIFICATIONS permission`
  - Files: `MainActivity.kt`, `PermissionScreen.kt`, `AndroidManifest.xml`
  - Pre-commit: `./gradlew assembleDebug`

---

- [x] 2. Configure Mapbox Runtime Access Token

  **What to do**:

  **Part A — Add MAPBOX_ACCESS_TOKEN to gradle.properties:**

  In `gradle.properties`, add a new property (the user must fill in their actual token):
  ```properties
  MAPBOX_ACCESS_TOKEN=
  ```
  Note: The existing `MAPBOX_DOWNLOADS_TOKEN` is for downloading the SDK from Maven. `MAPBOX_ACCESS_TOKEN` is the PUBLIC runtime token for using the map.

  **Part B — Inject token into BuildConfig via app/build.gradle.kts:**

  In `app/build.gradle.kts`, inside the `android { defaultConfig { ... } }` block, add:
  ```kotlin
  buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"${project.findProperty("MAPBOX_ACCESS_TOKEN") ?: ""}\"")
  ```

  Also ensure `buildFeatures { buildConfig = true }` is set in the `android {}` block (it may already be there for Compose — check first, add only if missing).

  **Part C — Initialize Mapbox token in FBRecoApplication.kt:**

  Modify `FBRecoApplication.kt` to set the access token on app startup:
  ```kotlin
  package com.fbreco

  import android.app.Application
  import com.mapbox.maps.MapboxOptions
  import dagger.hilt.android.HiltAndroidApp

  @HiltAndroidApp
  class FBRecoApplication : Application() {
      override fun onCreate() {
          super.onCreate()
          val token = BuildConfig.MAPBOX_ACCESS_TOKEN
          if (token.isNotBlank()) {
              MapboxOptions.accessToken = token
          }
      }
  }
  ```

  **IMPORTANT**: The user MUST provide their own Mapbox public access token. The plan should add the property placeholder and wiring, but leave the actual token value empty. Add a comment in `gradle.properties` explaining this:
  ```properties
  # Mapbox public access token (get from https://account.mapbox.com/)
  MAPBOX_ACCESS_TOKEN=
  ```

  **Must NOT do**:
  - Do NOT hardcode any token in source code
  - Do NOT change DestinationScreen.kt, DestinationViewModel.kt, or any map UI code
  - Do NOT add error handling UI for missing token (if blank, map simply won't load — acceptable for MVP)
  - Do NOT change the `MAPBOX_DOWNLOADS_TOKEN` property (it's for a different purpose)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Small config changes across 3 files
  - **Skills**: `[]`
    - No special skills needed
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: Not a UI task

  **Parallelization**:
  - **Can Run In Parallel**: YES (independent of Task 1)
  - **Parallel Group**: Could run with Task 1, but sequential is fine given task size
  - **Blocks**: Task 3 (build verification)
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/fbreco/FBRecoApplication.kt:1-8` — Current Application class (just `@HiltAndroidApp` annotation, no `onCreate`)
  - `gradle.properties:1-end` — Current properties file with existing `MAPBOX_DOWNLOADS_TOKEN`
  - `app/build.gradle.kts` — Current build config; need to add `buildConfigField` in `defaultConfig` block

  **API/Type References**:
  - `com.mapbox.maps.MapboxOptions.accessToken` — Static property to set before any MapboxMap usage
  - `BuildConfig.MAPBOX_ACCESS_TOKEN` — Auto-generated by Gradle from `buildConfigField`

  **External References**:
  - Mapbox Android SDK docs: access token must be set before any map view is created, either via `MapboxOptions.accessToken` in Application class or via `mapbox_access_token` string resource

  **Acceptance Criteria**:

  > **AGENT-EXECUTABLE VERIFICATION ONLY**

  - [ ] `gradle.properties` contains `MAPBOX_ACCESS_TOKEN` property
  - [ ] `app/build.gradle.kts` contains `buildConfigField` for `MAPBOX_ACCESS_TOKEN`
  - [ ] `app/src/main/java/com/fbreco/FBRecoApplication.kt` contains `MapboxOptions.accessToken` assignment
  - [ ] `./gradlew assembleDebug` → exit code 0 (even with empty token — should compile)

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: BuildConfig field is generated for Mapbox token
    Tool: Bash
    Preconditions: build.gradle.kts modified with buildConfigField
    Steps:
      1. export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug
      2. Assert: exit code 0
      3. grep -r "MAPBOX_ACCESS_TOKEN" app/build/generated/source/buildConfig/
      4. Assert: BuildConfig.java contains the field
    Expected Result: Token field exists in generated BuildConfig
    Evidence: grep output + build output

  Scenario: FBRecoApplication initializes Mapbox token
    Tool: Bash (grep)
    Preconditions: FBRecoApplication.kt modified
    Steps:
      1. grep "MapboxOptions.accessToken" app/src/main/java/com/fbreco/FBRecoApplication.kt
      2. Assert: at least one match
      3. grep "BuildConfig.MAPBOX_ACCESS_TOKEN" app/src/main/java/com/fbreco/FBRecoApplication.kt
      4. Assert: at least one match
    Expected Result: Token initialization code is present
    Evidence: grep output

  Scenario: gradle.properties has token placeholder
    Tool: Bash (grep)
    Preconditions: gradle.properties modified
    Steps:
      1. grep "MAPBOX_ACCESS_TOKEN" gradle.properties
      2. Assert: at least one match
    Expected Result: Property exists (even if empty)
    Evidence: grep output
  ```

  **Commit**: YES
  - Message: `fix(mapbox): configure runtime access token via BuildConfig`
  - Files: `gradle.properties`, `app/build.gradle.kts`, `FBRecoApplication.kt`
  - Pre-commit: `./gradlew assembleDebug`

---

- [x] 3. Final Build Verification and Test Suite

  **What to do**:

  Run the complete build and test suite to verify all changes work together:
  1. Run `./gradlew clean assembleDebug` — full clean build
  2. Run `./gradlew testDebugUnitTest` — all 36 unit tests must pass
  3. Verify the APK exists at `app/build/outputs/apk/debug/app-debug.apk`

  If any test or build fails, investigate and fix the issue before marking complete.

  **Must NOT do**:
  - Do NOT introduce new code changes — only fix build/test issues if they arise
  - Do NOT skip any failing tests

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Pure verification task
  - **Skills**: `[]`
    - No special skills needed
  - **Skills Evaluated but Omitted**:
    - All — this is a build/verify task only

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (final)
  - **Blocks**: None (final task)
  - **Blocked By**: Task 1, Task 2

  **References**:

  **Pattern References**:
  - Previous build commands used throughout the project: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew assembleDebug`
  - Test command: `export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest`

  **Acceptance Criteria**:

  > **AGENT-EXECUTABLE VERIFICATION ONLY**

  - [ ] `./gradlew clean assembleDebug` → exit code 0
  - [ ] `./gradlew testDebugUnitTest` → exit code 0, all 36 tests pass
  - [ ] APK file exists at `app/build/outputs/apk/debug/app-debug.apk`

  **Agent-Executed QA Scenarios:**

  ```
  Scenario: Clean build succeeds
    Tool: Bash
    Preconditions: Tasks 1 and 2 completed
    Steps:
      1. export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew clean assembleDebug
      2. Assert: exit code 0
      3. Assert: output contains "BUILD SUCCESSFUL"
      4. ls -la app/build/outputs/apk/debug/app-debug.apk
      5. Assert: file exists
    Expected Result: Clean build produces debug APK
    Evidence: Build output + file listing

  Scenario: All unit tests pass
    Tool: Bash
    Preconditions: Clean build succeeded
    Steps:
      1. export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk" && ./gradlew testDebugUnitTest
      2. Assert: exit code 0
      3. Assert: output contains "BUILD SUCCESSFUL"
    Expected Result: All 36 tests pass
    Evidence: Test output captured
  ```

  **Commit**: NO (verification only — no code changes)

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 1 | `fix(service): start BikeForegroundService and add POST_NOTIFICATIONS permission` | MainActivity.kt, PermissionScreen.kt, AndroidManifest.xml | `./gradlew assembleDebug` |
| 2 | `fix(mapbox): configure runtime access token via BuildConfig` | gradle.properties, app/build.gradle.kts, FBRecoApplication.kt | `./gradlew assembleDebug` |
| 3 | (no commit — verification only) | — | `./gradlew clean assembleDebug && ./gradlew testDebugUnitTest` |

---

## Success Criteria

### Verification Commands
```bash
export ANDROID_HOME="C:\\Users\\tetra\\Android\\Sdk"
./gradlew clean assembleDebug     # Expected: BUILD SUCCESSFUL
./gradlew testDebugUnitTest       # Expected: BUILD SUCCESSFUL, 36 tests pass
```

### Final Checklist
- [ ] BikeForegroundService is started from MainActivity after permissions granted
- [ ] POST_NOTIFICATIONS declared in manifest
- [ ] POST_NOTIFICATIONS requested at runtime on API 33+
- [ ] Mapbox access token wired via BuildConfig
- [ ] All 36 existing unit tests pass
- [ ] Debug APK builds successfully
- [ ] No new features added (bug fixes only)

### Post-Fix: User Manual Testing Required
After deploying the fixed APK to the real device, the user should verify:
1. ✅ Notification appears when app is opened (after granting notification permission)
2. ✅ App persists in background (notification stays, service keeps running)
3. ✅ Time and distance accumulate while pedaling
4. ✅ Destination tab opens without crash (after providing Mapbox token in gradle.properties)
5. ✅ Data persists across app backgrounding/foregrounding

### IMPORTANT: Mapbox Token Required
The user MUST add their Mapbox public access token to `gradle.properties`:
```properties
MAPBOX_ACCESS_TOKEN=pk.xxxxx...
```
Get a token from https://account.mapbox.com/ — the destination map will not load without it.
