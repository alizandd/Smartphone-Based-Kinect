#!/usr/bin/env python3
"""USB bridge for networks that isolate Wi-Fi clients (AP/client isolation).

Phone ──USB/adb──▶ this Mac ──LAN──▶ TV

    python3 tools/usb_bridge.py --listen <mac-lan-ip> --adb-serial <phone-serial>

(--adb-serial runs `adb forward tcp:47800 tcp:47800` itself and repeats it after a cable re-plug.)

Then enter the Mac's LAN IP on the TV instead of the phone's. Testing only: adds a hop (~1 ms)
and needs the cable. Standard library only.
"""
from __future__ import annotations

import argparse
import asyncio
import socket
import subprocess


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
    ap.add_argument("--adb-serial", help="re-create the adb forward automatically (e.g. after the USB cable is re-plugged)")
    a = ap.parse_args()

    def ensure_forward() -> None:
        if a.adb_serial:
            subprocess.run(["adb", "-s", a.adb_serial, "forward", f"tcp:{a.target_port}", f"tcp:{a.port}"],
                           capture_output=True, timeout=10)

    ensure_forward()

    async def handle(cr: asyncio.StreamReader, cw: asyncio.StreamWriter) -> None:
        peer = cw.get_extra_info("peername")
        try:
            pr, pw = await asyncio.open_connection(a.target, a.target_port)
        except OSError:
            # The forward disappears when the USB cable is re-plugged: re-create it once and retry.
            await asyncio.to_thread(ensure_forward)
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
