#!/usr/bin/env python3
"""
Pasarela de websocket fake -> servicio SMUX real.

Flujo:
  HEV -> SOCKS5 local (app) -> TunnelMux cliente -> websocket fake persistente
  -> este gateway -> servicio SMUX backend.

Este script NO implementa frames websocket reales; solo valida/acepta
el handshake HTTP fake y luego hace proxy TCP full-duplex al backend SMUX.
"""

from __future__ import annotations

import argparse
import asyncio


CRLF2 = b"\r\n\r\n"


async def read_http_block(reader: asyncio.StreamReader, limit: int = 64 * 1024) -> bytes:
    data = bytearray()
    while CRLF2 not in data:
        chunk = await reader.read(1024)
        if not chunk:
            break
        data.extend(chunk)
        if len(data) > limit:
            raise ValueError("HTTP header demasiado grande")
    return bytes(data)


def parse_headers(block: bytes) -> tuple[str, dict[str, str]]:
    text = block.decode("latin1", errors="ignore")
    lines = text.split("\r\n")
    request_line = lines[0] if lines else ""
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if not line:
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        headers[k.strip().lower()] = v.strip()
    return request_line, headers


async def pipe(src: asyncio.StreamReader, dst: asyncio.StreamWriter) -> None:
    try:
        while True:
            chunk = await src.read(65536)
            if not chunk:
                break
            dst.write(chunk)
            await dst.drain()
    except Exception:
        pass
    finally:
        try:
            dst.close()
            await dst.wait_closed()
        except Exception:
            pass


async def handle_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    upstream_host: str,
    upstream_port: int,
) -> None:
    peer = writer.get_extra_info("peername")
    try:
        req1 = await read_http_block(reader)
        req2 = await read_http_block(reader)
        _, _h1 = parse_headers(req1)
        _, h2 = parse_headers(req2)
        client_id = h2.get("x-client-id", "-")

        upstream_reader, upstream_writer = await asyncio.open_connection(upstream_host, upstream_port)

        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Connection: Upgrade\r\n"
            "Upgrade: websocket\r\n"
            "X-Status: VALID\r\n"
            f"X-Client-Id: {client_id}\r\n"
            "\r\n"
        ).encode("latin1")
        writer.write(resp)
        await writer.drain()

        await asyncio.gather(
            pipe(reader, upstream_writer),
            pipe(upstream_reader, writer),
        )
    except Exception as exc:
        print(f"[gateway] {peer} error: {exc}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def main() -> None:
    parser = argparse.ArgumentParser(description="Gateway websocket fake -> SMUX backend")
    parser.add_argument("--listen-host", default="0.0.0.0")
    parser.add_argument("--listen-port", type=int, default=80)
    parser.add_argument("--upstream-host", default="127.0.0.1")
    parser.add_argument("--upstream-port", type=int, default=9000)
    args = parser.parse_args()

    server = await asyncio.start_server(
        lambda r, w: handle_client(r, w, args.upstream_host, args.upstream_port),
        host=args.listen_host,
        port=args.listen_port,
        reuse_address=True,
    )

    addrs = ", ".join(str(sock.getsockname()) for sock in (server.sockets or []))
    print(f"[gateway] listening on {addrs} -> {args.upstream_host}:{args.upstream_port}")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
