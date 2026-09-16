# Concept2 PM5 USB & CSAFE Protocol Specification

This document details the low-level framing, byte stuffing, checksum algorithms, and CSAFE opcodes used by the Concept2 Performance Monitor (PM3, PM4, PM5) over physical USB-HID connections.

---

## 1. USB Interface Architecture

* **Vendor ID (VID)**: `0x17A4` (Decimal `6052`)
* **Product IDs (PID)**:
  * PM5: `0x0003`
  * PM4: `0x0002`
  * PM3: `0x0001`
* **USB Class**: Human Interface Device (HID, Class `0x03`)
* **Interface**: Interface `0`
* **Buffer Layout & HID Report IDs**:
  * **Report ID `0x01` (21 bytes)**: Used for short commands ($\le 21$ bytes) such as basic status or single queries.
  * **Report ID `0x04` (63 bytes)**: Used for compound telemetry queries ($\le 63$ bytes).
  * **Report ID `0x02` (121 bytes)**: Used for large block transfers, specifically handle force curve buffers (`CSAFE_PM_GET_FORCEPLOTDATA`).
  * *Important Guardrail*: Sending oversized frames with mismatched Report IDs triggers PM5 firmware buffer overruns and hardware fault `Code 384-1`. Frames must use the exact size matching their Report ID.

---

## 2. CSAFE Framing & Byte-Stuffing Rules

Concept2 CSAFE frames use delimiter bytes to mark packet boundaries. Any byte inside the payload matching a delimiter must be escaped ("byte-stuffed") to prevent premature packet termination.

### 2.1 Delimiter Bytes
| Constant | Hex Value | Decimal | Description |
| :--- | :--- | :--- | :--- |
| `EXT_FRAME_START` | `0xF0` | 240 | Extended Frame Start delimiter |
| `FRAME_START` | `0xF1` | 241 | Standard Frame Start delimiter |
| `FRAME_END` | `0xF2` | 242 | Frame Stop / End delimiter |
| `FRAME_STUFF` | `0xF3` | 243 | Byte stuffing escape prefix |

### 2.2 Byte Stuffing Algorithm (Transmitter)
When packing commands, any byte in the range `0xF0` through `0xF3` (including the checksum byte) is converted to a two-byte sequence:
$$\text{Original Byte } B \longrightarrow [\,0xF3,\; (B \ \& \ 0x03)\,]$$

* `0xF0` $\longrightarrow$ `0xF3 0x00`
* `0xF1` $\longrightarrow$ `0xF3 0x01`
* `0xF2` $\longrightarrow$ `0xF3 0x02`
* `0xF3` $\longrightarrow$ `0xF3 0x03`

### 2.3 Checksum Calculation
The checksum is an **8-bit bitwise XOR** across all unescaped command bytes between `FRAME_START` and the checksum location:
$$\text{Checksum} = B_1 \oplus B_2 \oplus B_3 \oplus \dots \oplus B_N$$

If the resulting checksum falls in the range `0xF0..0xF3`, it is also escaped using the byte-stuffing rule before `FRAME_END` is appended.

---

## 3. CSAFE Command Reference Table

The app communicates with PM5 using standard CSAFE commands and Concept2 Proprietary Extension commands (grouped inside the `0x1A` wrapper).

