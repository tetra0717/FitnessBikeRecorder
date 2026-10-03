# Verification — 2026-10-03

## Completed locally

- Windows desktop Release build: no warnings or errors.
- Self-contained Windows x64 single-file publish (.NET 10 runtime included).
- Packaged EXE startup/exit smoke test (`--smoke-test`, no pairing or startup settings changed).
- 45 desktop checks: 5-second grace, 10-second smooth fade, 80% cap, 1-second restoration, pause expiry, unknown-state clearing; shared Android authentication vectors; wrong key/session/phone, tampering and replay rejection; DNS-SD records across separate responses, TTL expiry and goodbye; native layered/click-through/non-activation/tool-window styles and actual alpha after showing; live WebSocket authentication, client heartbeat, 5-second stale-state timeout and automatic reconnect.
- Android debug APK built as versionCode 2 / versionName 1.1.
- 44 Android unit tests, zero failures or errors, including existing recording tests and new protocol/FTMS field-presence tests.
- Android `lintDebug`: completed successfully. Existing `NullSafeMutableLiveData` suppression remains; no new lint rule is disabled. Lint was independently updated to 8.8.2 for compatibility with the transitive Compose runtime.
- Settings dialog rendered and inspected to ensure instructions, controls, status and buttons fit.
- PowerShell installer/uninstaller scripts parsed successfully. Installation and startup registration are supplied for the user to run; no startup registration was changed on the development PC.

The APK uses the pre-existing Android debug signing key on this PC. It can update an existing installation only if that installation has the same certificate. Production distribution should use the original release signing key.

## Requires physical-device verification

- Real Android NSD discovery over the user's router/Wi-Fi and any Windows Firewall prompt.
- Real Bluetooth bike data, especially notification frequency while stopped.
- Smartphone screen-off operation and manufacturer battery-management behavior.
- Actual multiple-monitor configurations, HDR, screen sharing and exclusive fullscreen games.
- Startup at the next Windows sign-in after running Install.cmd.

These were not simulated as completed real-device checks. The automated network integration uses a local WebSocket peer; discovery record handling and native window flags are tested separately.
