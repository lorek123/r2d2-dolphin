# R2D2 Python Implementation

Python reimplementation of the R2D2 NanoPi robot controller, split into:

- **`r2d2/`** — protocol library (serial communication, commands, behaviors)
- **`server/`** — server application (REST API + WebSocket server)
- **`docs/PROTOCOL.md`** — full protocol documentation

---

## Architecture

```
Phone App / Home Assistant
        │
        ├── WebSocket (port 8765) — compatible with original phone app
        └── REST API  (port 8080) — for integrations (e.g. Home Assistant)
                │
          server/main.py
                │
          r2d2/r2d2.py (R2D2 class)
                │
          r2d2/commander.py → r2d2/serial_port.py → /dev/ttyS2 → MCU
```

---

## Installation

```bash
pip install -r requirements.txt
```

---

## Running

```bash
# From the python/ directory
python -m server.main
```

Environment variables:

| Variable          | Default         | Description                  |
|-------------------|-----------------|------------------------------|
| `R2D2_SERIAL`     | `/dev/ttyS2`    | Serial port to MCU           |
| `R2D2_BAUD`       | `115200`        | Serial baud rate             |
| `R2D2_WS_PORT`    | `8765`          | WebSocket server port        |
| `R2D2_API_PORT`   | `8080`          | REST API port                |
| `R2D2_API_KEY`    | *(empty)*       | Bearer token for REST API    |
| `R2D2_LOG_LEVEL`  | `INFO`          | Logging level                |

---

## Using the library directly

```python
from r2d2 import R2D2
import time

with R2D2("/dev/ttyS2") as robot:
    robot.move(50, 0)       # forward at 50% power
    time.sleep(2)
    robot.stop()
    robot.shake_head()
    robot.dance()
```

---

## REST API quick reference

```bash
# Status
curl http://robot:8080/status

# Move forward
curl -X POST http://robot:8080/move -H 'Content-Type: application/json' -d '{"power": 50, "angle": 0}'

# Stop
curl -X POST http://robot:8080/stop

# Shake head
curl -X POST http://robot:8080/shake_head

# Turn on lightsaber
curl -X POST http://robot:8080/lightsaber -d '{"on": true}'

# Set LEDs (red only)
curl -X POST http://robot:8080/led -d '{"r": 255, "b": 0, "y": 0, "g": 0}'
```

With API key:
```bash
curl -H "Authorization: Bearer mysecretkey" http://robot:8080/status
```

---

## Running on rooted Android 4.4 (NanoPi)

The NanoPi runs Android on top of a Linux ARM kernel. Since it is rooted,
Python can be run via several methods:

### Option A: Static Python ARM binary (simplest)
1. Download a pre-compiled Python 3 ARM binary (e.g. from python3-android or termux archive)
2. Push to device:
   ```bash
   adb push python3 /data/local/tmp/python3
   adb shell chmod +x /data/local/tmp/python3
   ```
3. Grant serial port access:
   ```bash
   adb shell su -c "chmod 666 /dev/ttyS2"
   ```
4. Run:
   ```bash
   adb shell su -c "/data/local/tmp/python3 -m server.main"
   ```

### Option B: Debian ARM chroot (recommended for full pip support)
1. Install BusyBox on the device
2. Download a Debian ARM rootfs (e.g. from official Debian or a pre-built image)
3. Set up chroot:
   ```bash
   adb shell su -c "chroot /data/debian /bin/bash"
   ```
4. Inside chroot: `apt install python3 python3-pip && pip install -r requirements.txt`
5. Bind-mount the serial device: the chroot can access `/dev/ttyS2` directly if
   the device node exists in the chroot's `/dev/`

### Option C: Replace Android with Armbian (best long-term)
NanoPi boards have excellent Armbian support. Flashing Armbian gives a proper
Debian/Ubuntu environment with full Python 3, pip, systemd, etc.
See: https://www.armbian.com/

### Auto-start on boot (Android)
Create `/data/local/userinit.sh` (executed by some Android init configs):
```bash
#!/system/bin/sh
su -c "chmod 666 /dev/ttyS2"
su -c "/data/local/tmp/python3 /data/r2d2/server/main.py &"
```

Or use a root-enabled init.d script if the ROM supports it.

---

## Future: Home Assistant Integration

The REST API is designed to be consumed by Home Assistant's
[RESTful command](https://www.home-assistant.io/integrations/rest_command/)
and [RESTful sensor](https://www.home-assistant.io/integrations/rest/) integrations.

Example `configuration.yaml` preview (not yet finalized):
```yaml
rest_command:
  r2d2_move_forward:
    url: http://r2d2.local:8080/go_forward
    method: post

  r2d2_stop:
    url: http://r2d2.local:8080/stop
    method: post

sensor:
  - platform: rest
    name: R2D2 Battery
    resource: http://r2d2.local:8080/status
    value_template: "{{ value_json.battery }}"
    unit_of_measurement: "%"
```
