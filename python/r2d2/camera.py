"""Face detection and head tracking.

Mirrors Android FaceDetection + VideoStreamer:
- Captures frames from a V4L2 camera using OpenCV.
- Detects faces using Haar/LBP cascade (default) or MediaPipe (optional).
- Tracks the target face and steers R2D2's head toward it.
- Provides JPEG frames for MJPEG streaming clients.

Head tracking algorithm (ported from Android FaceDetection.changeHeadDirection):

    diff         = face_center_x - frame_center_x
    target_angle = (diff / frame_width) * 40.0   # max ±40°
    rotate       = clamp(int(0.5 * target_angle), -5, 5)
    if |target_angle| > 2.0 and rotate != 0:
        robot.shift_head(rotate)

Face persistence: a detection must be stable for 1500 ms before becoming
the "target"; faces absent for 1500 ms are dropped.
"""

import logging
import threading
import time
from pathlib import Path
from typing import Callable, List, Optional

import cv2
import numpy as np

logger = logging.getLogger(__name__)

_CASCADE_DIR = Path(__file__).parent / "cascade"
_HAAR_CASCADE = _CASCADE_DIR / "haarcascade_frontalface_alt.xml"
_LBP_CASCADE  = _CASCADE_DIR / "lbpcascade_frontalface_improved.xml"

# Head tracking constants (from Android FaceDetection)
_MAX_DEGREE   = 40.0
_FOLLOW_SPEED = 0.5
_MAX_SHIFT    = 5
_DEAD_ZONE    = 2.0

# Face tracking constants (from Android FaceDetection)
_FACE_PERSIST_MS = 1500   # ms a face must exist before becoming target
_FACE_TIMEOUT_MS = 1500   # ms before dropping an unseen face
_MAX_FACES       = 10     # max simultaneously tracked faces
_SCALE_DOWN      = 3      # downscale factor for detection
_MIN_FACE_RATIO  = 0.2    # min face size relative to frame height

FaceCallback = Callable[[bool, int], None]   # (tracking: bool, face_count: int)


class _TrackedFace:
    _id_counter = 0

    def __init__(self, rect: tuple, now_ms: int) -> None:
        _TrackedFace._id_counter += 1
        self.face_id   = _TrackedFace._id_counter
        self.rect      = rect   # (x, y, w, h) in full-resolution coords
        self.first_seen = now_ms
        self.last_seen  = now_ms


