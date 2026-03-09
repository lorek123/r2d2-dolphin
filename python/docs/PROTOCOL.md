# R2D2 Protocol Documentation

## Overview

The R2D2 robot (NanoPi running Android) consists of two communication layers:

1. **External API** — phone/tablet communicates with the Android app via WebSocket (WiFi) or Bluetooth
2. **Internal Serial Protocol** — Android app communicates with the MCU (motor controller) via UART at `/dev/ttyS2`

All messages in both layers are **newline-terminated JSON strings**.

---

## 1. Internal Serial Protocol (Android ↔ MCU)

### Physical Layer

| Parameter  | Value          |
|------------|----------------|
| Device     | `/dev/ttyS2`   |
| Baud rate  | 115200         |
| Data bits  | 8              |
| Stop bits  | 1              |
| Parity     | None           |
| Terminator | `\n`           |

---

### 1.1 Commands (Android → MCU)

#### `ready` — Software ready signal
Sent at startup to signal the MCU that the Android software is running.
```json
{"cmd": "ready"}
```

#### `move` — Drive motors
Move the robot body. Blocked when charging.

| Field   | Type | Description                          |
|---------|------|--------------------------------------|
| `power` | int  | Motor power (0 = stop)               |
| `angle` | int  | Direction in degrees (0=fwd, 180=bck)|

```json
{"cmd": "move", "power": 50, "angle": 0}
```

Common angle values:
- `0` — forward
- `180` — backward
- Other values — turning (exact mapping is MCU-defined)

#### `head-angle` — Rotate head to absolute position
Blocked when charging.

| Field   | Type | Description              |
|---------|------|--------------------------|
| `angle` | int  | Target angle in degrees  |

```json
{"cmd": "head-angle", "angle": -40}
```

#### `head-shift` — Shift head by relative angle
Blocked when charging. Used by face-tracking to nudge the head incrementally (±5° per frame).

```json
{"cmd": "head-shift", "angle": 3}
```

#### `head-dir` — Continuous head direction
Blocked when charging.

| Field | Type | Description       |
|-------|------|-------------------|
| `dir` | int  | Direction value   |

```json
{"cmd": "head-dir", "dir": 1}
```

#### `mode` — MCU behavior mode
Triggers predefined behaviors on the MCU.

| Mode | Behavior        |
|------|-----------------|
| 0    | Stop            |
| 2    | Turn around     |
| 3    | Turn left       |
| 4    | Turn right      |
| 5    | Go forward      |
| 9    | Patrol (autonomous navigation) |
| 12   | Walk circle     |
| 13   | Flash front LCD |
| 14   | Flash back LCD  |

```json
{"cmd": "mode", "mode": 5}
```

#### `projector` — Projector control

| Mode | Behavior  |
|------|-----------|
| 0    | Off       |
| 1    | Projector 1 (plays sound 100) |
| 2    | Projector 2 (plays sound 101) |

```json
{"cmd": "projector", "mode": 1}
```

#### `arm` — Arm extension

| Power | Behavior |
|-------|----------|
| 0     | Retract  |
| 1     | Extend   |

```json
{"cmd": "arm", "power": 1}
```

#### `lightsaber` — Lightsaber

| Power | Behavior |
|-------|----------|
| 0     | Off      |
| 1     | On       |

```json
{"cmd": "lightsaber", "power": 1}
```

#### `led` — LED color control
Any field omitted or set to -1 leaves that LED unchanged.

| Field | LED   |
|-------|-------|
| `r`   | Red   |
| `b`   | Blue  |
| `y`   | Yellow|
| `g`   | Green |

```json
{"cmd": "led", "r": 255, "b": 0, "y": 128, "g": -1}
```

#### `lcd` — LCD panel control

| Field | Description         |
|-------|---------------------|
| `s`   | Short LCD (1=off, 2=on) |
| `l`   | Long LCD (1=off, 2=on)  |

```json
{"cmd": "lcd", "s": 2, "l": 1}
```

