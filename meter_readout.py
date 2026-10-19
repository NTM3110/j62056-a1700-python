#!/usr/bin/env python3
"""Try one IEC 62056-21 mode C readout at 9600 7E1 (standard library only).

Set MOXA RS485 4-wire mode first: sudo setserial /dev/ttyUSB0 port 3
Stop meter_loop.py before running this script.
"""

import argparse
from datetime import datetime
import os
from pathlib import Path
import select
import sys
import termios
import time

from meter_loop import log


def receive(fd, timeout):
    if not select.select([fd], [], [], timeout)[0]:
        return b""
    data = os.read(fd, 4096)
    if not data:
        raise OSError("Serial device disconnected")
    return data


def send(fd, data):
    if os.write(fd, data) != len(data):
        raise OSError("Incomplete serial write")
    log("TX:", data)


def run(port):
    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    response = bytearray()
    try:
        settings = termios.tcgetattr(fd)
        settings[0] = termios.INPCK
        settings[1] = 0
        settings[2] = termios.CLOCAL | termios.CREAD | termios.CS7 | termios.PARENB
        settings[3] = 0
        settings[4] = settings[5] = termios.B9600
        settings[6][termios.VMIN] = 0
        settings[6][termios.VTIME] = 0
        termios.tcsetattr(fd, termios.TCSANOW, settings)
        termios.tcflush(fd, termios.TCIFLUSH)
        print(f"START: {port}, 9600 7E1, identification then read-only ACK", flush=True)
        send(fd, b"/?!\r\n")
        identity = bytearray()
        deadline = time.monotonic() + 10
        while b"\r\n" not in identity and time.monotonic() < deadline:
            identity.extend(receive(fd, 0.2))
        log("IDENTIFICATION:", bytes(identity))
        if b"\r\n" not in identity:
            raise OSError("No complete identification within 10 seconds")
        line, _, remainder = identity.partition(b"\r\n")
        if not line.startswith(b"/") or line[4:5] != b"5":
            raise OSError("This probe expects mode C baud code 5 (9600); ACK not sent")
        response.extend(remainder)
        time.sleep(0.2)
        send(fd, b"\x06050\r\n")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            chunk = receive(fd, 0.2)
            if chunk:
                response.extend(chunk)
            if b"\x03" in response:
                end = response.index(3)
                if len(response) > end + 1:
                    break
        log("READOUT:", bytes(response))
        print(f"Received {len(response)} readout bytes", flush=True)
        if b"\x02" in response and b"\x03" in response:
            start = response.index(2)
            end = response.index(3)
            if end > start and len(response) > end + 1:
                checksum = 0
                for byte in response[start + 1:end + 1]:
                    checksum ^= byte
                valid = checksum == response[end + 1]
                print(f"BCC {'OK' if valid else 'FAILED'}", flush=True)
                return 0 if valid else 1
        print("No complete STX/data/ETX/BCC readout received", flush=True)
        return 1
    finally:
        os.close(fd)
        if response:
            output = Path(f"meter-readout-{datetime.now():%Y%m%d-%H%M%S-%f}.bin")
            output.write_bytes(response)
            print(f"Saved raw response: {output.resolve()}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="/dev/ttyUSB0")
    args = parser.parse_args()
    try:
        return run(args.port)
    except KeyboardInterrupt:
        print("\nSTOP")
        return 130
    except (OSError, termios.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
