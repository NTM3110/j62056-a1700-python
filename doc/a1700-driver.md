# A1700 driver for meter 21000568

## Java driver update — 20 September 2026

The new Java entry point is `run-scripts/a1700`, with `--group current`,
`instantaneous`, `historical`, `load-profile`, or `all`. It uses the existing
`Iec21Port` transport and a shared P0/P2 session engine, generates requests without
PMU files, and writes decoded JSON plus a per-session original wire log.
See [the Java run guide](/home/shou/Documents/ATEnergy/j62056/doc/a1700-java-driver.md)
for commands, decoder coverage, offline/PTY validation and remaining hardware checks.

The material below describes the earlier raw-current/Python implementation.
Its historical `544` reads and multi-set address arithmetic are not used by the
new Java set-1 workflow, which follows the current meter's supplied PMU log.

## Legacy Java Current Value reader

The project's existing `Reader` and `Iec21Port` now support `-cv` for the 18
native A1700 Current Value R1 requests. The Java path uses `/dev/ttyUSB0` at
9600 7E1, performs the P0/P2 exchange using the local eight-byte credential,
checks every response BCC and length, and writes an owner-only original TX/RX
text log. It returns raw hexadecimal blocks; no numeric scaling is assumed.

```bash
sudo setserial /dev/ttyUSB0 port 3
./run-scripts/a1700-current
```

An optional first argument changes the serial device; a second changes the
log path. The default output is `current_java_original.log` in the project root.
The launch script compiles the Java source and then runs the existing `Reader`
entry point. The A1700 path uses Java device streams plus `stty`; the installed
RXTX JAR/native library combination crashed in native read/drain calls on this
host. Other `Reader` modes continue using their existing jrxtx path.

Live validation on meter `21000568` completed with 18/18 registers and exact
payload matches against both the PMU Current Value log and the Python capture.

`a1700_driver.py` implements the observed IEC 62056-21 mode-C/native-register
conversation at **9600 baud, 7 data bits, even parity, 1 stop bit**. It uses the
four-wire MOXA UPort 1150 setup already proven for this meter. It does not use
OBIS as an R1 address or assume that a 64-byte profile block is one interval.

## Source-backed command flow

1. Send `/?\r\n` (or `/?address!\r\n`) and require `/GEC5...` identification.
2. Send `ACK 051`, receive `P0(seed)`, send level-0 `P2(0000000000000000)`.
3. Read `R1 798001(10)` (serial) and `R1 795001(08)` (scheme).
4. For measurements, send another P2 calculated from the eight-byte seed and
   the authorized level-1 password. Validate the meter ACK.
5. Issue R1 requests, validate each BCC, decode the ASCII hex transport value
   into raw bytes, and finish with `B0`.

