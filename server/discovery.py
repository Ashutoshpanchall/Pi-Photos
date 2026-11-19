"""mDNS/Zeroconf service advertisement for PiPhotos using AsyncZeroconf."""

from __future__ import annotations

import logging
import socket
from typing import Optional

from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf

from tls_cert import get_local_ipv4_addresses

logger = logging.getLogger("piphotos.discovery")

SERVICE_TYPE = "_piphotos._tcp.local."


class PiPhotosAdvertiser:
    """Advertises the PiPhotos server via mDNS asynchronously without blocking the event loop."""

    def __init__(
        self,
        server_id: str,
        server_name: str,
        port: int,
        tls_fingerprint: str,
        protocol_version: int = 1,
    ) -> None:
        self._aiozc: Optional[AsyncZeroconf] = None
        self._info: Optional[AsyncServiceInfo] = None
        self._server_id = server_id
        self._server_name = server_name
        self._port = port
        self._tls_fp = tls_fingerprint
        self._proto_version = protocol_version

    async def async_start(self) -> list[str]:
        """Register the mDNS service with real IPv4 addresses.

        Returns the list of advertised IPv4 addresses.
        """
        hostname = socket.gethostname()
        addresses = get_local_ipv4_addresses()

        properties = {
            b"v": str(self._proto_version).encode(),
            b"id": self._server_id.encode(),
            b"name": self._server_name.encode(),
            b"tls_fp": self._tls_fp.encode(),
        }

        self._info = AsyncServiceInfo(
            type_=SERVICE_TYPE,
            name=f"{self._server_name}.{SERVICE_TYPE}",
            port=self._port,
            properties=properties,
            server=f"{hostname}.local.",
            parsed_addresses=addresses,
        )

        self._aiozc = AsyncZeroconf()
        await self._aiozc.async_register_service(self._info)
        logger.info(
            "mDNS registered: %s on port %d with IPs %s",
            f"{self._server_name}.{SERVICE_TYPE}",
            self._port,
            addresses,
        )
        return addresses

    async def async_stop(self) -> None:
        """Unregister the mDNS service cleanly."""
        if self._aiozc and self._info:
            try:
                await self._aiozc.async_unregister_service(self._info)
            except Exception as e:
                logger.warning("Error unregistering mDNS service: %s", e)
            try:
                await self._aiozc.async_close()
            except Exception as e:
                logger.warning("Error closing AsyncZeroconf: %s", e)
            self._aiozc = None
            self._info = None
