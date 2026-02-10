# FBReco — BLE Fitness Bike Recorder Android App

## Overview
Android app that connects to a BLE FTMS fitness bike, automatically records cycling time and distance per day, shows daily/weekly summaries, and features a map-based destination progression system.

## Confirmed Specifications
- **App Name**: FBReco
- **Motif**: ながら運動を記録してモチベーション維持
- **Platform**: Android (Kotlin)
- **Architecture**: Local-only, no server, no accounts, no cloud sync
- **Protocol**: BLE FTMS only (Service UUID 0x1826, Indoor Bike Data 0x2AD2)
- **Data Recorded**: Daily total time + distance only (speed/cadence/power NOT stored)
- **Distance Calculation**: speed × time (bike does not send distance field)
- **Session Model**: 1 record per day, no manual session start/stop
- **Auto-detect**: cadence > 0 OR power > 0 → recording active
- **Day Boundary**: midnight (device timezone) splits records
- **BLE Connection**: auto-reconnect after initial pairing; remembered device
- **Background**: Foreground Service maintains BLE + recording when app is backgrounded
- **Destination**: 1 active destination at a time, free selection on map
- **Map SDK**: OpenStreetMap via Mapbox SDK
- **UI Language**: Japanese only
- **UI Framework**: Jetpack Compose
- **Storage**: Room (SQLite)
- **Tech Stack**: Kotlin, Jetpack Compose, Room, Android BLE API, Mapbox SDK, Foreground Service, Hilt (DI)

## Edge Case Decisions
- BLE disconnect during ride → reconnect automatically, accumulate from where left off
- Midnight crossing → split into two daily records
- Multiple FTMS devices in range → connect to last-paired device only
- App force-killed → Foreground Service restarts, resumes recording
- Corrupted BLE payloads → silently discard
- Destination changed mid-progress → reset cumulative distance for new destination
- No data export in MVP (future feature)

## Guardrails
- NO accounts, cloud sync, analytics, or PII
- NO manual session start/stop controls
- NO storage of speed/cadence/power (only daily aggregates)
- NO advanced map features (routing, turn-by-turn)
- NO multi-language support
- NO Wear OS integration
- NO graphs/charts beyond summary numbers in MVP

---

## Tasks

### Phase 1: Project Setup
- [x] 1.1 Initialize Android project with Gradle (Kotlin DSL), set applicationId=com.fbreco, minSdk=26, targetSdk=34, Jetpack Compose BOM, Room, Hilt, Mapbox SDK dependencies. Create basic project structure (app module). Keep existing main.py untouched.
  - parallelizable: none
  - files: build.gradle.kts (project + app), settings.gradle.kts, gradle.properties, gradle/libs.versions.toml, app/src/main/AndroidManifest.xml
  - verify: `./gradlew assembleDebug` succeeds

- [x] 1.2 Configure AndroidManifest.xml with BLE permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION), foreground service permission (FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE), and FBReco application class.
  - parallelizable: none (depends on 1.1)
  - files: app/src/main/AndroidManifest.xml, FBRecoApplication.kt
  - verify: `./gradlew assembleDebug` succeeds

### Phase 2: Data Layer (Room Database)
- [x] 2.1 Create Room entities: DailyRecord (date: LocalDate PK, totalTimeSeconds: Long, totalDistanceMeters: Double), Destination (id: Long PK, name: String, latitude: Double, longitude: Double, targetDistanceMeters: Double, accumulatedDistanceMeters: Double, isActive: Boolean, createdAt: LocalDateTime, completedAt: LocalDateTime?). Create type converters for LocalDate/LocalDateTime.
  - parallelizable: with 2.2
  - files: app/src/main/java/com/fbreco/data/entity/DailyRecord.kt, Destination.kt, Converters.kt
  - verify: compiles

- [x] 2.2 Create Room DAOs: DailyRecordDao (getByDate, getRange, getAll, upsert, getWeeklySummary), DestinationDao (getActive, insert, update, complete, deleteById). Create FBRecoDatabase with both entities.
  - parallelizable: with 2.1
  - files: app/src/main/java/com/fbreco/data/dao/DailyRecordDao.kt, DestinationDao.kt, FBRecoDatabase.kt
  - verify: compiles

