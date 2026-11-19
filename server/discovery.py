"""mDNS/Zeroconf service advertisement for PiPhotos."""

from __future__ import annotations

import socket
from typing import Optional

from zeroconf import ServiceInfo, Zeroconf


SERVICE_TYPE = "_piphotos._tcp.local."


class PiPhotosAdvertiser:
    """Advertises the PiPhotos server via mDNS."""

    def __init__(
        self,
        server_id: str,
        server_name: str,
        port: int,
        tls_fingerprint: str,
        protocol_version: int = 1,
    ) -> None:
        self._zc: Optional[Zeroconf] = None
        self._info: Optional[ServiceInfo] = None
        self._server_id = server_id
        self._server_name = server_name
        self._port = port
        self._tls_fp = tls_fingerprint
        self._proto_version = protocol_version

    def start(self) -> None:
        """Register the mDNS service."""
        hostname = socket.gethostname()

        # Build TXT records
        properties = {
            b"v": str(self._proto_version).encode(),
            b"id": self._server_id.encode(),
            b"name": self._server_name.encode(),
            b"tls_fp": self._tls_fp.encode(),
        }

        self._info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"{self._server_name}.{SERVICE_TYPE}",
            port=self._port,
            properties=properties,
            server=f"{hostname}.local.",
        )

        self._zc = Zeroconf()
        self._zc.register_service(self._info)

    def stop(self) -> None:
        """Unregister the mDNS service."""
        if self._zc and self._info:
            self._zc.unregister_service(self._info)
            self._zc.close()
            self._zc = None
            self._info = None