The thesis, printed pages 21-25, provides the mode-C outline and example
registers. The PMU capture from meter `21000568` was used to establish the
command sequences; the driver keeps the request list in code and does not read
the workbook to choose a request. For authentication the driver uses the algorithm published
by [Gurux](https://www.gurux.fi/node/5415): XOR each password byte with its seed
byte; start with the eighth XOR byte as the carry; successively add the carry
to each XOR byte modulo 256,
using each result as the next carry. The local, mode-0600 key file named
`.a1700_21000568.key` was derived solely from the user-supplied PMU session
at 16:40:52 (`P0=ADBA5E57D7508077`, `P2=D05AC82EC4D699CC`). The driver uses
it automatically after confirming meter serial `21000568`, calculating a new
P2 for each new P0 challenge. No workbook path is needed at runtime, and no
password or P2 response is written to JSON output. The one captured session
verifies the calculation against that session; live authentication with a new
challenge remains to be checked on the connected meter.

## Run

Plug in the MOXA first and confirm `/dev/ttyUSB0` exists. Configure the MOXA
driver for RS485 four-wire mode after each USB reconnection:

```bash
sudo setserial /dev/ttyUSB0 port 3
python3 a1700_driver.py identify
```

To read meter 21000568 using the saved local credential:

```bash
python3 a1700_driver.py current --output current.json

python3 a1700_driver.py instantaneous --output instantaneous.json

python3 a1700_driver.py historical --historical-sets 1 --output historical.json

python3 a1700_driver.py load-profile --selector FFFF --output load-profile.json

python3 a1700_driver.py instrumentation-profile --selector FFFF --output instrumentation-profile.json

python3 a1700_driver.py events --output events.json
```

The user can instead supply `--password-file` with an authorized eight-byte
level-1 password, or `--auth-helper` that receives the seed, serial and scheme
as arguments and writes a 16-character hex P2 response to stdout. Do not pass
the password directly on the shell command line.

## Register groups and evidence

| Group | Driver sequence | Evidence |
| --- | --- | --- |
| `identify` | `798001(10)`, `795001(08)` | PMU capture and successful live serial read |
| `current` | `861001(07)`, `667/668`, `507`, `508`, `509`, `510`, `511`, `516` groups | Thesis pp. 24-25; PMU `Current Value` sheet |
| `instantaneous` | `W1 605001(selector)`, poll `R1 605001(04)` until ready, then `R1 606001(1C)` for current, voltage, P, Q, S, PF, frequency, phase angle and rotation | Thesis pp. 24-25; PMU `Instantaneus Value` sheet |
| `historical` | Per set, fourteen `543` blocks, three `544` blocks and one `548` block | Thesis pp. 23-24; PMU `All Historical Registers` sheet, first set |
| `load-profile` | `W1 551001(FFFF)`, `R1 551001(02)` block count, then `R1 550001(40)` onward | PMU `Full Load Profile` sheet from this meter |
| `instrumentation-profile` | `W1 556001(FFFF)`, `R1 556001(02)` block count, then `R1 555001(40)` onward | PMU `Instrumentation profile data` sheet |
| `events` | `R1 750001` through `754001`, eleven bytes each | PMU `Inst. Events` sheet |

The thesis calls `556/555` *load profile* on printed page 23, while the PMU
capture from this particular meter labels it *instrumentation profile* and uses
`551/550` for full load profile. This driver follows the meter-specific capture.
`FFFF` and `0200` are observed selectors; the driver does not claim that they
mean “all dates” or “two days” because the selector format has not been verified.

## Output and remaining decoding work

The driver returns JSON with the meter identity, serial, scheme and every
checksum-checked register value as **raw hexadecimal bytes**. The `current`
command follows the 18 `R1` requests in the PMU workbook's `Current Value`
sheet, from `667001(20)` through `511002(38)`. It does not include `861001(07)`,
which belongs to a different PMU sequence. The `507001(40)` request is missing
from row 21 of this workbook copy; its 64-byte response remains in row 22, and
the request is present in the same sheet of `PMU_register.xlsx`.

Use `--output /absolute/path/current.json` to retain the actual read. The JSON
contains `current_raw_blocks` in request order. Each `registers` entry also
contains UTC send/receive times and the complete request/response frames in hex,
including the received BCC, so the capture can be compared with PMU later.
Use `--original-log /absolute/path/current_original.log` to also save a single
PMU-style text log of the original TX/RX bytes from identification through B0.
This log includes authentication frames and is created with owner-only read
permissions. `--trace-file` separately saves live progress events as JSON Lines.
The JSON is written even if a later request fails, preserving earlier responses.
The driver checks that every Current Value response contains the requested byte
count. It does not assign names, units, or scaling to fields yet. A profile result
also contains the concatenated block stream. A transport block boundary is not
a profile-record boundary. Numeric values, channel order, timestamp, tariff,
event meaning, scaling and transformer multipliers are not inferred from the
thesis's examples; a decoder needs the A1700 format for this scheme and comparison
with PMU exports from the same time window. The code will report a failed or
rejected command and preserve the raw registers received up to that point.

Live measurement, higher-level authentication and engineering-unit decoding
have not yet been verified on this host because `/dev/ttyUSB0` is currently absent.

## Local checks

```bash
python3 -m unittest -v test_a1700_driver.py
```

The tests cover frame/BCC handling, the captured serial conversation, profile
block iteration, readiness polling and the P2 algorithm against independent PMU
sessions.