- [x] 2.3 Create Repository layer: DailyRecordRepository (addRidingTime, getTodayRecord, getWeekRecords, getDateRange), DestinationRepository (getActiveDestination, createDestination, addDistance, completeDestination). Provide via Hilt module.
  - parallelizable: none (depends on 2.1, 2.2)
  - files: app/src/main/java/com/fbreco/data/repository/*.kt, app/src/main/java/com/fbreco/di/DatabaseModule.kt
  - verify: compiles

- [x] 2.4 Write unit tests for Repository and DAO logic (Room in-memory database tests). Test daily aggregation, midnight boundary split, weekly summary calculation.
  - parallelizable: none (depends on 2.3)
  - files: app/src/test/java/com/fbreco/data/**
  - verify: `./gradlew testDebugUnitTest` passes

### Phase 3: BLE Service Layer
- [x] 3.1 Create FtmsParser utility: parse Indoor Bike Data (0x2AD2) byte array → extract speed (km/h) and cadence (rpm) based on flags. Include isActive detection (cadence > 0 OR power > 0). Calculate distance increment from speed and notification interval.
  - parallelizable: yes (independent)
  - files: app/src/main/java/com/fbreco/ble/FtmsParser.kt
  - verify: unit tests pass

- [x] 3.2 Write unit tests for FtmsParser with known byte payloads from main.py reference. Test flag combinations, edge cases (zero speed, missing fields, corrupted data).
  - parallelizable: none (depends on 3.1)
  - files: app/src/test/java/com/fbreco/ble/FtmsParserTest.kt
  - verify: `./gradlew testDebugUnitTest` passes

- [x] 3.3 Create BleManager: scan for FTMS devices (filter by service UUID 0x1826), connect, subscribe to 0x2AD2 notifications, handle disconnect/reconnect with exponential backoff. Remember last connected device address in SharedPreferences. Expose connection state as StateFlow.
  - parallelizable: none (depends on 3.1)
  - files: app/src/main/java/com/fbreco/ble/BleManager.kt, BleConnectionState.kt
  - verify: compiles

- [x] 3.4 Create BikeForegroundService: Android Foreground Service that hosts BleManager, processes FTMS notifications via FtmsParser, accumulates time+distance, writes to DailyRecordRepository periodically (every 30s) and on stop. Show persistent notification with current status. Handle midnight boundary (check date on each write, create new DailyRecord if date changed). Auto-start on app launch, persist through backgrounding.
  - parallelizable: none (depends on 3.1, 3.3, 2.3)
  - files: app/src/main/java/com/fbreco/service/BikeForegroundService.kt
  - verify: compiles

- [x] 3.5 Write unit tests for distance calculation logic (speed × time interval accumulation) and midnight boundary handling.
  - parallelizable: none (depends on 3.4)
  - files: app/src/test/java/com/fbreco/service/AccumulatorTest.kt
  - verify: `./gradlew testDebugUnitTest` passes

### Phase 4: UI — Home Screen
- [x] 4.1 Create navigation structure with NavHost: 3 screens (Home, History, Destination). Bottom navigation bar with icons and Japanese labels. Material 3 theme.
  - parallelizable: yes (independent of Phase 3)
  - files: app/src/main/java/com/fbreco/ui/navigation/*.kt, app/src/main/java/com/fbreco/ui/theme/*.kt, MainActivity.kt
  - verify: `./gradlew assembleDebug` succeeds

- [x] 4.2 Create HomeScreen: show BLE connection status (接続中/未接続/スキャン中), today's total riding time (HH:MM:SS), today's total distance (X.XX km), active/inactive indicator. ViewModel reads from BikeForegroundService state + DailyRecordRepository.
  - parallelizable: none (depends on 4.1)
  - files: app/src/main/java/com/fbreco/ui/home/HomeScreen.kt, HomeViewModel.kt
  - verify: compiles

- [x] 4.3 Add BLE device pairing UI to HomeScreen: "バイクを探す" button that triggers scan, shows found FTMS devices in a dialog, tap to connect and remember. Show "接続済み: [device name]" when connected.
  - parallelizable: none (depends on 4.2)
  - files: app/src/main/java/com/fbreco/ui/home/HomeScreen.kt (update), ScanDialog.kt
  - verify: compiles

### Phase 5: UI — History Screen
- [x] 5.1 Create HistoryScreen with two tabs: 日別 (daily) and 週別 (weekly). Daily tab shows list of DailyRecords (date, time, distance) sorted by date descending. Weekly tab shows aggregated totals per week. ViewModel reads from DailyRecordRepository.
  - parallelizable: with Phase 4 tasks
  - files: app/src/main/java/com/fbreco/ui/history/HistoryScreen.kt, HistoryViewModel.kt
  - verify: compiles

### Phase 6: UI — Destination Screen (Map)
- [x] 6.1 Integrate Mapbox SDK: add MapView composable, configure with Japanese locale, show user's start point (Tokyo default or configurable). Display "目的地を設定" prompt when no active destination.
  - parallelizable: yes (independent)
  - files: app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt, DestinationViewModel.kt
  - verify: compiles with Mapbox

- [x] 6.2 Implement destination selection: long-press on map to drop pin, show confirmation dialog with calculated straight-line distance from start point, "この場所を目的地にする" button. Save to DestinationRepository.
  - parallelizable: none (depends on 6.1)
  - files: app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt (update)
  - verify: compiles

- [x] 6.3 Implement progress visualization: show start point marker, destination marker, and a line between them. Show progress indicator (filled portion of line based on accumulated/target distance ratio). Display "X.X km / Y.Y km (Z%)" text overlay. When 100% reached, show completion celebration and prompt for new destination.
  - parallelizable: none (depends on 6.2)
  - files: app/src/main/java/com/fbreco/ui/destination/DestinationScreen.kt (update), DestinationViewModel.kt (update)
  - verify: compiles

### Phase 7: Integration & Polish
- [x] 7.1 Wire BikeForegroundService destination updates: when daily distance is recorded, also add to active destination's accumulated distance via DestinationRepository.
  - parallelizable: none (depends on 3.4, 6.3)
  - files: app/src/main/java/com/fbreco/service/BikeForegroundService.kt (update)
  - verify: compiles

- [x] 7.2 Add permission request flow on first launch: request BLE + location permissions with Japanese explanation dialogs. Handle permission denied gracefully. Add battery optimization whitelist prompt.
  - parallelizable: with 7.1
  - files: app/src/main/java/com/fbreco/ui/PermissionScreen.kt, MainActivity.kt (update)
  - verify: compiles

- [x] 7.3 Final integration test: full build, verify all screens compile, run all unit tests, verify APK can be built.
  - parallelizable: none (depends on all above)
  - files: none (verification only)
  - verify: `./gradlew assembleDebug` succeeds AND `./gradlew testDebugUnitTest` passes