#### `d-head-power` — Head rotation motor power
```json
{"cmd": "d-head-power", "power": 80}
```

#### `d-leg-power` — Leg/drive motor power level
```json
{"cmd": "d-leg-power", "power": 100}
```

#### `reset-wdt` — Reset watchdog timer
```json
{"cmd": "reset-wdt"}
```

#### `gin` — Request full status from MCU
```json
{"cmd": "gin"}
```

#### `shut-down` — Power off MCU
```json
{"cmd": "shut-down"}
```

#### `debug` — Debug mode
```json
{"cmd": "debug"}
```

---

### 1.2 Status Messages (MCU → Android)

#### `gin` — Full robot status
Periodically sent by the MCU (or in response to a `gin` request).

```json
{
  "cmd": "gin",
  "batt": 85,
  "charging-status": 0,
  "lightsaber": 0,
  "arm": 0,
  "projector": 0,
  "mode": 0,
  "head": 0,
  "lcd_s": 1,
  "lcd_l": 1,
  "status": 0,
  "error": ""
}
```

| Field             | Type   | Description                              |
|-------------------|--------|------------------------------------------|
| `batt`            | int    | Battery percentage (0–100)               |
| `charging-status` | int    | 0=not charging, 1=charging, 2=full       |
| `lightsaber`      | int    | 0=off, 1=on                              |
| `arm`             | int    | 0=retracted, 1=extended                  |
| `projector`       | int    | 0=off, 1=projector1, 2=projector2        |
| `mode`            | int    | Current MCU mode                         |
| `head`            | int    | Head angle                               |
| `lcd_s`           | int    | Short LCD: 1=off, 2=on                   |
| `lcd_l`           | int    | Long LCD: 1=off, 2=on                    |
| `status`          | int    | MCU status code                          |
| `error`           | string | Error string, empty if none              |

#### `ready` — MCU ready signal
Sent by the MCU at boot; Android cancels its ready timer.
```json
{"cmd": "ready"}
```

#### `play_sound` — MCU requests sound playback
The MCU can ask Android to play a sound.

```json
{"cmd": "play_sound", "sound_id": 7, "interrupt": 1}
```

#### `btn` — Physical button press

| Value | Action             |
|-------|--------------------|
| 1     | Power off          |
| 2     | Toggle WiFi AP mode|
| 3     | Toggle pair mode   |
| 4     | Toggle lightsaber  |
| 5     | Toggle arm         |
| 6     | Toggle patrol      |

```json
{"cmd": "btn", "value": 3}
```

---

## 2. External WebSocket API (Phone ↔ Android App)

The Android app runs a WebSocket server. Clients (phone app) connect and send JSON commands, one per line (`\n` terminated).

### 2.1 Authentication Flow

All new connections must authenticate before sending control commands.

#### `grantAccess` — Register / authenticate client

```json
{"cmd": "grantAccess", "uuid": "device-uuid", "deviceName": "My Phone", "seq": 1}
```

Success response:
```json
{
  "cmd": "grantAccess",
  "seq": 1,
  "resultCode": 0,
  "robot": { ... robot state ... }
}
```

Error codes:
- `301` — missing UUID
- `401` — not in pair mode and not previously paired

Access is granted automatically if:
- The robot is in **pair mode** (mode 3), OR
- The client UUID is already in the paired list, OR
- The robot is in WiFi AP mode

### 2.2 Normal Commands (require authenticated connection)

All commands use `"seq"` as a request sequence number, echoed in the response.

Responses always include:
```json
{"cmd": "<original_cmd>", "seq": <N>, "resultCode": 0}
```
`resultCode: 0` = success, non-zero = error code.

#### Robot state management