class FaceDetector:
    """Camera capture + face detection + head tracking.

    Args:
        device:       V4L2 device path or OpenCV index (default ``/dev/video0``).
        rotation:     Frame rotation in degrees before detection
                      (270 = camera mounted sideways, matching original robot).
        backend:      ``"haar"`` (default), ``"lbp"``, or ``"mediapipe"``.
        fps:          Target capture and detection frame rate (default 10).
        jpeg_quality: JPEG quality for MJPEG stream frames (default 40).
    """

    def __init__(
        self,
        device: str = "/dev/video0",
        rotation: int = 270,
        backend: str = "haar",
        fps: int = 10,
        jpeg_quality: int = 40,
    ) -> None:
        self._device       = device
        self._rotation     = rotation
        self._backend      = backend
        self._fps          = fps
        self._jpeg_quality = jpeg_quality

        self._clf = self._load_detector(backend)
        self._robot = None

        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

        # Latest annotated JPEG for MJPEG streaming
        self._frame_lock = threading.Lock()
        self._latest_frame: Optional[bytes] = None

        # Face tracking state
        self._tracked: List[_TrackedFace] = []
        self._target: Optional[_TrackedFace] = None
        self._is_tracking = False

        self._callbacks: List[FaceCallback] = []

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_robot(self, robot) -> None:
        """Wire head tracking and LED/sound to a R2D2 instance."""
        self._robot = robot

    def add_callback(self, cb: FaceCallback) -> None:
        """Register a callback fired when tracking state changes."""
        self._callbacks.append(cb)

    def remove_callback(self, cb: FaceCallback) -> None:
        self._callbacks.remove(cb)

    def start(self) -> None:
        """Start the capture and detection thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run,
            name="face-detector",
            daemon=True,
        )
        self._thread.start()
        logger.info("FaceDetector started (device=%s backend=%s)", self._device, self._backend)

    def stop(self) -> None:
        """Stop the capture thread and clear tracking state."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=3.0)
            self._thread = None
        self._tracked.clear()
        self._target = None
        if self._is_tracking:
            self._is_tracking = False
            self._fire_callbacks(False, 0)
        logger.info("FaceDetector stopped")

    @property
    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @property
    def face_detected(self) -> bool:
        return self._target is not None

    @property
    def face_count(self) -> int:
        return len(self._tracked)

    def get_frame(self) -> Optional[bytes]:
        """Return the latest annotated JPEG frame, or None."""
        with self._frame_lock:
            return self._latest_frame

    # ------------------------------------------------------------------
    # Capture / detection loop
    # ------------------------------------------------------------------

    def _run(self) -> None:
        # Try integer index if numeric string given
        try:
            device = int(self._device)
        except (ValueError, TypeError):
            device = self._device

        cap = cv2.VideoCapture(device)
        if not cap.isOpened():
            logger.error("Failed to open camera: %s", self._device)
            return

        interval = 1.0 / self._fps
        while not self._stop_event.is_set():
            t0 = time.monotonic()

            ok, frame = cap.read()
            if not ok:
                logger.warning("Camera read failed, retrying…")
                time.sleep(0.5)
                continue

            frame = self._rotate(frame)
            rects = self._detect(frame)
            self._update_tracking(rects, frame.shape[1])

            # Annotate target face for stream
            if self._target is not None:
                x, y, w, h = self._target.rect
                cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)

            _, jpeg = cv2.imencode(
                ".jpg", frame,
                [cv2.IMWRITE_JPEG_QUALITY, self._jpeg_quality],
            )
            with self._frame_lock:
                self._latest_frame = jpeg.tobytes()

            elapsed = time.monotonic() - t0
            sleep = interval - elapsed
            if sleep > 0:
                time.sleep(sleep)

        cap.release()

    # ------------------------------------------------------------------
    # Frame rotation
    # ------------------------------------------------------------------

    def _rotate(self, frame: np.ndarray) -> np.ndarray:
        if self._rotation == 90:
            return cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
        if self._rotation == 180:
            return cv2.rotate(frame, cv2.ROTATE_180)
        if self._rotation == 270:
            return cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        return frame

    # ------------------------------------------------------------------
    # Face detection (cascade or MediaPipe)
    # ------------------------------------------------------------------

    def _detect(self, frame: np.ndarray) -> List[tuple]:
        if self._backend == "mediapipe":
            return self._detect_mediapipe(frame)
        return self._detect_cascade(frame)

    def _detect_cascade(self, frame: np.ndarray) -> List[tuple]:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        small = cv2.resize(gray, (w // _SCALE_DOWN, h // _SCALE_DOWN))
        min_size = max(1, int((h // _SCALE_DOWN) * _MIN_FACE_RATIO))

        rects = self._clf.detectMultiScale(
            small,
            scaleFactor=1.1,
            minNeighbors=5,
            flags=cv2.CASCADE_SCALE_IMAGE,
            minSize=(min_size, min_size),
        )
        if len(rects) == 0:
            return []
        # Scale coordinates back to full resolution
        return [
            (x * _SCALE_DOWN, y * _SCALE_DOWN, w * _SCALE_DOWN, h * _SCALE_DOWN)
            for (x, y, w, h) in rects
        ]

    def _detect_mediapipe(self, frame: np.ndarray) -> List[tuple]:
        try:
            import cv2 as _cv2
            rgb = _cv2.cvtColor(frame, _cv2.COLOR_BGR2RGB)
            results = self._clf.process(rgb)
            if not results.detections:
                return []
            fh, fw = frame.shape[:2]
            faces = []
            for det in results.detections:
                bb = det.location_data.relative_bounding_box
                x = int(bb.xmin * fw)
                y = int(bb.ymin * fh)
                w = int(bb.width * fw)
                h = int(bb.height * fh)
                faces.append((max(0, x), max(0, y), w, h))
            return faces
        except Exception:
            logger.exception("MediaPipe detection error")
            return []

    # ------------------------------------------------------------------
    # Face tracking state machine (ported from Android FaceDetection)
    # ------------------------------------------------------------------

    def _update_tracking(self, rects: List[tuple], frame_width: int) -> None:
        now = int(time.monotonic() * 1000)

        # Match detections to existing tracked faces, add new ones
        for rect in rects:
            match = self._find_match(rect, frame_width)
            if match is None:
                if len(self._tracked) < _MAX_FACES:
                    self._tracked.append(_TrackedFace(rect, now))
            else:
                match.rect = rect
                match.last_seen = now

        # Expire faces not seen recently
        self._tracked = [
            f for f in self._tracked
            if now - f.last_seen <= _FACE_TIMEOUT_MS
        ]

        # Pick target: first face that has been stable long enough
        prev_target = self._target
        self._target = next(
            (f for f in self._tracked if now - f.first_seen >= _FACE_PERSIST_MS),
            None,
        )

        # Side effects
        if self._target is not None:
            self._on_face_detected(frame_width)
        elif prev_target is not None:
            self._on_face_lost()

        # Fire callbacks on tracking state change
        was_tracking = self._is_tracking
        self._is_tracking = self._target is not None
        if self._is_tracking != was_tracking:
            self._fire_callbacks(self._is_tracking, len(self._tracked))

    def _find_match(self, rect: tuple, frame_width: int) -> Optional[_TrackedFace]:
        """Find existing tracked face matching this detection (Android searchPrevFace)."""
        x1, y1, w1, h1 = rect
        threshold = frame_width * 0.35
        for f in self._tracked:
            x2, y2, w2, h2 = f.rect
            dist = ((x1 - x2) ** 2 + (y1 - y2) ** 2) ** 0.5
            area1, area2 = w1 * h1, w2 * h2
            if area1 == 0 or area2 == 0:
                continue
            size_ratio = min(area1, area2) / max(area1, area2)
            if dist < threshold and size_ratio > 0.7:
                return f
        return None

    # ------------------------------------------------------------------
    # Side effects: LED, sound, head tracking
    # ------------------------------------------------------------------

    def _on_face_detected(self, frame_width: int) -> None:
        if self._robot:
            self._robot.wake()

        if not self._is_tracking:
            # Transition: no face → face detected
            logger.debug("Face tracking start")
            if self._robot:
                try:
                    self._robot._led.on_face_detect_start()
                except Exception:
                    pass
                self._robot.play_sound(13, interrupt=False)  # startled_three_tone

        if self._target and self._robot:
            self._track_head(self._target.rect, frame_width)

    def _on_face_lost(self) -> None:
        logger.debug("Face tracking stop")
        if self._robot:
            try:
                self._robot._led.on_face_detect_stop()
            except Exception:
                pass

    def _track_head(self, rect: tuple, frame_width: int) -> None:
        """Steer head toward detected face (Android changeHeadDirection)."""
        x, y, w, h = rect
        face_cx = x + w / 2
        diff = face_cx - frame_width / 2
        target_angle = (diff / frame_width) * _MAX_DEGREE
        rotate = int(_FOLLOW_SPEED * target_angle)
        rotate = max(-_MAX_SHIFT, min(_MAX_SHIFT, rotate))
        if abs(target_angle) > _DEAD_ZONE and rotate != 0:
            try:
                self._robot.shift_head(rotate)
            except Exception:
                logger.exception("Head shift error")

    def _fire_callbacks(self, tracking: bool, count: int) -> None:
        for cb in self._callbacks:
            try:
                cb(tracking, count)
            except Exception:
                logger.exception("Error in face callback")

    # ------------------------------------------------------------------
    # Detector loader
    # ------------------------------------------------------------------

    @staticmethod
    def _load_detector(backend: str):
        if backend == "mediapipe":
            try:
                import mediapipe as mp
                clf = mp.solutions.face_detection.FaceDetection(
                    model_selection=0,            # short-range (<2 m)
                    min_detection_confidence=0.6,
                )
                logger.info("MediaPipe face detection loaded")
                return clf
            except ImportError:
                logger.warning("mediapipe not installed, falling back to haar")
                backend = "haar"

        cascade_path = _LBP_CASCADE if backend == "lbp" else _HAAR_CASCADE
        if not cascade_path.exists():
            raise FileNotFoundError(f"Cascade file not found: {cascade_path}")
        clf = cv2.CascadeClassifier(str(cascade_path))
        if clf.empty():
            raise RuntimeError(f"Failed to load cascade: {cascade_path}")
        logger.info("Loaded %s cascade", cascade_path.stem)
        return clf
