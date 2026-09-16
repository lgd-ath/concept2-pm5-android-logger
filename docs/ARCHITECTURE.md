# Concept2 PM5 Android Stroke Logger: System Architecture & Technical Specification

## 1. Architectural Overview

The **Concept2 PM5 Android Stroke Logger** is an enterprise-grade Android application designed to acquire high-frequency rowing telemetry and discrete handle force curves (`forceMap`) from Concept2 Performance Monitors (PM5, PM4, PM3) via physical **USB-OTG**.

The system is engineered specifically to run seamlessly on modern Android devices, including the **Samsung Galaxy S25+ (Android 15 / One UI 7)**, while simultaneously allowing **ErgData** or media applications to run concurrently without interference or stroke loss.

```
+---------------------------------------------------------------------------------------+
|                               Samsung Galaxy S25+                                     |
|                                                                                       |
|  +-------------------------------------+     +-------------------------------------+  |
|  |             ErgData App             |     |    PM5 USB Stroke Logger App        |  |
|  |     (Foreground or Background)      |     |  +-------------------------------+  |  |
|  |                                     |     |  |   Jetpack Compose UI Layer    |  |  |
|  |  - C2 Online Logbook Sync           |     |  |  - Live Force Curve Canvas    |  |  |
|  |  - Workout programming              |     |  |  - Big Watts / SPM Gauges     |  |  |
|  |                                     |     |  +---------------^---------------+  |  |
|  +------------------+------------------+     +------------------|------------------+  |
|                     |                                           | StateFlow           |
|                     |                        +------------------v------------------+  |
|                     |                        |   UsbForegroundService              |  |
|                     |                        |  - Type: "connectedDevice"          |  |
|                     |                        |  - Partial WakeLock                 |  |
|                     |                        |  - Sticky Ongoing Notification      |  |
|                     |                        |  +-------------------------------+  |  |
|                     |                        |  | StrokeStateMachine (35 Hz)   |  |  |
|                     |                        |  +---------------^---------------+  |  |
|                     |                        |  | CsafeProtocol & UsbTransport  |  |  |
|                     |                        |  +-------------------------------+  |  |
|                     |                        +------------------|------------------+  |
|                     |                                           |                     |
|          Android Bluetooth Stack                     Android USB Host Subsystem       |
|            (BluetoothGatt)                              (UsbManager / UsbDevice)      |
+---------------------|-------------------------------------------|---------------------+
                      | Bluetooth Low Energy                      | Physical USB-OTG
                      | Wireless Radio                            | High-Speed Wired Cable
+---------------------v-------------------------------------------v---------------------+
|                                 Concept2 PM5 Monitor                                  |
|                                                                                       |
|  +-------------------------------------+     +-------------------------------------+  |
|  |      Nordic Semiconductor BLE       |     |     Microchip USB HID Controller    |  |
|  |  - Characteristic: 0xCE060031       |     |  - Vendor ID: 0x17A4 (6052)         |  |
|  |  - Characteristic: 0xCE060035       |     |  - Product ID: 0x0003 (PM5)         |  |
|  +-------------------------------------+     +-------------------------------------+  |
|                                                                                       |
|                     Flywheel Sensor & PM5 Real-Time DSP Firmware                      |
+---------------------------------------------------------------------------------------+
```

---

## 2. Independent Subsystems & ErgData Concurrency

### 2.1 The Two-Controller Architecture of Concept2 PM5
A critical design requirement is running **ErgData** concurrently with this stroke logger. Concept2 PM5 monitors are architecturally split into two independent hardware controllers:
1. **Nordic Semiconductor BLE SoC**: Manages the 2.4 GHz Bluetooth Low Energy radio, advertising standard Concept2 GATT services (`0xCE060030...`).
2. **Atmel/Microchip USB Microcontroller**: Manages the physical USB-B port, advertising standard USB-HID endpoint communication with Vendor ID `0x17A4` (`6052`).

Because these interfaces run on dedicated physical controllers in the PM5, the firmware processes requests on both interfaces simultaneously without contention.

### 2.2 Android OS Subsystem Isolation
On Android 15 (Samsung One UI 7):
- ErgData communicates with the BLE controller via `android.bluetooth.BluetoothGatt`.
- Our logger communicates with the USB controller via `android.hardware.usb.UsbManager`.

The Android Linux kernel isolates USB Host endpoint buffers from the Bluetooth HCI daemon. Neither app can monopolize or lock out the other.

