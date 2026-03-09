"""REST API server for the R2D2 robot.

Exposes the robot over HTTP/JSON. Designed with Home Assistant integration
in mind — all endpoints are stateless, use simple JSON bodies, and return
consistent response shapes.

Authentication: set R2D2_API_KEY env var. If set, all requests must include
    Authorization: Bearer <key>

Endpoints:

    GET  /status           — full robot status
    POST /move             — drive {power, angle}
    POST /stop             — stop all movement
    POST /head             — move head {angle}
    POST /mode             — dispatch mode {mode}
    POST /led              — set LEDs {r, b, y, g}
    POST /lcd              — set LCD {short, long}
    POST /lightsaber       — toggle lightsaber {on: bool}
    POST /arm              — toggle arm {extended: bool}
    POST /projector        — projector mode {mode: 0|1|2}
    POST /patrol           — start/stop patrol {enable: bool}
    POST /power_off        — shut down robot
    POST /shake_head
    POST /dance
    POST /turn_left
    POST /turn_right
    POST /turn_around
    POST /go_forward
    POST /walk_circle
"""

import logging
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

from r2d2 import R2D2

from .config import Config

logger = logging.getLogger(__name__)

# --- Request models -------------------------------------------------------

class MoveRequest(BaseModel):
    power: int
    angle: int = 0

class HeadRequest(BaseModel):
    angle: int

class ModeRequest(BaseModel):
    mode: int

class LEDRequest(BaseModel):
    r: int = -1
    b: int = -1
    y: int = -1
    g: int = -1

class LCDRequest(BaseModel):
    short: int = -1
    long: int = -1

class LightsaberRequest(BaseModel):
    on: bool

class ArmRequest(BaseModel):
    extended: bool

class ProjectorRequest(BaseModel):
    mode: int

class PatrolRequest(BaseModel):
    enable: bool

class MotorPowerRequest(BaseModel):
    power: int

# --------------------------------------------------------------------------

def create_app(robot: R2D2, config: Config) -> FastAPI:
    """Factory that creates the FastAPI app with the robot instance injected."""

    app = FastAPI(title="R2D2 REST API", version="1.0.0")
    security = HTTPBearer(auto_error=False)

    def verify_api_key(
        credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    ) -> None:
        if not config.API_KEY:
            return  # Auth disabled
        if credentials is None or credentials.credentials != config.API_KEY:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or missing API key",
            )

    deps = [Depends(verify_api_key)]

    # ------------------------------------------------------------------
    # Status
    # ------------------------------------------------------------------

    @app.get("/status", dependencies=deps)
    def get_status() -> dict:
        """Return the latest robot status as reported by the MCU."""
        return robot.status.to_dict()

    # ------------------------------------------------------------------
    # Movement
    # ------------------------------------------------------------------

    @app.post("/move", dependencies=deps)
    def move(req: MoveRequest) -> dict:
        robot.move(req.power, req.angle)
        return {"ok": True}

    @app.post("/stop", dependencies=deps)
    def stop() -> dict:
        robot.stop()
        return {"ok": True}

    @app.post("/head", dependencies=deps)
    def head(req: HeadRequest) -> dict:
        robot.move_head(req.angle)
        return {"ok": True}

    # ------------------------------------------------------------------
    # High-level modes / behaviors
    # ------------------------------------------------------------------

    @app.post("/mode", dependencies=deps)
    def mode(req: ModeRequest) -> dict:
        robot.dispatch_mode(req.mode)
        return {"ok": True}

    @app.post("/shake_head", dependencies=deps)
    def shake_head() -> dict:
        robot.shake_head()
        return {"ok": True}

    @app.post("/dance", dependencies=deps)
    def dance() -> dict:
        robot.dance()
        return {"ok": True}

    @app.post("/turn_left", dependencies=deps)
    def turn_left() -> dict:
        robot.turn_left()
        return {"ok": True}

    @app.post("/turn_right", dependencies=deps)
    def turn_right() -> dict:
        robot.turn_right()
        return {"ok": True}

    @app.post("/turn_around", dependencies=deps)
    def turn_around() -> dict:
        robot.turn_around()
        return {"ok": True}

    @app.post("/go_forward", dependencies=deps)
    def go_forward() -> dict:
        robot.go_forward()
        return {"ok": True}

    @app.post("/walk_circle", dependencies=deps)
    def walk_circle() -> dict:
        robot.walk_circle()
        return {"ok": True}

    @app.post("/patrol", dependencies=deps)
    def patrol(req: PatrolRequest) -> dict:
        robot.patrol(req.enable)
        return {"ok": True}

    # ------------------------------------------------------------------
    # Accessories
    # ------------------------------------------------------------------

    @app.post("/lightsaber", dependencies=deps)
    def lightsaber(req: LightsaberRequest) -> dict:
        robot.lightsaber(req.on)
        return {"ok": True}

    @app.post("/arm", dependencies=deps)
    def arm(req: ArmRequest) -> dict:
        robot.arm(req.extended)
        return {"ok": True}

    @app.post("/projector", dependencies=deps)
    def projector(req: ProjectorRequest) -> dict:
        robot.projector(req.mode)
        return {"ok": True}

    # ------------------------------------------------------------------
    # Lights & display
    # ------------------------------------------------------------------

    @app.post("/led", dependencies=deps)
    def led(req: LEDRequest) -> dict:
        robot.led(req.r, req.b, req.y, req.g)
        return {"ok": True}

    @app.post("/lcd", dependencies=deps)
    def lcd(req: LCDRequest) -> dict:
        robot.lcd(req.short, req.long)
        return {"ok": True}

    # ------------------------------------------------------------------
    # Motor tuning
    # ------------------------------------------------------------------

    @app.post("/head_power", dependencies=deps)
    def head_power(req: MotorPowerRequest) -> dict:
        robot.set_head_power(req.power)
        return {"ok": True}

    @app.post("/leg_power", dependencies=deps)
    def leg_power(req: MotorPowerRequest) -> dict:
        robot.set_leg_power(req.power)
        return {"ok": True}

    # ------------------------------------------------------------------
    # System
    # ------------------------------------------------------------------

    @app.post("/power_off", dependencies=deps)
    def power_off() -> dict:
        robot.power_off()
        return {"ok": True}

    return app
