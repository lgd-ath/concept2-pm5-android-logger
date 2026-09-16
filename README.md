# Concept2 PM5 Android Stroke-by-Stroke Data Logger

[![Build Concept2 PM5 Logger APK](https://github.com/lgd-ath/concept2-pm5-android-logger/actions/workflows/build-apk.yml/badge.svg)](https://github.com/lgd-ath/concept2-pm5-android-logger/actions/workflows/build-apk.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0+-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![Hardware](https://img.shields.io/badge/Hardware-Concept2%20PM5%20USB--OTG-blue.svg)](https://www.concept2.com)
[![Format](https://img.shields.io/badge/Compatible-Stroke--by--Stroke%20Analyzer-orange.svg)](https://github.com/lgd-ath/concept2-pm5-android-logger)

High-performance native Android application that connects directly to a **Concept2 Performance Monitor 5 (PM5)** via physical **USB-OTG** to record real-time stroke-by-stroke telemetry and discrete **handle force curve vectors (`forceMap`)** during workouts.

Outputs sessions in the exact same schema required by the **Concept2 Stroke-by-Stroke Analyzer**, enabling seamless post-workout biomechanical analysis.

---

## Key Features

* **High-Frequency CSAFE USB Engine (~35 Hz)**: Direct USB-HID endpoint communication reading real-time stroke states, instantaneous power (Watts), cadence (SPM), work time, and drag factor.
* **Full Discrete Force Curve Extraction**: Captures 16-bit handle force samples (Newtons) for every stroke drive phase with zero data truncation.
* **Dual Simultaneous Connections (ErgData Friendly)**: PM5 operates Bluetooth Low Energy (BLE) and USB simultaneously. You can run **ErgData** in the background or foreground to sync to the C2 Online Logbook while this app records stroke-by-stroke telemetry over USB-C OTG.
* **Uninterrupted Background Recording**: Employs an Android 15 `connectedDevice` Foreground Service and CPU `WakeLock` to prevent Samsung One UI / Android OS from terminating recording when the screen turns off or you switch apps.
* **One-Tap Google Drive Upload**: Directly uploads lossless JSON (`ergomonitor-session` v3) and telemetry CSV (`ergo-strokes.csv`) files to your Google Drive for immediate workstation analysis.
* **Athletic Jetpack Compose UI**: High-contrast dark cockpit featuring a 60 FPS vector force curve canvas with peak force marker and impulse calculation.

---

## Data Schema & Format Compatibility

The application exports two files simultaneously for every session:

### 1. JSON Session Backup (`ergomonitor-session` v3)
Directly loadable into `stroke-by-stroke-analyzer/index.html` via file drop or placed into `Saved sessions/`:
```json
{
  "format": "ergomonitor-session",
  "version": 3,
  "exportedAt": "2026-09-16T12:00:00.000Z",
  "title": "PM5 Session 2026-09-16 12:00",
  "phases": [],
  "strokes": [
    {
      "n": 1,
      "ts": 1726480800000,
      "tsMs": 0,
      "block": 1,
      "phase": 1,
      "watts": 210,
      "spm": 24,
      "driveMs": 720,
      "recovMs": 1780,
      "spmDerived": false,
      "drag": 125,
      "forceMap": [0, 14, 45, 110, 185, 210, 205, 160, 115, 60, 20, 0]
    }
  ]
}
```

### 2. Standard Telemetry CSV (`ergo-strokes.csv`)
Matches the analyzer's primary stroke table export format:
```csv
stroke,phase,block,time_ms,spm,spm_derived,watts,watts_derived,drive_ms,recov_ms,peak_force,peak_pct,avg_force,impulse,rfd,catch_pct,finish_pct,shape,forceMap
1,1,1,0,24,0,210,0,720,1780,210,48,135,97.2,1250,12,88,Symmetric,"[0 14 45 110 185 210 205 160 115 60 20 0]"
```

---

## Documentation

* [System Architecture & Concurrency Model](docs/ARCHITECTURE.md)
* [CSAFE USB Protocol Specification](docs/CSAFE_USB_SPEC.md)
* [Samsung Galaxy S25+ Installation & Setup Guide](docs/SAMSUNG_S25_INSTALL_GUIDE.md)

---

## Building from Source

### Prerequisites
* JDK 17
* Android SDK 35 (Android 15)

### Command-Line Build
```bash
./gradlew assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License

Internal high-performance athletic coaching tool developed for Laurent Grandidier.
All rights reserved.
