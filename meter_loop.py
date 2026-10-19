#!/usr/bin/env python3
"""Request meter identification every 10 seconds over a Linux serial port.

Uses Python's standard library. Configure the MOXA interface separately:
    sudo setserial /dev/ttyUSB0 port 3
    python3 meter_loop.py

This only requests identification; it does not perform the readout handshake.
"""

import argparse
import math
import os
import select
import sys
import termios
import time


def positive_interval(value):
    interval = float(value)
    if not math.isfinite(interval) or interval <= 0:
        raise argparse.ArgumentTypeError("interval must be a finite positive number")
    return interval


def log(direction, data):
    print(time.strftime("%H:%M:%S"), direction, repr(data), flush=True)


def run(port, interval):
    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    pending = bytearray()
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

        print(f"START: {port}, 9600 7E1, interval={interval:g}s. Ctrl+C to stop.",
              flush=True)
        next_send = time.monotonic()
        last_received = next_send
        while True:
            now = time.monotonic()
            if now >= next_send:
                request = b"/?!\r\n"
                if os.write(fd, request) != len(request):
                    raise OSError("Incomplete serial write")
                log("TX:", request)
                next_send = now + interval

            if select.select([fd], [], [], min(0.2, interval))[0]:
                data = os.read(fd, 4096)
                if not data:
                    raise OSError("Serial device disconnected or returned end of stream")
                pending.extend(data)
                last_received = time.monotonic()
                while b"\n" in pending:
                    end = pending.index(b"\n") + 1
                    log("RX:", bytes(pending[:end]))
                    del pending[:end]

            # Show incomplete/non-line responses too, without buffering forever.
            if pending and (time.monotonic() - last_received >= 0.5
                            or len(pending) >= 4096):
                log("RX (partial):", bytes(pending))
                pending.clear()
    finally:
        if pending:
            log("RX (partial):", bytes(pending))
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", default="/dev/ttyUSB0")
    parser.add_argument("--interval", type=positive_interval, default=10.0,
                        help="seconds between requests (default: 10)")
    args = parser.parse_args()
    try:
        run(args.port, args.interval)
    except KeyboardInterrupt:
        print("\nSTOP", flush=True)
        return 0
    except (OSError, termios.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
