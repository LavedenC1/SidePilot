# Sideswipe Pilot

A **new, separate Android application** (package `com.laveden.sideswipepilot`) for external screen observation and optional KernelSU-assisted touch controls in Rocket League Sideswipe. It does **not** modify, patch, inject into, or read Sideswipe's memory. It is an experimental prototype, not a polished or validated competitive bot.

## Modes

- **Training**: observes the screen, applies a conservative ball-intercept policy in freeplay/bot games, explores a small action space, and persists tabular Q-values locally between sessions. There is no pretrained neural model and no claim of human-level gameplay.
- **Live assist**: uses the current observation to choose touches with exploration disabled. Live mode can interfere with your own touches and may violate game rules; avoid ranked or online competitive play.
- **Pause**: leaves screen capture running, but stops issuing actions.

Long-press the floating button to calibrate the car. Long-press a second time to calibrate the ball. Tap it to cycle TRAIN → LIVE → PAUSED → TRAIN. Stop the foreground service with **STOP PILOT** inside the app or the system's foreground-service manager.

## Supported features

1. Official Android **MediaProjection** screen capture, requested with visible user permission. No polling for Sideswipe's package name or fragile `Waiting for Sideswipe` loop.
2. RGBA processing at a longest-side resolution of **640 px**, adjustable scan interval 33/50/75 ms, with motion-gated circle-edge ball proposals, local refinement and periodic global reacquisition.
3. Car proposal combining visual green-ring geometry, color tracking, manually seeded spatial tracking and tiny frame-to-frame template matching. Works better when you calibrate your car in play.
4. **Experimental** boost-ring estimate from green pixels around the car and flip-availability inference from ground contact/ball contact. These are **not** reliable game-state flags, especially under special effects, customized cars or HUD changes.
5. Optional **OpenGL ES 3.1 compute shader** for parallel circle-edge candidate scoring, with automatic CPU fallback on unsupported drivers (and not guaranteed to improve latency on every GPU).
6. Root shell input through KernelSU's `su`, kept alive to avoid repeated root startup overhead; no accessibility-service trickery, memory patching, or packet injection.
7. Rotating-screen handling using `VirtualDisplay.resize()` and `setSurface()`, without re-consuming the single-use MediaProjection permission token on Android 14+.
8. Foreground service, notification, floating overlay, controls active outside the app.

## Requirements

- Android 10+ (`minSdk 29`, `targetSdk 35`), arm64 Android phone recommended.
- KernelSU root permission for the Pilot app, including **shell commands** via `su`.
- Floating windows / Display over other apps permission.
- MediaProjection screen-sharing permission; select **Entire screen** if offered, then open Sideswipe in landscape.
- Three-button Sideswipe HUD close to the reference recording. Current default approximate normalized coordinates: joystick 12.5%,79%; jump 67%,81%; boost 77.5%,81%; flip 93.5%,81.5%. Coordinates are editable in `RootController.java` to match changed layouts. `input swipe` / `input tap` are inherently slower than native multitouch and not suitable for precision high-rank gameplay.

## Build APK

Android Studio: **Open project → Sync Gradle → Build → Build APK(s)**. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and is debug-signed by Android Gradle Plugin.

Command line (JDK 17, Android SDK platform 35 and build tools 35, Gradle 8.10.2):

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub: push the project to a repository, open **Actions → Build Sideswipe Pilot APK → Run workflow** and download the APK artifact. You control where the source is uploaded.

## Screen recording analysis

The reference recording is **2340×1080, 60 FPS**, with frequent camera shifts, extreme boost streaks, bright goal effects, green boost/ability rings, a mostly grayscale ball and sometimes a blue glowing ball. That is why this build does **not** depend on a fixed ball color or assumed car texture. The stills were used as visual design references, **not** as supervised training data with annotated labels.

## Limitations / verification

This project contains an implementation but has **not been deployed or benchmarked on your KernelSU device**. The code was syntax parsed successfully; an Android SDK is required to run the actual APK build and device tests. Ball confidence is heuristic; boost is an approximate UI-ring measurement and flip reset is only an inference. GPU scanning may fall back to CPU; screen-reading overhead varies by device. Do not represent it as a fully trained neural agent or true memory injection.

If the button appears but nothing moves: approve KernelSU's root prompt, confirm the game's button locations, wait until both B and C confidence are high, and calibrate by long-pressing the floating button. If the screen stays portrait, open Sideswipe before calibrating. If you choose a single-app screen share, ensure **Sideswipe**, not Pilot, is selected.
# SidePilot
# SidePilot
# SidePilot
