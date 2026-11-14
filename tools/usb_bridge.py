#!/usr/bin/env python3
"""USB bridge for networks that isolate Wi-Fi clients (AP/client isolation).

Phone ──USB/adb──▶ this Mac ──LAN──▶ TV

    adb -s <phone-serial> forward tcp:47800 tcp:47800      # Mac 127.0.0.1:47800 → phone :47800
    python3 tools/usb_bridge.py --listen <mac-lan-ip>       # LAN :47800 → 127.0.0.1:47800

Then enter the Mac's LAN IP on the TV instead of the phone's. Testing only: adds a hop (~1 ms)
and needs the cable. Standard library only.
"""
from __future__ import annotations

import argparse
import asyncio
import socket


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


def no_delay(writer: asyncio.StreamWriter) -> None:
    sock = writer.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)


async def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--listen", required=True, help="Mac LAN address the TV connects to")
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--target", default="127.0.0.1", help="adb-forwarded address")
    ap.add_argument("--target-port", type=int, default=47800)
    a = ap.parse_args()

    async def handle(cr: asyncio.StreamReader, cw: asyncio.StreamWriter) -> None:
        peer = cw.get_extra_info("peername")
        try:
            pr, pw = await asyncio.open_connection(a.target, a.target_port)
        except OSError as e:
            print(f"{peer}: phone not reachable via adb forward ({e})", flush=True)
            cw.close()
            return
        no_delay(cw); no_delay(pw)
        print(f"{peer} ⇄ phone", flush=True)
        await asyncio.gather(pipe(cr, pw), pipe(pr, cw))
        print(f"{peer} closed", flush=True)

    server = await asyncio.start_server(handle, a.listen, a.port)
    print(f"USB bridge: {a.listen}:{a.port} → {a.target}:{a.target_port} (adb forward to the phone)", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
