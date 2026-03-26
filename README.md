# Open-TM-P1

Open-source Android app for the **Thermal Master P1** USB thermal camera.  
No proprietary software required — connect the camera via USB OTG and get a fully featured live thermal viewer on your Android device.

> **Desktop reference driver**: This project is based on the protocol reverse-engineered by
> [jvdillon/p3-ir-camera](https://github.com/jvdillon/p3-ir-camera).  
> See that repository for the Python desktop driver, lock-in thermography, and detailed protocol documentation.

---

## Features

- **Live thermal view** — real-time 160×120 frames at ~25 fps via USB Host
- **5 colormaps** — Ironbow, Rainbow, Grayscale, Hot, Plasma
- **Temperature overlay** — center-point, min/max spot markers, and touch-to-measure
- **Gain control** — High gain (−20 °C to 150 °C) / Low gain (0 °C to 550 °C)
- **Shutter / NUC calibration** — one-tap non-uniformity correction
- **Screenshot** — save a PNG to your Gallery with a single tap
- **Auto-launch** — app opens automatically when the camera is plugged in

---

## Camera Specs (P1)

| Property     | Value              |
|--------------|--------------------|
| VID          | `0x3474` (13428)   |
| PID          | `0x45C2` (17858)   |
| Resolution   | 160 × 120          |
| Frame size   | 77,440 bytes       |
| Frame rate   | ~25 fps            |
| Interface    | USB 2.0 (Bulk IN)  |

---

## Requirements

- Android 5.0 (API 21) or higher
- USB OTG (On-The-Go) cable / adapter
- The Thermal Master P1 camera

---

## Build Instructions

### Prerequisites

- [Android Studio](https://developer.android.com/studio) Hedgehog (2023.1.1) or newer, **or** Android SDK + JDK 8+
- Gradle 8.4 (the wrapper will download it automatically)

### Build from command line

```bash
cd android
./gradlew assembleDebug
```

The APK is generated at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Install on device

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and click **Run**.

---

## Usage

1. Connect the camera to your Android device with a USB OTG cable.
2. Grant USB permission when prompted.
3. The live thermal view starts automatically.

### Controls

| Button       | Action                                         |
|--------------|------------------------------------------------|
| **Shutter**  | Trigger NUC (non-uniformity correction) click  |
| **Gain**     | Toggle High / Low gain mode                    |
| **Colormap** | Cycle through 5 colormaps                      |
| **Screenshot** | Save current view as PNG to Gallery          |
| **Touch**    | Tap any spot on the image to read its temperature |

---

## Project Structure

```
android/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/github/opentmp1/
│       │   │   ├── MainActivity.kt            # Activity, USB lifecycle, controls
│       │   │   ├── camera/
│       │   │   │   ├── ThermalCameraDriver.kt # USB Host driver (init, streaming, gain, shutter)
│       │   │   │   └── FrameParser.kt         # Frame decoding & temperature conversion
│       │   │   └── ui/
│       │   │       ├── ThermalView.kt         # Custom View — thermal image + overlays
│       │   │       └── ColormapManager.kt     # 5 colormap lookup tables
│       │   ├── res/
│       │   │   ├── layout/activity_main.xml
│       │   │   └── xml/device_filter.xml      # USB device filter (VID/PID)
│       │   └── AndroidManifest.xml
│       └── test/
│           └── java/com/github/opentmp1/
│               └── FrameParserTest.kt         # JUnit tests for frame parsing
├── build.gradle
└── settings.gradle
```

---

## Protocol

The app communicates with the camera using Android's USB Host API:

- **Control transfers** (`0x41 / 0xC1`) for initialization, gain, shutter
- **Bulk transfers** (`0x81`) for frame data
- **Frame layout**: start marker (12 B) + 77,440 B pixel data + end marker (12 B)
- **Temperature**: raw 16-bit value ÷ 64 − 273.15 = °C

Full protocol details are documented in  
[P3_PROTOCOL.md](https://github.com/jvdillon/p3-ir-camera/blob/main/P3_PROTOCOL.md)
(from the reference desktop driver repository).

---

## License

Apache 2.0