---

## 3. Background Persistence & Power Management

Mobile operating systems—particularly Samsung One UI—aggressively throttle or terminate background processes to conserve battery. To guarantee zero dropped strokes, the app uses a multi-layered persistence architecture:

### 3.1 Android 14/15 `connectedDevice` Foreground Service
Under Android 14 and 15, services running in the background without explicit types are terminated within seconds. The app declares:
```xml
<service
    android:name=".service.UsbForegroundService"
    android:foregroundServiceType="connectedDevice" />
```
This grants official operating system permission to maintain an uninterrupted communication loop with physical external hardware.

### 3.2 Ongoing Status Bar Notification
The service maintains an active foreground notification displaying real-time workout metrics (`Power: 210 W | SPM: 24 | Strokes: 142`). This prevents the process priority from dropping into cached/background tiers.

### 3.3 PowerManager Partial WakeLock
When a workout session begins (`startRecording()`), the service acquires a `PARTIAL_WAKE_LOCK`:
```kotlin
wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Concept2StrokeLogger::RecordingWakeLock")
wakeLock.acquire(3 * 60 * 60 * 1000L) // 3-hour safety timeout
```
This keeps the CPU active even when the athlete locks the screen or places the phone in the rowing machine cradle with the display off. When the workout finishes, the wake lock is immediately released.

---

## 4. Telemetry Pipeline & Finite State Machine

The heart of the application is the `StrokeStateMachine`, which runs at **~35 Hz** (28 ms interval) on Kotlin Coroutines (`Dispatchers.IO`).

### 4.1 State Machine Lifecycle
```
                   +---------------------------+
                   |  0: WAITING_FOR_MIN_SPEED |
                   +-------------+-------------+
                                 | Flywheel accelerates
                                 v
                   +---------------------------+
         +-------->|         1: CATCH          |
         |         +-------------+-------------+
         |                       | Acceleration exceeds threshold
         |                       v
         |         +---------------------------+
         |         |         2: DRIVE          | <------+
         |         +-------------+-------------+        | Drive continues
         |                       | Force drops below peak | (State 2 or 3)
         |                       v                      |
         |         +---------------------------+        |
         |         |         3: DWELL          |--------+
         |         +-------------+-------------+
         |                       |
         |                       | Drive finishes -> State = 4
         |                       v
         |         +---------------------------+
         |         |        4: RECOVERY        |
         |         |                           |
         |         | 1. Query Force Plot (0x6B)|
         |         | 2. Query Kinematics (0xB4)|
         |         | 3. Assemble Stroke Model  |
         |         | 4. Emit to StateFlow / UI |
         |         +-------------+-------------+
         |                       |
         +-----------------------+ Next drive catch begins
```

### 4.2 Multi-Packet Force Plot Streaming
While standard telemetry (Watts, SPM, elapsed time) requires a single query, the handle force curve consists of 30–80 discrete 16-bit integers (Newtons). 

Because USB HID packets are capped at 120 payload bytes, the PM5 transmits force samples in chunks of up to 32 bytes (16 samples) per request. The state machine issues successive `CSAFE_PM_GET_FORCEPLOTDATA` calls until the monitor returns fewer than 32 bytes or 0 bytes, guaranteeing the full handle profile is captured without truncation.

---

## 5. Exporter Compatibility with `stroke-by-stroke-analyzer`

The export engine generates two files simultaneously on session completion:
1. **JSON Session File (`ergomonitor-session` v3)**:
   - Root keys: `format`, `version: 3`, `exportedAt`, `title`, `phases`, `strokes`.
   - Stroke keys: `n`, `ts`, `tsMs`, `block`, `phase`, `watts`, `spm`, `driveMs`, `recovMs`, `drag`, `spmDerived`, `forceMap`.
   - **Ingestion**: Drag and drop directly into `stroke-by-stroke-analyzer/index.html` or place into `Saved sessions/`.
2. **CSV Stroke Table (`ergo-strokes.csv`)**:
   - Headers: `stroke,phase,block,time_ms,spm,spm_derived,watts,watts_derived,drive_ms,recov_ms,peak_force,peak_pct,avg_force,impulse,rfd,catch_pct,finish_pct,shape,forceMap`.
   - Contains formatted vector: `"[0 14 45 110 ... 0]"`.
   - **Ingestion**: Drag and drop directly onto the analyzer's CSV drop zone.
