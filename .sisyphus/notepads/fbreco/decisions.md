## Decisions

## 2026-02-09 Spec Decisions
- App name: FBReco
- Distance: computed from speed × time (bike doesn't send distance field)
- Session model: 1 daily record, no manual start/stop
- Auto-detect: cadence > 0 OR power > 0
- Day boundary: midnight split in device timezone
- BLE: auto-reconnect, remember last device
- Background: Foreground Service mandatory
- Destination: 1 active at a time, free map selection, Mapbox SDK
- UI: Japanese only, Jetpack Compose, Material 3
- Storage: Room (SQLite), local only
- Guardrails: no cloud, no accounts, no PII, no speed/cadence/power storage

