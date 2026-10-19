#!/usr/bin/env python3
"""A1700 IEC 62056-21 native-register reader for the documented 9600 7E1 setup.

The output is verified raw register bytes. Engineering-unit decoding requires the
meter's register layout, scaling and profile-record specification; it is not guessed.
Higher access uses a local password file or authentication helper.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import select
import subprocess
import sys
import termios
import time

SOH, STX, ETX, ACK, NAK = 1, 2, 3, 6, 21

# PMU_A1700_Registers_210930.xlsx, "Current Value", rows 17-52.
# Row 21 lost its request text in this copy; row 22 is its 64-byte response,
# and PMU_register.xlsx, "Current Value", row 21 retains R1 507001(40).
CURRENT_VALUE_REQUESTS = (
    ("667001", 0x20), ("668001", 0x08),
    ("507001", 0x40), ("507002", 0x10),
    ("508001", 0x40), ("508002", 0x40),
    ("508003", 0x40), ("508004", 0x40),
    ("516001", 0x20),
    ("510001", 0x40), ("510002", 0x40),
    ("510003", 0x40), ("510004", 0x40), ("510005", 0x20),
    ("509001", 0x40), ("509002", 0x08),
    ("511001", 0x40), ("511002", 0x38),
)
LOCAL_PASSWORD_FILE = Path(__file__).resolve().parent / ".a1700_21000568.key"


class ProtocolError(Exception):
    pass


def bcc(data):
    value = 0
    for byte in data:
        value ^= byte
    return value


def frame(command, payload=None):
    body = command.encode("ascii")
    if payload is not None:
        body += bytes([STX]) + payload.encode("ascii")
    body += bytes([ETX])
    return bytes([SOH]) + body + bytes([bcc(body)])


def unpack_frame(raw, expected_bytes=None):
    """Validate an A1700 response and return its ASCII-hex-encoded value."""
    if not raw or raw[0] not in (SOH, STX):
        raise ProtocolError(f"Bad frame start: {raw!r}")
    try:
        end = raw.index(ETX, 1)
    except ValueError as exc:
        raise ProtocolError("Missing ETX") from exc
    if len(raw) != end + 2 or bcc(raw[1:end + 1]) != raw[end + 1]:
        raise ProtocolError("Truncated frame or invalid BCC")
    start = raw.index(STX) + 1
    payload = raw[start:end]
    if expected_bytes is not None:
        if len(payload) != expected_bytes * 2 + 2 or not payload.startswith(b"(") or not payload.endswith(b")"):
            raise ProtocolError(f"Unexpected register length: {payload!r}")
    return payload


def decode_hex_payload(payload):
    if not re.fullmatch(rb"\([0-9A-Fa-f]*\)", payload):
        raise ProtocolError(f"Expected parenthesized hex bytes, got {payload!r}")
    try:
        return bytes.fromhex(payload[1:-1].decode("ascii"))
    except ValueError as exc:
        raise ProtocolError("Invalid hex response") from exc


def encrypt_password(password, challenge):
    """A1700 P2 algorithm published by Gurux, using an authorized 8-byte password."""
    if len(password) != 8 or len(challenge) != 8:
        raise ValueError("A1700 password and challenge must each be 8 bytes")
    value = [password[i] ^ challenge[i] for i in range(8)]
    previous = value[7]
    for i in range(8):
        value[i] = (value[i] + previous) & 0xFF
        previous = value[i]
    return bytes(value)


def recover_password(challenge, response):
    """Recover a password from a previously authorized PMU challenge/response."""
    if len(challenge) != 8 or len(response) != 8:
        raise ValueError("P0/P2 captures must each be 8 bytes")
    initial = (response[7] - response[6]) & 0xFF
    result = bytes((((response[i] - (initial if i == 0 else response[i - 1])) & 0xFF)
                    ^ challenge[i]) for i in range(8))
    if encrypt_password(result, challenge) != response:
        raise ProtocolError("Capture did not satisfy the A1700 authentication algorithm")
    return result


class SerialPort:
    def __init__(self, path, timeout=8.0, on_wire=None):
        self.path, self.timeout, self.on_wire = path, timeout, on_wire
        self.fd = None
        self.pending = bytearray()

    def __enter__(self):
        self.fd = os.open(self.path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        settings = termios.tcgetattr(self.fd)
        settings[0] = termios.INPCK
        settings[1] = settings[3] = 0
        settings[2] = termios.CLOCAL | termios.CREAD | termios.CS7 | termios.PARENB
        settings[4] = settings[5] = termios.B9600
        settings[6][termios.VMIN] = settings[6][termios.VTIME] = 0
        termios.tcsetattr(self.fd, termios.TCSANOW, settings)
        termios.tcflush(self.fd, termios.TCIFLUSH)
        return self

    def __exit__(self, *_):
        os.close(self.fd)

    def send(self, data):
        if os.write(self.fd, data) != len(data):
            raise OSError("Incomplete serial write")
        termios.tcdrain(self.fd)
        if self.on_wire:
            self.on_wire("TX", data)

    def _fill(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProtocolError(f"Meter response timed out; partial={bytes(self.pending)!r}")
        if select.select([self.fd], [], [], remaining)[0]:
            chunk = os.read(self.fd, 4096)
            if not chunk:
                raise OSError("Serial device disconnected")
            self.pending.extend(chunk)

    def read_line(self):
        deadline = time.monotonic() + self.timeout
        while b"\r\n" not in self.pending:
            self._fill(deadline)
        end = self.pending.index(b"\r\n") + 2
        result = bytes(self.pending[:end])
        del self.pending[:end]
        if self.on_wire:
            self.on_wire("RX", result)
        return result

    def read_response(self):
        deadline = time.monotonic() + self.timeout
        while True:
            if self.pending:
                if self.pending[0] in (ACK, NAK):
                    result = bytes(self.pending[:1])
                    del self.pending[:1]
                    if self.on_wire:
                        self.on_wire("RX", result)
                    return result
                if self.pending[0] not in (SOH, STX):
                    raise ProtocolError(f"Unexpected response start: {bytes(self.pending)!r}")
                if ETX in self.pending:
                    end = self.pending.index(ETX)
                    if len(self.pending) > end + 1:
                        result = bytes(self.pending[:end + 2])
                        del self.pending[:end + 2]
                        if self.on_wire:
                            self.on_wire("RX", result)
                        return result
            self._fill(deadline)


def render_wire(data):
    """Render exact serial bytes in a PMU-style, one-line text representation."""
    names = {0: "NUL", 1: "SOH", 2: "STX", 3: "ETX", 4: "EOT", 5: "ENQ",
             6: "ACK", 7: "BEL", 8: "BS", 9: "HT", 10: "LF", 11: "VT",
             12: "FF", 13: "CR", 14: "SO", 15: "SI", 16: "DLE", 17: "DC1",
             18: "DC2", 19: "DC3", 20: "DC4", 21: "NAK", 22: "SYN",
             23: "ETB", 24: "CAN", 25: "EM", 26: "SUB", 27: "ESC",
             28: "FS", 29: "GS", 30: "RS", 31: "US", 127: "DEL"}
    return "".join(f"<{names[byte]}>" if byte in names else chr(byte)
                   if 32 <= byte < 127 else f"<0x{byte:02X}>" for byte in data)
def require_ack(raw, action):
    if raw == bytes([ACK]):
        return
    if raw == bytes([NAK]):
        raise ProtocolError(f"Meter rejected {action} (NAK); access level or address may be wrong")
    raise ProtocolError(f"Expected ACK for {action}, got {raw!r}")


class A1700:
    def __init__(self, transport, address="", auth_helper=None, password_file=None,
                 pause=0.3, progress=None):
        if address and not re.fullmatch(r"[0-9A-Za-z]{1,32}", address):
            raise ValueError("Invalid meter address")
        self.port, self.address, self.auth_helper = transport, address, auth_helper
        self.password_file, self.pause = password_file, pause
        self.identity = self.challenge = self.serial = self.scheme = None
        self.logged_on = False
        self.records = []
        self.progress = progress or (lambda event, **details: None)

    def open(self):
        self.progress("identify_request", request=f"/?{self.address}!\\r\\n")
        self.port.send(f"/?{self.address}!\r\n".encode("ascii"))
        identity = self.port.read_line().rstrip(b"\r\n")
        if not identity.startswith(b"/GEC5"):
            raise ProtocolError(f"Expected /GEC5 identification for 9600 7E1: {identity!r}")
        self.identity = identity.decode("ascii")
        self.progress("identify_response", identity=self.identity)
        time.sleep(0.2)
        self.port.send(b"\x06051\r\n")
        self.progress("mode_select", mode="051")
        challenge = self.port.read_response()
        payload = unpack_frame(challenge)
        if not challenge.startswith(b"\x01P0\x02") or not re.fullmatch(rb"\([0-9A-Fa-f]{16}\)", payload):
            raise ProtocolError(f"Expected 8-byte P0 challenge: {challenge!r}")
        self.challenge = payload[1:-1].decode("ascii").upper()
        self.progress("challenge_received", hex=self.challenge)
        self.logged_on = True
        time.sleep(self.pause)
        self.port.send(frame("P2", "(0000000000000000)"))
        require_ack(self.port.read_response(), "level-0 P2")
        self.progress("level0_login", status="ACK")
        time.sleep(self.pause)
        self.serial = self.read("798001", 0x10).decode("ascii").strip("-")
        self.scheme = self.read("795001", 0x08).decode("ascii").strip("-")
        self.progress("meter_identified", serial=self.serial, scheme=self.scheme)
        return self.identity

    def elevate(self):
        local_password_file = (LOCAL_PASSWORD_FILE if self.serial == "21000568"
                               and LOCAL_PASSWORD_FILE.is_file() else None)
        if self.auth_helper:
            proc = subprocess.run([self.auth_helper, self.challenge, self.serial, self.scheme],
                                  capture_output=True, text=True, timeout=10, check=True)
            response = proc.stdout.strip().upper()
            if not re.fullmatch(r"[0-9A-F]{16}", response):
                raise ProtocolError("Authentication helper must output exactly 16 hex digits")
        elif self.password_file or local_password_file:
            password = (self.password_file or local_password_file).read_bytes().rstrip(b"\r\n")
            response = encrypt_password(password, bytes.fromhex(self.challenge)).hex().upper()
        else:
            raise ProtocolError("Higher access needs a local key, --password-file, or --auth-helper")
        time.sleep(self.pause)
        self.progress("level1_request", challenge_hex=self.challenge)
        self.port.send(frame("P2", f"({response})"))
        require_ack(self.port.read_response(), "higher-access P2")
        self.progress("level1_login", status="ACK")
        time.sleep(self.pause)

    def read(self, register, length):
        if not re.fullmatch(r"[0-9A-Fa-f]{6}", register) or not 0 < length <= 0x40:
            raise ValueError("Register must be six hex digits and length 1..0x40")
        if self.records:
            time.sleep(self.pause)
        request = f"{register}({length:02X})"
        request_frame = frame("R1", request)
        sent_at = datetime.now(timezone.utc).isoformat()
        record = {"op": "R1", "register": register, "requested_length": length,
                  "sent_at_utc": sent_at, "request_frame_hex": request_frame.hex().upper()}
        self.records.append(record)
        self.progress("read_request", register=register, length=length)
        self.port.send(request_frame)
        try:
            response = self.port.read_response()
        except (OSError, ProtocolError) as exc:
            self.progress("read_error", register=register, error=str(exc))
            raise
        record.update(received_at_utc=datetime.now(timezone.utc).isoformat(),
                      response_frame_hex=response.hex().upper())
        if response == bytes([NAK]):
            raise ProtocolError(f"Meter rejected R1 {request} (NAK)")
        value = decode_hex_payload(unpack_frame(response))
        if not 0 < len(value) <= 0x40:
            raise ProtocolError(f"R1 {request} returned {len(value)} bytes")
        record.update(returned_length=len(value), hex=value.hex().upper())
        self.progress("read_response", register=register, length=len(value), hex=value.hex().upper())
        return value

    def select(self, register, hex_value):
        if not re.fullmatch(r"[0-9A-Fa-f]{6}", register) or not re.fullmatch(r"[0-9A-Fa-f]+", hex_value):
            raise ValueError("Invalid selector")
        self.port.send(frame("W1", f"{register}({hex_value})"))
        require_ack(self.port.read_response(), f"W1 {register}")
        self.records.append({"op": "W1", "register": register, "hex": hex_value.upper()})

    def close(self):
        if self.logged_on:
            self.port.send(frame("B0"))
            self.logged_on = False

    def current(self):
        """Capture the PMU Current Value request sequence, without field decoding."""
        captured = []
        for register, size in CURRENT_VALUE_REQUESTS:
            value = self.read(register, size)
            if len(value) != size:
                raise ProtocolError(
                    f"R1 {register}({size:02X}) returned {len(value)} bytes, expected {size}")
            captured.append({"register": register, "length": size,
                             "raw_hex": value.hex().upper()})
        return captured

    def instantaneous(self, poll_limit=8):
        selectors = {
            "current": "1A2A4A00", "voltage": "1B2B4B00",
            "active_power": "1C2C4C0C", "reactive_power": "1D2D4D0D",
            "apparent_power": "1E2E4E0E", "power_factor": "13234303",
            "frequency": "18284800", "phase_angle": "19294900",
            "phase_sequence": "07000000",
        }
        result = {}
        for name, selector in selectors.items():
            self.select("605001", selector)
            for _ in range(poll_limit):
                status = self.read("605001", 4)
                if all(byte & 0x80 for byte in status):
                    break
            else:
                raise ProtocolError(f"Instantaneous {name} not ready after {poll_limit} polls")
            result[name] = self.read("606001", 0x1C).hex().upper()
        return result

    def historical(self, sets=1):
        if not 1 <= sets <= 12:
            raise ValueError("Historical sets must be 1..12")
        self.read("667001", 0x20)
        self.read("668001", 8)
        for set_index in range(sets):
            for n in range(set_index * 14 + 1, set_index * 14 + 15):
                self.read(f"543{n:03x}", 0x1F if n % 14 == 0 else 0x40)
            for n in range(set_index * 3 + 1, set_index * 3 + 4):
                self.read(f"544{n:03x}", 7 if n % 3 == 0 else 0x40)
            self.read(f"548{set_index * 3 + 1:03x}", 0x40)

    def profile(self, kind, selector="FFFF", max_blocks=256):
        control, data = {"load-profile": ("551001", "550"),
                         "instrumentation-profile": ("556001", "555")}[kind]
        self.select(control, selector)
        count_bytes = self.read(control, 2)
        if len(count_bytes) != 2:
            raise ProtocolError(f"Profile count returned {len(count_bytes)} bytes, expected 2")
        count = int.from_bytes(count_bytes, "big")
        if not 0 < count <= max_blocks:
            raise ProtocolError(f"Profile block count {count} outside allowed range 1..{max_blocks}")
        blocks = []
        for i in range(1, count + 1):
            block = self.read(f"{data}{i:03X}", 0x40)
            if len(block) != 0x40:
                raise ProtocolError(f"Profile block {i} returned {len(block)} bytes, expected 64")
            blocks.append(block)
        return {"block_count": count, "raw_hex": b"".join(blocks).hex().upper()}

    def events(self):
        for register in ("750001", "751001", "752001", "753001", "754001"):
            self.read(register, 0x0B)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", choices=["identify", "current", "instantaneous", "historical",
                                          "load-profile", "instrumentation-profile", "events"])
    parser.add_argument("--port", default="/dev/ttyUSB0")
    parser.add_argument("--address", default="", help="Meter outstation address; blank sends /?!")
    parser.add_argument("--auth-helper", help="Executable: receives challenge, serial, scheme; prints 16 hex digits")
    parser.add_argument("--password-file", type=Path, help="File containing the authorized 8-byte level-1 password")
    parser.add_argument("--selector", default="FFFF", help="Profile selector from a verified PMU log (default FFFF)")
    parser.add_argument("--historical-sets", type=int, default=1)
    parser.add_argument("--max-blocks", type=int, default=256)
    parser.add_argument("--output", type=Path, help="JSON output path; stdout if omitted")
    parser.add_argument("--trace-file", type=Path, help="Save live progress events as JSON Lines")
    parser.add_argument("--original-log", type=Path, help="Save one PMU-style raw TX/RX log (contains session authentication frames)")
    args = parser.parse_args(argv)
    if sum(bool(x) for x in (args.auth_helper, args.password_file)) > 1:
        parser.error("Choose only one authentication source")
    if args.group in ("load-profile", "instrumentation-profile") and not re.fullmatch(r"[0-9A-Fa-f]{4}", args.selector):
        parser.error("--selector must contain four hex digits")
    result = {"group": args.group, "port": args.port, "status": "incomplete", "registers": []}
    driver = None
    trace_stream = args.trace_file.open("w", encoding="utf-8") if args.trace_file else None
    wire_stream = None
    if args.original_log:
        descriptor = os.open(args.original_log, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        wire_stream = os.fdopen(descriptor, "w", encoding="utf-8")
        os.chmod(args.original_log, 0o600)
        wire_stream.write(f"[{datetime.now().astimezone().strftime('%H:%M:%S')}] Logon\n")
        wire_stream.flush()
    def on_wire(direction, data):
        marker = "TTYUSB0->" if direction == "TX" else "<-TTYUSB0"
        line = f"[{datetime.now().astimezone().strftime('%H:%M:%S')}] {marker}  {render_wire(data)}\n"
        if wire_stream:
            wire_stream.write(line)
            wire_stream.flush()
    def progress(event, **details):
        line = json.dumps({"at_utc": datetime.now(timezone.utc).isoformat(),
                           "event": event, **details}, ensure_ascii=False)
        print(line, file=sys.stderr, flush=True)
        if trace_stream:
            trace_stream.write(line + "\n")
            trace_stream.flush()
    try:
        with SerialPort(args.port, on_wire=on_wire if wire_stream else None) as port:
            driver = A1700(port, args.address, args.auth_helper, args.password_file,
                           progress=progress)
            try:
                driver.open()
                result.update(identity=driver.identity, serial=driver.serial, scheme=driver.scheme)
                if args.group != "identify":
                    driver.elevate()
                    if args.group == "current": result["current_raw_blocks"] = driver.current()
                    elif args.group == "instantaneous": result["instantaneous_raw"] = driver.instantaneous()
                    elif args.group == "historical": driver.historical(args.historical_sets)
                    elif args.group in ("load-profile", "instrumentation-profile"):
                        result["profile"] = driver.profile(args.group, args.selector.upper(), args.max_blocks)
                    elif args.group == "events": driver.events()
                result["status"] = "complete"
            finally:
                driver.close()
    except (OSError, ProtocolError, ValueError, subprocess.SubprocessError) as exc:
        result["error"] = str(exc)
        progress("session_error", error=str(exc))
    finally:
        if driver:
            result["registers"] = driver.records
            result.update(identity=driver.identity, serial=driver.serial, scheme=driver.scheme)
        progress("session_end", status=result["status"], captured_registers=len(result["registers"]))
        if trace_stream:
            trace_stream.close()
        if wire_stream:
            wire_stream.write(f"[{datetime.now().astimezone().strftime('%H:%M:%S')}] Logoff ({result['status']})\n")
            wire_stream.close()
        output = json.dumps(result, indent=2, ensure_ascii=False)
        if args.output:
            args.output.write_text(output + "\n", encoding="utf-8")
            print(f"Saved {args.output.resolve()}")
        else:
            print(output)
    return 0 if result["status"] == "complete" else 1


if __name__ == "__main__":
    sys.exit(main())
