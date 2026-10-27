# YSLAM Baseline Report (Phase 0)

**Date & Time:** 2026-10-27T04:05:30-07:00  
**Environment:** Headless Cloud Container (Gradle 9.3.1 / OpenJDK 17 / No ADB directly accessible)

---

## 1. Build Verification (`gradle :app:assembleDebug`)
- **Command:** `gradle :app:assembleDebug`
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Duration:** 4s
- **Status:** Compiles and packages `app-debug.apk` without errors.

---

## 2. Unit & Robolectric Tests (`gradle :app:testDebugUnitTest`)
- **Command:** `gradle :app:testDebugUnitTest`
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Total Tests:** 3
- **Passed:** 3
- **Failed:** 0
- **Skipped:** 0
- **Test Details:**
  - `com.example.ExampleRobolectricTest.read string from context`: PASSED (3.82s)
  - `com.example.ExampleRobolectricTest.verify html asset exists`: PASSED (0.111s)
  - `com.example.ExampleUnitTest.addition_isCorrect`: PASSED (0.002s)

---

## 3. Targeted Inventory Grep (15 Checkpoints)

| Target Identifier | Hits | Files Matched |
|---|---|---|
| `gemini-3.5-flash` | **6** | `app/src/main/assets/index.html`, `index.html` |
| `gemini-3.1-pro-preview` | **4** | `app/src/main/assets/index.html`, `index.html` |
| `6 Models Available` | **2** | `app/src/main/assets/index.html`, `index.html` |
| `installedModels = [` | **0** | Clean (dynamic/empty array initialized) |
| `"2ms"` | **0** | Clean (no hardcoded latency in port matrix) |
| `"1ms"` | **0** | Clean (no hardcoded latency in port matrix) |
| `"0.342"` | **0** | Clean (fake attention entropy removed) |
| `"98.4%"` | **0** | Clean (fake hit rate removed) |
| `performance.now() % 8` | **2** | `app/src/main/assets/index.html`, `index.html` (in `testPipelineWorkflow`) |
| `"Simulator Ready"` | **0** | Clean (no simulated ready status) |
| `localStorage.setItem('yslam_gemini_key'` | **2** | `app/src/main/assets/index.html`, `index.html` (plaintext localStorage storage) |
| `usesCleartextTraffic="true"` | **1** | `app/src/main/AndroidManifest.xml` (global cleartext allowed) |
| `MANAGE_EXTERNAL_STORAGE` | **6** | `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/MainActivity.kt`, `index.html`, `app/src/main/assets/index.html` |
| `firebase-appcheck-recaptcha` | **1** | `gradle/libs.versions.toml` |
| `agp = "9.1.1"` | **1** | `gradle/libs.versions.toml` |

---

## 4. Emulator & Device Screenshot Note
- Per platform execution constraints, **no ADB daemon is exposed or running locally** inside the cloud build container (`There is NO Android Emulator or ADB available to you in your execution environment`).
- Live display output is hosted via the browser-streamed emulator on the UI surface.

---

## Phase 0 Status
**Baseline Established:** Phase 0 complete. Awaiting review before proceeding to Phase 1.
