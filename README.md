# R2D2 Dolphin Control

This repository contains Python scripts to control an R2D2 robot's LCD screen and projector functionality via WebSocket communication.

## Features

- WebSocket client for R2D2 control
- LCD screen control (short and long modes)
- Projector mode control (off, mode 1, mode 2)
- Automatic connection handling and validation
- Error handling and connection management

## Requirements

- Python 3.6+
- websocket-client package

## Installation

1. Clone the repository:
```bash
git clone https://github.com/lorek123/r2d2-dolphin.git
cd r2d2-dolphin
```

2. Install dependencies:
```bash
pip install websocket-client
```

## Usage

1. Update the R2D2's IP address in the script:
```python
client = R2D2WebSocketClient(host="192.168.1.100")  # Replace with your R2D2's IP
```

2. Run the script:
```bash
python r2d2_websocket_client.py
```

## API Reference

### WebSocket Commands

- LCD Control:
  - Toggle Short LCD: `{"cmd": "lcd", "s": 1}`
  - Toggle Long LCD: `{"cmd": "lcd", "l": 1}`
  - Set Both LCDs: `{"cmd": "lcd", "s": 1, "l": 2}`

- Projector Control:
  - Mode Off: `{"cmd": "projector", "mode": 0}`
  - Mode 1: `{"cmd": "projector", "mode": 1}`
  - Mode 2: `{"cmd": "projector", "mode": 2}`

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details. 