| Command         | Description                    |
|-----------------|--------------------------------|
| `getWifiList`   | Get available WiFi networks    |
| `connectWifi`   | Connect to a WiFi network      |
| `face_detection`| Enable/disable face detection  |
| `voice_recognition` | Enable/disable voice recognition |
| `mute`          | Mute/unmute audio              |
| `power`         | Power off the robot            |
| `user_control`  | Enable/disable user control mode |
| `change_name`   | Change robot name (max 16 chars)|
| `paired_list`   | Get list of paired devices     |
| `unpair`        | Remove a paired device         |

#### Real-time control commands

These bypass the job queue and are processed directly (no `seq`/`resultCode` flow):

##### `move` — Drive robot
```json
{"cmd": "move", "power": 50, "angle": 0}
```
Side effect: if `power > 0` and `angle == 0`, head resets to 0° after 100ms.

##### `move-head` — Rotate head
```json
{"cmd": "move-head", "angle": -40}
```

##### `head-dir` — Head direction
```json
{"cmd": "head-dir", "dir": 1}
```

##### `projector` — Projector
```json
{"cmd": "projector", "mode": 1}
```

##### `mode` — High-level mode / behavior
```json
{"cmd": "mode", "mode": 5}
```

High-level modes dispatched by the app (not directly to MCU):

| Mode | App behavior triggered   |
|------|--------------------------|
| 0    | Stop all                 |
| 1    | Voice wake-up            |
| 2    | Turn around              |
| 3    | Turn left                |
| 4    | Turn right               |
| 5    | Go forward               |
| 6    | Lightsaber toggle        |
| 7    | Who are you (head shake + sound) |
| 9    | Patrol toggle            |
| 10   | Dance sequence           |
| 12   | Walk circle              |
| 13   | Flash front LCD          |
| 14   | Flash back LCD           |
| 15   | Shake head               |
| 16   | Arm toggle               |
| 17   | Short LCD toggle         |
| 18   | Long LCD toggle          |
| 19   | Projector 1 toggle       |
| 20   | Projector 2 toggle       |

##### `lcd` — LCD control
```json
{"cmd": "lcd", "s": 2, "l": 1}
```

##### `led` — LED control
```json
{"cmd": "led", "r": 255, "b": -1, "y": -1, "g": 0}
```

##### `play_sound` — Play sound
```json
{"cmd": "play_sound", "sound_id": 7, "interrupt": 1}
```

##### `d-head-power` / `d-leg-power` — Motor power tuning
```json
{"cmd": "d-head-power", "power": 80}
{"cmd": "d-leg-power", "power": 100}
```

##### `reset-wdt` — Reset MCU watchdog
```json
{"cmd": "reset-wdt"}
```

##### `reset_mcu` — Reset MCU
```json
{"cmd": "reset_mcu"}
```

---

## 3. Robot Mode State Machine

```
         ┌─────────────────────────────────────────────────────┐
         │                   READY (1)                         │
         │                   (default)                         │
         └───┬──────────────┬─────────────────┬───────────────┘
             │              │                 │
        idle timer     pair request     patrol command
             │              │                 │
             ▼              ▼                 ▼
         SLEEP (2)      PAIR (3)         PATROL (4)
                                    (60s auto-stop)
                              ▼ on client control
                       USER_CONTROL (5)
```

**Mode constraints:**
- `PAIR` and `PATROL`: face detection and voice recognition are stopped
- `USER_CONTROL`: face detection stopped (user has active control)
- Any move/head command: blocked while **charging**
- Patrol: auto-stops after 60 seconds

---

## 4. Sound IDs (reference)

Known IDs observed in the code:

| ID  | Usage                         |
|-----|-------------------------------|
| 0   | Who are you                   |
| 1   | Dance / movement sound        |
| 3   | Fail in pair mode             |
| 5   | Moving sound (alternate)      |
| 6   | Make some noise               |
| 7   | Generic R2D2 sound            |
| 8   | Voice wake-up                 |
| 9   | User grant access             |
| 12  | Not recognized                |
| 13  | Face detected                 |
| 100 | Projector 1                   |
| 101 | Projector 2                   |
| 301 | Angle secret                  |
| 302 | Stark secret                  |
