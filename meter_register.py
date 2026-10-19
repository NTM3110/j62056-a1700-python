#!/usr/bin/env python3
"""Read A1700 serial-number register 798001(10) once, using read-only level 0.

Hardware manual Figure 7 (printed page 55) documents level 0 access.
Frame sequence follows the supplied A1700/logloadprofilepmu220317.txt PMU log.
Requires meter_loop.py and meter_readout.py in the same directory.
"""

import argparse
import os
import sys
import termios
import time

from meter_loop import log
from meter_readout import receive, send


def bcc(data):
    result = 0
    for byte in data:
        result ^= byte
    return result


def frame(command, payload=None):
    body = command
    if payload is not None:
        body += b"\x02" + payload
    body += b"\x03"
    return b"\x01" + body + bytes([bcc(body)])


def read_reply(fd, identification=False):
    data = bytearray()
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        data.extend(receive(fd, 0.2))
        if identification:
            if data.endswith(b"\r\n"):
                log("RX:", bytes(data))
                return bytes(data)
        elif data:
            if data[0] in (0x06, 0x15):
                log("RX:", bytes(data))
                return bytes(data)
            if data[0] not in (0x01, 0x02):
                raise OSError(f"Unexpected response: {bytes(data)!r}")
            if 0x03 in data:
                end = data.index(0x03)
                if len(data) > end + 1:
                    log("RX:", bytes(data))
                    if bcc(data[1:end + 1]) != data[end + 1]:
                        raise OSError("Response BCC mismatch")
                    print("BCC OK", flush=True)
                    return bytes(data[:end + 2])
    raise OSError(f"Response timeout; partial={bytes(data)!r}")


def run(port):
    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    session = False
    try:
        settings = termios.tcgetattr(fd)
        settings[0] = termios.INPCK
        settings[1] = settings[3] = 0
        settings[2] = termios.CLOCAL | termios.CREAD | termios.CS7 | termios.PARENB
        settings[4] = settings[5] = termios.B9600
        settings[6][termios.VMIN] = settings[6][termios.VTIME] = 0
        termios.tcsetattr(fd, termios.TCSANOW, settings)
        termios.tcflush(fd, termios.TCIFLUSH)
        print(f"START: {port}, 9600 7E1, read serial-number register once", flush=True)
        send(fd, b"/?!\r\n")
        identity = read_reply(fd, identification=True)
        if not identity.startswith(b"/GEC5"):
            raise OSError("Expected GEC identification with baud code 5")
        time.sleep(0.2)
        session = True
        send(fd, b"\x06051\r\n")
        challenge = read_reply(fd)
        if not challenge.startswith(b"\x01P0\x02("):
            raise OSError("Expected P0 session challenge")
        time.sleep(0.3)
        # Level 0 security algorithm is zero; this is not a guessed password.
        send(fd, frame(b"P2", b"(0000000000000000)"))
        if read_reply(fd) != b"\x06":
            raise OSError("Level 0 was not accepted; stopping without retries")
        time.sleep(0.3)
        send(fd, frame(b"R1", b"798001(10)"))
        response = read_reply(fd)
        if not response.startswith(b"\x02(") or response[-3:-2] != b")":
            raise OSError(f"Unexpected register response: {response!r}")
        value = bytes.fromhex(response[2:-3].decode("ascii"))
        if len(value) != 16:
            raise OSError(f"Expected 16 bytes, received {len(value)}")
        print(f"REGISTER 798001(10): {value!r}", flush=True)
        print(f"SERIAL TEXT: {value.decode('ascii')}", flush=True)
    finally:
        try:
            if session:
                send(fd, frame(b"B0"))
                termios.tcdrain(fd)
        finally:
            os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="/dev/ttyUSB0")
    args = parser.parse_args()
    try:
        run(args.port)
        return 0
    except KeyboardInterrupt:
        print("\nSTOP")
        return 130
    except (OSError, ValueError, termios.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
