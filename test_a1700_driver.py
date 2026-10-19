import unittest
from unittest.mock import patch

from a1700_driver import (A1700, ProtocolError, bcc, decode_hex_payload,
                          CURRENT_VALUE_REQUESTS, LOCAL_PASSWORD_FILE, encrypt_password, frame,
                          recover_password, render_wire, unpack_frame)


def reply(payload, prefix=b"\x02"):
    body = prefix + payload + b"\x03"
    return body + bytes([bcc(body[1:])])


class FakePort:
    def __init__(self, responses):
        self.responses = list(responses)
        self.sent = []

    def send(self, data):
        self.sent.append(data)

    def read_line(self):
        return self.responses.pop(0)

    def read_response(self):
        return self.responses.pop(0)


class DriverTests(unittest.TestCase):
    def test_pmu_frame_and_response_checksum(self):
        self.assertEqual(frame("R1", "798001(10)"), b"\x01R1\x02798001(10)\x03e")
        raw = reply(b"(2D2D2D2D2D2D2D2D3231303030353638)")
        self.assertEqual(decode_hex_payload(unpack_frame(raw, 16)), b"--------21000568")
        with self.assertRaises(ProtocolError):
            unpack_frame(raw[:-1] + b"x", 16)

    def test_wire_log_represents_control_bytes_without_loss(self):
        self.assertEqual(render_wire(b"\x01R1\x02(10)\x03\x0a"),
                         "<SOH>R1<STX>(10)<ETX><LF>")

    @patch("a1700_driver.time.sleep")
    def test_identification_matches_captured_meter(self, _sleep):
        port = FakePort([
            b"/GEC5090100301113@000\r\n",
            reply(b"P0\x02(ADBA5E57D7508077)", prefix=b"\x01"),
            b"\x06",
            reply(b"(2D2D2D2D2D2D2D2D3231303030353638)"),
            reply(b"(41313730302D2D2D)"),
        ])
        driver = A1700(port)
        driver.open()
        self.assertEqual(driver.challenge, "ADBA5E57D7508077")
        self.assertEqual(driver.serial, "21000568")
        self.assertEqual(driver.scheme, "A1700")
        self.assertEqual(port.sent[0], b"/?!\r\n")
        self.assertEqual(port.sent[1], b"\x06051\r\n")
        self.assertEqual(port.sent[2], frame("P2", "(0000000000000000)"))

    def test_profile_uses_count_as_hex_and_sequential_blocks(self):
        # Each profile transport block is 0x40 bytes, independently BCC checked.
        port = FakePort([b"\x06", reply(b"(0002)"),
                         reply(b"(" + b"AA" * 64 + b")"),
                         reply(b"(" + b"BB" * 64 + b")")])
        driver = A1700(port)
        data = driver.profile("load-profile")
        self.assertEqual(data["block_count"], 2)
        self.assertEqual(len(data["raw_hex"]), 256)
        self.assertEqual(port.sent, [frame("W1", "551001(FFFF)"),
                                     frame("R1", "551001(02)"),
                                     frame("R1", "550001(40)"),
                                     frame("R1", "550002(40)")])

    def test_profile_rejects_excessive_count(self):
        driver = A1700(FakePort([b"\x06", reply(b"(FFFF)")]))
        with self.assertRaises(ProtocolError):
            driver.profile("instrumentation-profile")

    def test_instantaneous_waits_for_ready_bits(self):
        port = FakePort([b"\x06", reply(b"(1A2A4A00)"),
                         reply(b"(9AAACA80)"), reply(b"(" + b"00" * 28 + b")")])
        driver = A1700(port)
        # Exercise one selector while keeping the real command sequence.
        driver.select("605001", "1A2A4A00")
        status = driver.read("605001", 4)
        self.assertFalse(all(x & 0x80 for x in status))
        status = driver.read("605001", 4)
        self.assertTrue(all(x & 0x80 for x in status))
        self.assertEqual(len(driver.read("606001", 0x1C)), 28)

    def test_current_follows_pmu_request_order_and_keeps_raw_blocks(self):
        driver = A1700(FakePort([]))
        calls = []
        def fake_read(register, length):
            calls.append((register, length))
            return bytes([len(calls) & 0xFF]) * length
        driver.read = fake_read
        blocks = driver.current()
        self.assertEqual(calls, list(CURRENT_VALUE_REQUESTS))
        self.assertEqual(len(blocks), 18)
        self.assertEqual(blocks[2], {"register": "507001", "length": 64,
                                      "raw_hex": "03" * 64})

    def test_current_rejects_short_response(self):
        driver = A1700(FakePort([]))
        driver.read = lambda register, length: bytes(length - 1)
        with self.assertRaisesRegex(ProtocolError, "667001.*31 bytes, expected 32"):
            driver.current()

    def test_register_capture_records_wire_frames_and_time(self):
        response = reply(b"(01020304)")
        port = FakePort([response])
        driver = A1700(port)
        self.assertEqual(driver.read("605001", 4), b"\x01\x02\x03\x04")
        record = driver.records[0]
        self.assertEqual(record["request_frame_hex"], port.sent[0].hex().upper())
        self.assertEqual(record["response_frame_hex"], response.hex().upper())
        self.assertIn("+00:00", record["received_at_utc"])

    def test_register_capture_preserves_rejected_response(self):
        driver = A1700(FakePort([b"\x15"]))
        with self.assertRaisesRegex(ProtocolError, "NAK"):
            driver.read("667001", 0x20)
        self.assertEqual(driver.records[0]["response_frame_hex"], "15")

    def test_password_algorithm_matches_user_supplied_session(self):
        seed = bytes.fromhex('ADBA5E57D7508077')
        response = bytes.fromhex('D05AC82EC4D699CC')
        password = recover_password(seed, response)
        self.assertEqual(encrypt_password(password, seed), response)

    @unittest.skipUnless(LOCAL_PASSWORD_FILE.is_file(), 'Local meter key unavailable')
    def test_local_key_allows_login_without_workbook_argument(self):
        port = FakePort([b'\x06'])
        driver = A1700(port, pause=0)
        driver.serial = '21000568'
        driver.challenge = 'ADBA5E57D7508077'
        driver.elevate()
        self.assertEqual(port.sent, [frame('P2', '(D05AC82EC4D699CC)')])


if __name__ == "__main__":
    unittest.main()
