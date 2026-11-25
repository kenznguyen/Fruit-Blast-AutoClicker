# Source-level dependency audit

## Baseline

- Upstream: https://github.com/Nain57/Smart-AutoClicker
- Audited ref: `699bff818519bc418addb7f0bdad5fbfb6c3f720`
- Upstream branch at audit: `master`
- License: GPL-3.0 (upstream `LICENSE`)
- This repository does not contain copied Klick'r source files as of this audit.

## Source-file findings

| Source file | Direct dependencies observed | Decision |
|---|---|---|
| `core/common/actions/.../gesture/GestureExecutor.kt` | Android AccessibilityService/GestureDescription/SystemClock; coroutines CompletableDeferred/timeout; Dumpable and crash-key extensions; BuiltGesture/HeldPointer; HeldPointerManager; UserTouchWatcher; gesture timeout/debouncer helpers | Do not copy as a unit. It relies on internal action models, lifecycle semantics, helper extensions and gesture callback accounting. Reimplement the smallest tap dispatch adapter after Android service design. |
| `.../gesture/HeldPointerManager.kt` | AccessibilityService/GestureDescription/SystemClock; HeldPointer model; local GestureDescription continuation extension | Exclude initially. Fruit Blast needs taps, not long-lived pointer chains. |
| `.../gesture/UserTouchWatcher.kt` | Android WindowManager/View/Handler; base safeAddView/safeRemoveView extensions | Exclude initially. It exists to protect held-pointer interactions; do not introduce its overlay/window lifecycle unless held gestures are later required. |
| `core/common/actions/.../AndroidActionExecutorImpl.kt` | GestureExecutor; action notification executor; text executor; Hilt; weak AccessibilityService reference; coroutine delay | Do not copy. It is a broad action facade containing notifications/text/global actions unrelated to the first Fruit Blast use case. |
| `core/common/display/.../recorder/DisplayRecorder.kt` | MediaProjection, VirtualDisplay, ImageReader, Bitmap; coroutine Mutex/delay; application Context; projection permission result | Do not copy as a unit. Reuse Android platform concepts only; implement a narrow capture owner with explicit start/stop and image close semantics. |
| `core/common/bitmaps/.../BitmapRepository.kt` | Bitmap and base Dumpable/file-extension contract | Do not copy interface; implement a minimal template asset loader only if required. |
| `core/common/bitmaps/.../BitmapRepositoryImpl.kt` | Android Bitmap; AndroidX bitmap helper; coroutine runBlocking; base dump helpers; DI | Exclude cache/DI layer initially. |
| `core/smart/detection/.../ImageDetector.kt` | Android Bitmap/Rect and AndroidX ColorInt; exposes image, color, OCR and number detection contract | Do not copy broad interface. Define a small template matcher contract for close/next/continue/game-home indicators. |
| `core/smart/detection/.../NativeDetector.kt` and `src/main/cpp/**` | JNI, CMake, OpenCV, NCNN, native image/text/color matching and model loading | Exclude. This is the main source of native build/dependency weight and includes OCR/model functionality not required initially. |
| `smartautoclicker/.../SmartAutoClickerService.kt` | Hilt; LocalAccessibilityServiceConnection; overlay, settings, quality, revenue, tutorial, debug, tile, review, database/domain engines | Do not copy. Build a small app-owned AccessibilityService with only lifecycle and foreground-window validation needed by this app. |

## Verified target application

- Fruit Blast package ID: `com.funnyvideo.cgame.fruitblast`
- Evidence: uploaded device ADB logs contain the package in the Android input-method `EditorInfo.packageName` record and the game process/app log records.
- The package ID is centralized in `FruitBlastConfig.TARGET_PACKAGE`.
- `ForegroundAppValidator` classifies exact matches as TARGET, other non-empty packages as OTHER, and null/blank values as UNKNOWN.
- The ad policy fails closed for UNKNOWN and requests the separate bounded recovery path for OTHER. This classifier does not itself launch or navigate to another app.

## FB-A03 — Android application and foreground adapter

- App module: `:app`
- Android application ID: `com.fruitblast.autoclicker`
- Namespace: `com.fruitblast.autoclicker`
- The app module uses Android Gradle Plugin 9.3.1 and compile SDK 37. The existing Kotlin/JVM root module and its policy test task are retained.
- The intended Gradle Wrapper version for FB-A03.1 is 9.5.0; wrapper files and executable verification remain pending.
- `FruitBlastAccessibilityService` is registered with `BIND_ACCESSIBILITY_SERVICE` and listens only for window-state/windows-changed events.
- Window-event package is diagnostic state only. The foreground gate reads `rootInActiveWindow` at point of use and fails closed if unavailable; it never falls back to stale event data.
- No gesture dispatch, wrong-app recovery, MediaProjection, OCR, template detection, or AI is implemented in this task.
- Android foreground validation is not yet runtime-verified. The point-of-use check is not an atomic OS foreground lock; a future dispatcher must re-check immediately before dispatch and still handle a foreground race safely.

## Minimal architecture selected

1. Pure Kotlin ad decision policy and exact-package foreground validator (implemented; test cases added, execution evidence pending).
2. Minimal Android app module and foreground-window AccessibilityService adapter (source added; Gradle/Android runtime verification pending).
3. A single MediaProjection capture owner, with deterministic release of ImageReader/Image/VirtualDisplay/MediaProjection resources.
4. A small template matcher for the configured Fruit Blast UI states; no OCR, OpenCV, NCNN or AI in the first implementation.
5. A bounded controller that consumes observations and emits at most one action per accepted frame. It must validate the foreground package immediately before dispatch, apply cooldown/retry limits, and stop on GAME_HOME/completion.
6. Wrong-app recovery must be bounded and must not launch arbitrary URLs or stores. It may return only to the explicitly configured Fruit Blast package through a verified app-launch path.

## Explicit non-goals

- No Smart-Ad-Skip integration.
- No AI/Qwen/native inference/Binder/watchdog.
- No scenario editor, database, backups, notifications, OCR, model packs, revenue, analytics or tutorial features.
- No long-held pointer gesture support in the first iteration.

## Verification boundary

The ad decision policy is platform-independent and can be verified with Kotlin/JVM tests. Android service binding, MediaProjection permission UX, frame acquisition/release, gesture dispatch and wrong-app recovery still require Android integration tests and real-device tests. A JVM policy PASS does not establish Android runtime readiness.

## Licensing

The upstream repository is GPL-3.0. No upstream implementation source has been copied into this repository yet. Before extracting any GPL-covered implementation, retain required copyright/license notices, mark modified files, and review the distribution/source-offer obligations for the resulting combined work.