| Opcode | Mnemonic | Request Format | Response Format | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `0x80` | `CSAFE_GETSTATUS_CMD` | `[0x80]` | `[Status_Byte]` | Basic PM status flags |
| `0x81` | `CSAFE_RESET_CMD` | `[0x81]` | `[]` | Resets communication buffer |
| `0x85` | `CSAFE_GOINUSE_CMD` | `[0x85]` | `[]` | Places monitor into "In Use" state |
| `0xA7` | `CSAFE_GETCADENCE_CMD` | `[0xA7]` | `[0xA7, 2, SPM_lo, SPM_hi]` | Flywheel cadence (strokes per minute) |
| `0xB4` | `CSAFE_GETPOWER_CMD` | `[0xB4]` | `[0xB4, 3, Watts_lo, Watts_hi, Units]` | Instantaneous mechanical power (Watts) |
| `0xB0` | `CSAFE_GETHRCUR_CMD` | `[0xB0]` | `[0xB0, 1, HR_bpm]` | Heart rate from chest strap |
| `0x1A 0xBF` | `CSAFE_PM_GET_STROKESTATE` | `[0x1A, 0x01, 0xBF]` | `[0x1A, len, 0xBF, 0x01, State]` | Current stroke phase (0=Wait, 1=Catch, 2=Drive, 3=Dwell, 4=Recovery) |
| `0x1A 0x6B` | `CSAFE_PM_GET_FORCEPLOTDATA`| `[0x1A, 0x03, 0x6B, 0x01, 0x20]` | `[0x1A, len, 0x6B, sublen, bytesRet, d0_lo, d0_hi, ...]` | 16-bit little-endian handle force samples (Newtons) |
| `0x1A 0xA0` | `CSAFE_PM_GET_WORKTIME` | `[0x1A, 0x01, 0xA0]` | `[0x1A, len, 0xA0, 0x05, b0, b1, b2, b3, frac]` | Elapsed workout time (10ms increments) |
| `0x1A 0xA3` | `CSAFE_PM_GET_WORKDISTANCE` | `[0x1A, 0x01, 0xA3]` | `[0x1A, len, 0xA3, 0x05, b0, b1, b2, b3, frac]` | Cumulative workout distance (0.1m increments) |
| `0x1A 0xC1` | `CSAFE_PM_GET_DRAGFACTOR` | `[0x1A, 0x01, 0xC1]` | `[0x1A, len, 0xC1, 0x01, Drag]` | Measured aerodynamic flywheel drag factor |

---

## 4. Compound Telemetry Query & Grouped Wrapper

To minimize USB bus overhead and match official PyRow / Concept2 firmware expectations, all proprietary queries are grouped inside a **single** `0x1A` wrapper:

```
Frame Payload (9 bytes total):
  [0xB4]                     <-- Power (Watts)
  [0xA7]                     <-- Cadence (SPM)
  [0xB0]                     <-- Heart Rate (bpm)
  [0x1A, 0x04,               <-- PM Proprietary Wrapper (4 subcommands follow)
   0xA0,                     <-- Elapsed Work Time
   0xA3,                     <-- Workout Distance
   0xBF,                     <-- PM Stroke State
   0xC1]                     <-- Drag Factor
```

### 4.1 Two-Tier Polling Architecture (ErgometerJS Parity)
To guarantee 100% parity with the working `stroke-by-stroke-analyzer` (which runs `ErgometerJS v0.8` by Tijmen van Gulik):
* **Fixed 121-Byte Report ID 0x02 Framing**: All HID commands sent over physical USB are packed into 121 bytes with Report ID `0x02` (`WRITE_BUF_SIZE = 121`, `REPORT_TYPE = 2`), padded with zeroes after `FRAME_END (0xF2)`.
* **High-Resolution Stroke State Tier**: During active rowing, the state machine issues the lightweight 6-byte query `[0xF1, 0x1A, 0x01, 0xBF, 0xA4, 0xF2]` every **35ms** (~28.5 Hz) without querying full telemetry.
* **Low-Resolution Telemetry Tier**: Polled every **250ms** during steady rowing or immediately upon stroke completion to update UI display metrics (Watts, SPM, HR, Time, Distance).
* **Idle Pacing**: When `strokeState == WAITING` (wheel stationary), the loop backs off to **400ms** to minimize battery and CPU usage.

---

## 5. Force Plot Decoding Formula & Post-Processing

When the stroke finishes (transition from `DRIVE` / `DWELL` to `RECOVERY`):
1. The app requests `CSAFE_PM_GET_FORCEPLOTDATA` in 32-byte chunks: `[0xF1, 0x1A, 0x03, 0x6B, 0x01, 0x20, 0x53, 0xF2]`.
2. PM5 responds with Report ID `0x02` (121 bytes).
3. The payload contains `[Status, 0x1A, wrapperLen, 0x6B, subcmdLen, bytesReturned, d0_lo, d0_hi, ...]`.
4. `bytesReturned` indicates the number of data bytes in this chunk (up to 32 bytes = 16 points).
5. Every pair of bytes represents a discrete 16-bit handle force sample in Newtons:
   $$F_i = (B_{2i+1} \ll 8) \;|\; B_{2i}$$
6. Chunks are collected until `bytesReturned < 32` (or fewer than 16 points).
7. **ErgometerJS Curve Trimming**: Trailing double zeroes are popped while preserving the single grounding point:
   $$\text{while}(\text{len} > 3 \land F_{-1} = 0 \land F_{-2} = 0) \implies \text{pop}()$$
8. Stored directly as `forceMap: [Int]` if $\text{len} \ge 4$.

