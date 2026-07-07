"""SSRF guard for outbound URLs the model or a user can steer.

The backend sits on the aistack docker network next to postgres, qdrant,
jupyter, etc. — a URL like http://tomsense-postgres:5432 must not be fetchable
via model-controlled tools. `assert_public_url` resolves the hostname and
rejects anything that lands on a non-public address (RFC 1918, loopback,
link-local, CGNAT, docker service names).
"""

import asyncio
import ipaddress
import socket
from urllib.parse import urlparse


class PrivateAddressError(ValueError):
    """URL points at a private / internal address."""


async def assert_public_url(url: str) -> None:
    """Raise PrivateAddressError unless every address `url` resolves to is
    globally routable. IP literals are checked without a DNS lookup."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise PrivateAddressError(f"unsupported scheme {parsed.scheme!r}")
    host = parsed.hostname
    if not host:
        raise PrivateAddressError("no hostname in url")
    try:
        addrs = [ipaddress.ip_address(host)]
    except ValueError:
        # Hostname — resolve asynchronously and check every A/AAAA record.
        loop = asyncio.get_running_loop()
        try:
            infos = await loop.getaddrinfo(host, None, type=socket.SOCK_STREAM)
        except socket.gaierror as e:
            raise PrivateAddressError(f"cannot resolve {host!r}: {e}") from e
        addrs = []
        for _family, _type, _proto, _canon, sockaddr in infos:
            try:
                addrs.append(ipaddress.ip_address(sockaddr[0]))
            except ValueError:
                continue
        if not addrs:
            raise PrivateAddressError(f"{host!r} resolved to no usable address")
    for a in addrs:
        if not a.is_global:
            raise PrivateAddressError(
                f"{host!r} resolves to non-public address {a}"
            )
