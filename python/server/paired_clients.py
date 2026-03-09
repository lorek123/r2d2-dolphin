"""Persistent store for paired WebSocket clients."""

import json
import logging
import os
from typing import Optional

logger = logging.getLogger(__name__)


class PairedClientsStore:
    """JSON-file-backed store of paired device UUIDs and names."""

    def __init__(self, path: str = "paired_clients.json") -> None:
        self._path = path
        self._clients: dict[str, str] = {}  # uuid -> device_name
        self._load()

    def _load(self) -> None:
        if os.path.exists(self._path):
            try:
                with open(self._path) as f:
                    self._clients = json.load(f)
                logger.info("Loaded %d paired clients", len(self._clients))
            except Exception:
                logger.warning("Could not load paired clients from %s", self._path)

    def _save(self) -> None:
        try:
            with open(self._path, "w") as f:
                json.dump(self._clients, f, indent=2)
        except Exception:
            logger.error("Could not save paired clients to %s", self._path)

    def is_known(self, uuid: str) -> bool:
        return uuid in self._clients

    def add(self, uuid: str, name: str) -> None:
        self._clients[uuid] = name
        self._save()

    def update_name(self, uuid: str, name: str) -> None:
        if uuid in self._clients:
            self._clients[uuid] = name
            self._save()

    def remove(self, uuid: str) -> None:
        self._clients.pop(uuid, None)
        self._save()

    def clear(self) -> None:
        self._clients.clear()
        self._save()

    def all(self) -> list[dict]:
        return [{"uuid": k, "deviceName": v} for k, v in self._clients.items()]
