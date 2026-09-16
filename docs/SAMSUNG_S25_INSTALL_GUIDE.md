# Samsung Galaxy S25+ Setup, Installation & ErgData Guide

This guide provides step-by-step instructions for installing and running the **PM5 Stroke Logger** on your **Samsung Galaxy S25+ (Android 15 / One UI 7)**, configuring Google Drive sync, and running **ErgData** simultaneously.

---

## 1. Hardware Requirements

To connect your Samsung Galaxy S25+ to the Concept2 PM5 monitor, you will need:
* **Option A (Recommended)**: A direct **USB-C to USB-B OTG Cable** (commonly sold as "USB-C to USB-B Printer / MIDI Cable").
* **Option B**: A standard **USB-C to USB-A OTG Adapter** paired with the standard Concept2 USB cable (USB-A to USB-B).

```
[Samsung Galaxy S25+] (USB-C) <====== OTG Cable ======> (USB-B) [Concept2 PM5 Monitor]
```

---

## 2. Installing the APK on your Samsung Galaxy S25+

Because this app is your private high-performance tool, you do not need the Google Play Store.

### Step 1: Download the APK
* Download `concept2-stroke-logger.apk` from the GitHub Release page (or save it to your Google Drive).

### Step 2: Allow Sideloading on Samsung One UI 7
When you tap the APK to install, Samsung One UI may show a security prompt:
1. Tap **Settings** on the prompt.
2. Toggle **Allow from this source** ON (for Google Drive or Chrome, whichever app you opened the APK with).
3. Return to the prompt and tap **Install**.

The app icon **"PM5 Stroke Logger"** will now appear on your home screen and app drawer.

---

## 3. Simultaneous Workflow: Running ErgData + PM5 Stroke Logger

You can run **both apps concurrently** during your workouts:

### Step-by-Step Rower Setup:
1. **Connect ErgData via Bluetooth (BLE)**:
   - Turn on the Concept2 PM5 monitor.
   - Open **ErgData** on your Samsung S25+.
   - Connect to your PM5 wirelessly via Bluetooth as you normally do.
2. **Connect PM5 Stroke Logger via USB**:
   - Plug the USB-C cable into your S25+ and into the back of the PM5.
   - A prompt will appear: *"Open PM5 Stroke Logger when this USB device is connected?"* $\rightarrow$ Tap **OK**.
3. **Start the Workout**:
   - In PM5 Stroke Logger, verify the top bar displays **`PM5 CONNECTED (USB)`**.
   - Tap the large green **`START WORKOUT`** button.
   - The indicator switches to **`● REC`** and the ongoing notification appears.
4. **Row Freely**:
   - You can leave PM5 Stroke Logger on screen to view the live 60 FPS force curve canvas and giant power tile.
   - **OR** you can switch back to ErgData to follow a workout interval.
   - **OR** you can turn off the phone screen and put it in the PM5 cradle.
   - The Android **Foreground Service** and **Wake Lock** ensure the USB data stream continues uninterrupted.

---

## 4. One-Tap Google Drive Sync to Your Computer

Once your rowing session is complete:
1. In PM5 Stroke Logger, tap the red **`FINISH & EXPORT`** button.
2. The summary dialog appears showing total strokes, average watts, cadence, and distance.
3. Tap **`☁ Upload to Google Drive`**.
4. The Samsung Share Sheet opens with Google Drive selected:
   - Select your target folder (e.g., `Google Drive/Rowing/Sessions/`).
   - Tap **Save**.
5. Both the lossless JSON session file (`ergomonitor-session-...json`) and the standard telemetry CSV file (`ergo-strokes-...csv`) are uploaded immediately.

---

## 5. Analyzing on Your Computer

Because Google Drive syncs automatically to your Mac:
1. Open your browser to the local `stroke-by-stroke-analyzer` workstation:
   ```
   file:///Users/laurentgrandidier/Documents/Perso/Sports/Rowing/AI%20Training%20coach/stroke-by-stroke-analyzer/index.html
   ```
2. Simply **drag and drop** the newly uploaded `.json` or `.csv` file directly into the browser window.
3. The analyzer instantly populates:
   - All 14 biomechanical diagnostic tabs.
   - Real-time handle force curves and impulse curves.
   - Catch quickness, leg drive ratio, finish hang, and symmetry index.
   - AI Coach biomechanical audit and Watts-based training prescriptions.
