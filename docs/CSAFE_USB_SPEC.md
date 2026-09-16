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
* **Buffer Layout**: 121 bytes total:
  * Byte 0: Report ID (`0x02` for CSAFE protocol packets)
  * Bytes 1..120: CSAFE frame data (start byte, command payload, checksum, end byte, and zero-padding)

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

The app communicates with PM5 using standard CSAFE commands and Concept2 Proprietary Extension commands (prefixed by `0x1A` wrapper).

| Opcode | Mnemonic | Request Format | Response Format | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `0x80` | `CSAFE_GETSTATUS_CMD` | `[0x80]` | `[Status_Byte]` | Basic PM status flags |
| `0x81` | `CSAFE_RESET_CMD` | `[0x81]` | `[]` | Resets communication buffer |
| `0x85` | `CSAFE_GOINUSE_CMD` | `[0x85]` | `[]` | Places monitor into "In Use" state |
| `0xA7` | `CSAFE_GETCADENCE_CMD` | `[0xA7]` | `[0xA7, 2, SPM_lo, SPM_hi]` | Flywheel cadence (strokes per minute) |
| `0xB4` | `CSAFE_GETPOWER_CMD` | `[0xB4]` | `[0xB4, 2, Watts_lo, Watts_hi]` | Instantaneous mechanical power (Watts) |
| `0xB0` | `CSAFE_GETHRCUR_CMD` | `[0xB0]` | `[0xB0, 1, HR_bpm]` | Heart rate from chest strap |
| `0x1A 0xBF` | `CSAFE_PM_GET_STROKESTATE` | `[0x1A, 0x01, 0xBF, 0x00]` | `[0x1A, 0x02, 0xBF, 0x01, State]` | Current stroke phase (0=Wait, 1=Catch, 2=Drive, 3=Dwell, 4=Recovery) |
| `0x1A 0x6B` | `CSAFE_PM_GET_FORCEPLOTDATA`| `[0x1A, 0x02, 0x6B, 0x20]` | `[0x1A, len, 0x6B, count, d0_lo, d0_hi, ...]` | 16-bit little-endian handle force samples (Newtons) |
| `0x1A 0xA0` | `CSAFE_PM_GET_WORKTIME` | `[0x1A, 0x01, 0xA0, 0x00]` | `[0x1A, 0x05, 0xA0, b0, b1, b2, b3, frac]` | Elapsed workout time (10ms increments) |
| `0x1A 0xA3` | `CSAFE_PM_GET_WORKDISTANCE` | `[0x1A, 0x01, 0xA3, 0x00]` | `[0x1A, 0x05, 0xA3, b0, b1, b2, b3, frac]` | Cumulative workout distance (0.1m increments) |
| `0x1A 0xC1` | `CSAFE_PM_GET_DRAGFACTOR` | `[0x1A, 0x01, 0xC1, 0x00]` | `[0x1A, 0x02, 0xC1, 0x01, Drag]` | Measured aerodynamic flywheel drag factor |

---

## 4. Compound Telemetry Query

To minimize round-trip USB bus overhead, the logger bundles multiple queries into a single compound frame (`buildCombinedTelemetryCommand`):

```
Frame Payload:
  [0x1A, 0x01, 0xBF, 0x00]   <-- PM Stroke State
  [0xB4]                     <-- Power (Watts)
  [0xA7]                     <-- Cadence (SPM)
  [0xB0]                     <-- Heart Rate (bpm)
  [0x1A, 0x01, 0xA0, 0x00]   <-- Elapsed Work Time
  [0x1A, 0x01, 0xA3, 0x00]   <-- Workout Distance
  [0x1A, 0x01, 0xC1, 0x00]   <-- Drag Factor
```

The PM5 responds with a single unified response containing all requested parameters in a single USB IN transaction.

---

## 5. Force Plot Decoding Formula

When `CSAFE_PM_GET_FORCEPLOTDATA` is received:
1. `count` indicates the number of data bytes returned.
2. Every pair of bytes represents a discrete 16-bit handle force in Newtons:
   $$F_i = (B_{2i+1} \ll 8) \;|\; B_{2i}$$
3. Successive chunks are collected until `count == 0` or `count < 32`.
4. The resulting vector is stored directly as `forceMap: [Int]`.
