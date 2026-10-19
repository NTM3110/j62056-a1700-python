# j62056 source functionality and meter-profile review

Reviewed 2026-09-14 in `/home/shou/Documents/ATEnergy/j62056`. Version: 2.2.0 (`configuration.gradle`, line 4).

## Result

This source implements ordinary IEC 62056-21 meter readout in modes A, B and C, plus unsolicited mode D reception. There is **no dedicated historical/load-profile reader** in the production source.

The review covers all 26 Java files under `src/main/java`, containing 166 explicitly declared methods and constructors. This includes private/package methods, overloads, abstract/interface declarations, nested classes and the anonymous shutdown-hook method. It excludes inherited methods, implicit constructors, compiler-generated enum methods, dependency internals and tests.

## Functions provided

| Area | Functions |
|---|---|
| Serial connection | Open a jRxTx serial port with 7 data bits, even parity and 1 stop bit; configure initial baud rate, timeout and baud-change delay; close and check closed state. |
| Readout | Send an optionally addressed request, parse identification, acknowledge mode C, change baud for B/C as applicable, receive and validate one data message, restore initial baud on success. |
| Mode D | Start a receiver thread, receive unsolicited messages, call the supplied data/error listener, restore infinite receive timeout after each message. |
| Meter selection | Optionally send manufacturer selection data and check the exact expected selection reply before readout. |
| Configuration | Customize request prefix, device address, fixed baud, verbosity and acknowledgement mode. |
| Returned data | Retrieve manufacturer ID, meter ID, enhanced ID/capability and a list of data sets. Each set exposes address, string value and string unit. |
| Validation | Validate framing and A/B/C block check character (XOR BCC); reject malformed datasets or excessive field length. |
| Low-level access | Expose the underlying serial port and input/output streams for custom communication. |
| Console application | Read once and print the message, or listen for mode D messages; show usage, report errors and close on shutdown. |
| Internal utilities | Build/parse protocol messages, convert hexadecimal data, print debug output, parse typed CLI arguments and generate help text. |

Core API: `Iec21Port.Builder` supplies `setBaudRateChangeDelay`, `setInitialBaudrate`, `setTimeout`, `setDeviceAddress`, `setRequestStartCharacters`, `setSelectManufacturerData`, `setSelectExpectData`, `setAcknowledgeMode`, `enableVerboseMode`, `enableFixedBaudrate` and `buildAndOpen`.

`Iec21Port` exposes `read`, `listen`, `close`, `isClosed`, `getSerialPort`, `getInputStream` and `getOutputStream`. `DataMessage` exposes `getManufacturerId`, `getMeterId`, `getEnhancedId`, `getDataSets` and `toString`. `DataSet` exposes `getAddress`, `getValue`, `getUnit` and `toString`. `ModeDListener` defines `newDataMessage` and `exceptionWhileListening`.

Console options: `-p` serial port (required), `-b` initial baud, `-d` baud-change delay, `-t` timeout, `-v` verbose, `-f` fixed baud, `-l` mode D listening, `-a` device address, `-rsc` request prefix, and `--help`.

Library defaults: 300 baud for A/B/C, 2400 for D, 5000 ms timeout, zero baud-change delay. The console explicitly defaults `-b` to 300 and passes that to the builder even when listening, so use `-b 2400` for a meter requiring 2400-baud mode D.

## Evidence for the profile-reading conclusion

1. [Iec21Port.read()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:365) has no profile identifier, start/end time, entry range or channel-selection argument. It follows selection → request → identification → acknowledgement/baud change → ordinary data-message parsing.
2. [readModeAbcDataMessage()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:65) expects STX, ordinary datasets, `!`, CR/LF, ETX and BCC. There is no profile block transfer, continuation or historical query workflow.
3. [DataSet.readDataSet()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:51) parses `address(value*unit)` into three strings. It has no dedicated timestamped interval-record model or profile schema. Address, value and unit parsing each use a 100-byte buffer.
4. [AcknowledgeMode](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMode.java:31) includes `PROGRAMMING` and `BINARY`, and the builder lets callers choose an acknowledgement character. However, [handleModeC()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:444) only sends the acknowledgement; `read()` subsequently calls the same ordinary ASCII data parser. These constants do not implement programming commands, authentication, DLMS/COSEM or historical retrieval.
5. [Raw serial/stream getters](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:328) can support additional caller-written protocol code, but the repository does not supply a complete profile reader on top of them.

A meter could include stored readings or date strings in its ordinary readout, and this code may expose them if they fit the supported dataset grammar. That is different from requesting a meter's historical/load-profile archive. Repeatedly polling and storing ordinary readings would create a history from the polling start time; it would not retrieve past intervals already stored in the meter.

To add profile retrieval, first identify the meter model and its documented profile-access protocol. Then implement its session/authentication requirements, profile selection, range requests, block handling and record decoding. The source alone cannot establish which profile commands a particular meter accepts.

## Validation scope

This is a static source review, with call-flow inspection and a declaration inventory checked against the source. No meter was connected and no hardware communication was tested. The existing test source contains one BCC test method with two checksum examples; it does not validate meter/profile communication. Production code was not changed.

## Complete declaration inventory

Each declaration below links to its current source line. Visibility and overload parameters are preserved. A public declaration inside an internal or private nested class is not necessarily part of the supported application API.

### DataMessage (8 declarations)

Parses complete readout messages, checks framing/BCC and exposes meter identity and datasets.

- [Line 53](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:53): `private DataMessage(String manufacturerId, String meterId, String enhancedId, List<DataSet> dataSets)`
- [Line 65](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:65): `static DataMessage readModeAbcDataMessage(DataInputStream is, IdentificationMessage identificationMessage) throws IOException`
- [Line 110](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:110): `static DataMessage readModeDDataMessage(DataInputStream is, IdentificationMessage identificationMessage, SerialPort serialPort) throws IOException`
- [Line 152](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:152): `public String getManufacturerId()`
- [Line 161](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:161): `public String getMeterId()`
- [Line 170](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:170): `public String getEnhancedId()`
- [Line 179](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:179): `public List<DataSet> getDataSets()`
- [Line 184](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataMessage.java:184): `public String toString()`

### DataSet (8 declarations)

Parses individual address/value/unit records and exposes their string fields.

- [Line 45](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:45): `private DataSet(String id, String value, String unit)`
- [Line 51](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:51): `static DataSet readDataSet(DataInputStream is, Bcc bcc) throws IOException`
- [Line 85](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:85): `private static String readUnit(DataInputStream is, Bcc bcc, byte b, byte[] buffer) throws IOException`
- [Line 101](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:101): `private static String readAddress(DataInputStream is, Bcc bcc, byte b, byte[] buffer) throws IOException`
- [Line 126](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:126): `public String getAddress()`
- [Line 140](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:140): `public String getValue()`
- [Line 151](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:151): `public String getUnit()`
- [Line 156](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/DataSet.java:156): `public String toString()`

### Iec21Port (27 declarations)

Serial lifecycle and read/listen workflows. Lines 73–88 belong to ModeDReceiver; lines 134–290 to Builder; remaining declarations to Iec21Port.

- [Line 73](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:73): `public void run()`
- [Line 88](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:88): `private void clearInputStream()`
- [Line 134](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:134): `public Builder(String serialPortName)`
- [Line 153](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:153): `public Builder setBaudRateChangeDelay(int baudRateChangeDelay)`
- [Line 168](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:168): `public Builder setInitialBaudrate(int initialBaudrate)`
- [Line 183](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:183): `public Builder setTimeout(int timeout)`
- [Line 197](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:197): `public Builder setDeviceAddress(String deviceAddress)`
- [Line 211](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:211): `public Builder setRequestStartCharacters(String requestStartCharacters)`
- [Line 223](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:223): `public Builder setSelectManufacturerData(String selectManufacturerData)`
- [Line 235](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:235): `public Builder setSelectExpectData(String selectExpectData)`
- [Line 247](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:247): `public Builder setAcknowledgeMode(char acknowledgeMode)`
- [Line 261](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:261): `public Builder enableVerboseMode(boolean verbose)`
- [Line 276](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:276): `public Builder enableFixedBaudrate(boolean fixedBaudRate)`
- [Line 290](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:290): `public Iec21Port buildAndOpen() throws IOException`
- [Line 296](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:296): `private Iec21Port(Builder builder) throws IOException`
- [Line 328](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:328): `public final SerialPort getSerialPort()`
- [Line 332](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:332): `public final DataOutputStream getOutputStream()`
- [Line 336](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:336): `public final DataInputStream getInputStream()`
- [Line 345](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:345): `public void close()`
- [Line 365](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:365): `public DataMessage read() throws IOException`
- [Line 397](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:397): `private void clearInputStream() throws IOException`
- [Line 408](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:408): `private void selectMessage() throws IOException`
- [Line 424](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:424): `private void setInitialBaudrate() throws IOException`
- [Line 433](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:433): `private void changeBaudrate(IdentificationMessage identificationMessage) throws IOException`
- [Line 444](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:444): `private void handleModeC(IdentificationMessage identificationMessage) throws IOException`
- [Line 475](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:475): `public boolean isClosed()`
- [Line 487](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:487): `public void listen(ModeDListener listener) throws IOException`

### ModeDListener (2 declarations)

Callbacks for received data and listening errors.

- [Line 37](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/ModeDListener.java:37): `public void newDataMessage(DataMessage dataMessage)`
- [Line 45](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/ModeDListener.java:45): `public void exceptionWhileListening(Exception e)`

### ConsoleLineParser (3 declarations)

Defines the reader options and delegates parsing/help generation.

- [Line 76](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/ConsoleLineParser.java:76): `ConsoleLineParser()`
- [Line 93](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/ConsoleLineParser.java:93): `public void parse(String[] args) throws CliParseException`
- [Line 97](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/ConsoleLineParser.java:97): `public void printUsage()`

### Reader (7 declarations)

Console entry point and output helpers. Lines 41–46 implement ModeDListener; line 86 is the anonymous shutdown hook.

- [Line 41](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:41): `public void newDataMessage(DataMessage dataMessage)`
- [Line 46](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:46): `public void exceptionWhileListening(Exception e)`
- [Line 52](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:52): `public static void main(String[] args)`
- [Line 86](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:86): `public void run()`
- [Line 116](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:116): `private static void printLnErr(String... text)`
- [Line 120](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:120): `private static void printLn(String... text)`
- [Line 124](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/Reader.java:124): `private static String conCat(String... text)`

### AcknowledgeMessage (4 declarations)

Encodes/sends acknowledgement with protocol control, baud code and mode.

- [Line 45](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMessage.java:45): `public AcknowledgeMessage(int baudRate, ProtocolControlCharacter protocolControlCharacter, AcknowledgeMode acknowledgeMode)`
- [Line 54](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMessage.java:54): `public void send(DataOutputStream os) throws IOException`
- [Line 60](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMessage.java:60): `public String toString()`
- [Line 65](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMessage.java:65): `private static byte getCodeFromBaudRate(int baudRate)`

### AcknowledgeMode (3 declarations)

Maps acknowledgement choices to protocol characters.

- [Line 40](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMode.java:40): `private AcknowledgeMode(char value)`
- [Line 44](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMode.java:44): `public char value()`
- [Line 48](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/AcknowledgeMode.java:48): `public static AcknowledgeMode valueOf(char mode)`

### Bcc (3 declarations)

Calculates an XOR checksum and stores an incremental checksum value.

- [Line 28](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Bcc.java:28): `public static byte calc(byte[] bytes)`
- [Line 37](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Bcc.java:37): `public byte getValue()`
- [Line 41](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Bcc.java:41): `public void setValue(byte value)`

### Helper (3 declarations)

Debug output and checksum-updating byte reads.

- [Line 33](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Helper.java:33): `public static void debug(Object... objects)`
- [Line 48](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Helper.java:48): `public static byte readByteAndCalculateBcc(DataInputStream is, Bcc bcc) throws IOException`
- [Line 54](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/Helper.java:54): `private Helper()`

### HexConverter (15 declarations)

Converts bytes/numbers to hexadecimal text and parses hexadecimal strings.

- [Line 7](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:7): `public static String toHexString(byte b)`
- [Line 13](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:13): `public static String toHexString(byte[] bytes)`
- [Line 17](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:17): `public static String toHexString(byte[] bytes, int offset, int length)`
- [Line 46](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:46): `public static String toShortHexString(int i)`
- [Line 59](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:59): `public static String toShortHexString(long l)`
- [Line 72](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:72): `public static String toShortHexString(byte b)`
- [Line 78](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:78): `public static String toShortHexString(byte[] bytes)`
- [Line 82](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:82): `public static String toShortHexString(byte[] bytes, int offset, int length)`
- [Line 92](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:92): `public static byte[] fromShortHexString(String shortHexString)`
- [Line 112](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:112): `public static void appendShortHexString(byte b, StringBuilder builder)`
- [Line 116](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:116): `public static void appendShortHexString(StringBuilder builder, byte[] bytes, int offset, int length)`
- [Line 120](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:120): `public static void appendHexString(byte b, StringBuilder builder)`
- [Line 125](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:125): `public static void appendHexString(StringBuilder builder, byte[] byteArray, int offset, int length)`
- [Line 142](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:142): `private static void validate(String s)`
- [Line 155](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/HexConverter.java:155): `private HexConverter()`

### IdentificationMessage (16 declarations)

Parses manufacturer, meter and enhanced identification, detects baud/mode and describes capability text.

- [Line 45](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:45): `public IdentificationMessage(DataInputStream is) throws IOException`
- [Line 66](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:66): `private void checkIdentificationMessageStartByte(byte b) throws IOException`
- [Line 73](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:73): `private String getManufactureId(DataInputStream is) throws IOException`
- [Line 79](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:79): `private String getMeterId(DataInputStream is, byte b) throws IOException`
- [Line 93](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:93): `private void checkEndOfMessage(DataInputStream is) throws IOException`
- [Line 102](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:102): `private void parseMode(DataInputStream is) throws IOException`
- [Line 149](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:149): `private void setMode(int baudrate, ProtocolMode protocolMode)`
- [Line 154](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:154): `private void setModeC(int baudrate)`
- [Line 158](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:158): `private void setModeB(int baudrate)`
- [Line 162](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:162): `public String getManufactureId()`
- [Line 166](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:166): `public String getMeterId()`
- [Line 170](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:170): `public String getEnhancedId()`
- [Line 174](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:174): `public int getBaudRate()`
- [Line 178](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:178): `public ProtocolMode getProtocolMode()`
- [Line 183](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:183): `public String toString()`
- [Line 190](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/IdentificationMessage.java:190): `public static String getEnhancedIdDescription(String enhancedId)`

### ProtocolControlCharacter (3 declarations)

Maps protocol-control choices to characters; enum constants are not full protocol implementations.

- [Line 46](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/ProtocolControlCharacter.java:46): `private ProtocolControlCharacter(char value)`
- [Line 50](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/ProtocolControlCharacter.java:50): `public char value()`
- [Line 54](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/ProtocolControlCharacter.java:54): `public static ProtocolControlCharacter valueOf(char tagValue)`

### ProtocolMode (0 declarations)

Enum constants A, B, C and D. No explicitly declared methods or constructors.


### RequestMessage (4 declarations)

Builds and sends readout requests with optional address/custom prefix.

- [Line 43](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/RequestMessage.java:43): `public RequestMessage(String deviceAddress)`
- [Line 57](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/RequestMessage.java:57): `public RequestMessage(String deviceAddress, String startCharacters)`
- [Line 69](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/RequestMessage.java:69): `public void send(DataOutputStream os) throws IOException`
- [Line 75](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/RequestMessage.java:75): `public String toString()`

### SelectMessage (4 declarations)

Builds and sends optional manufacturer selection data.

- [Line 43](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectMessage.java:43): `public SelectMessage(String manufacturerData)`
- [Line 48](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectMessage.java:48): `public void send(DataOutputStream os) throws IOException`
- [Line 54](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectMessage.java:54): `public String toString()`
- [Line 58](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectMessage.java:58): `public boolean isEmpty()`

### SelectReplyMessage (3 declarations)

Reads and exposes the manufacturer selection reply.

- [Line 34](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectReplyMessage.java:34): `public SelectReplyMessage(DataInputStream is) throws IOException`
- [Line 58](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectReplyMessage.java:58): `public String getReplyMessage()`
- [Line 63](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/SelectReplyMessage.java:63): `public String toString()`

### CliParameter (8 declarations)

Base metadata and abstract parsing/help contracts.

- [Line 10](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:10): `CliParameter(CliParameterBuilder builder)`
- [Line 19](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:19): `public String getName()`
- [Line 26](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:26): `public String getDescription()`
- [Line 33](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:33): `public boolean isOptional()`
- [Line 37](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:37): `public boolean isSelected()`
- [Line 41](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:41): `abstract int parse(String[] args, int i) throws CliParseException`
- [Line 43](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:43): `abstract int appendSynopsis(StringBuilder sb)`
- [Line 45](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameter.java:45): `abstract void appendDescription(StringBuilder sb)`

### CliParameterBuilder (10 declarations)

Builds mandatory/optional flag, integer, long and string parameters, with optional defaults.

- [Line 9](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:9): `public CliParameterBuilder(String name)`
- [Line 13](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:13): `public CliParameterBuilder setDescription(String description)`
- [Line 18](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:18): `public CliParameterBuilder setMandatory()`
- [Line 23](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:23): `public LongCliParameter buildLongParameter(String parameterName, long defaultValue)`
- [Line 27](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:27): `public LongCliParameter buildLongParameter(String parameterName)`
- [Line 31](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:31): `public IntCliParameter buildIntParameter(String parameterName, int defaultValue)`
- [Line 35](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:35): `public IntCliParameter buildIntParameter(String parameterName)`
- [Line 39](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:39): `public StringCliParameter buildStringParameter(String parameterName, String defaultValue)`
- [Line 43](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:43): `public StringCliParameter buildStringParameter(String parameterName)`
- [Line 47](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParameterBuilder.java:47): `public FlagCliParameter buildFlagParameter()`

### CliParseException (4 declarations)

Exception constructors for CLI parse errors.

- [Line 7](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParseException.java:7): `public CliParseException()`
- [Line 11](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParseException.java:11): `public CliParseException(String s)`
- [Line 15](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParseException.java:15): `public CliParseException(Throwable cause)`
- [Line 19](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParseException.java:19): `public CliParseException(String s, Throwable cause)`

### CliParser (9 declarations)

Manages parameter groups, parses options, checks mandatory options and builds usage text. Line 21 belongs to nested ParameterGroup.

- [Line 21](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:21): `public ParameterGroup(String name, List<CliParameter> parameters)`
- [Line 29](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:29): `public CliParser(String name, String description)`
- [Line 34](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:34): `public void addParameterGroup(String groupName, List<CliParameter> parameters)`
- [Line 38](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:38): `public void addParameters(List<CliParameter> parameters)`
- [Line 43](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:43): `public String getSelectedGroup()`
- [Line 47](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:47): `public void parseArguments(String[] args) throws CliParseException`
- [Line 79](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:79): `private List<CliParameter> getParameters(String[] args, List<CliParameter> parameters)`
- [Line 89](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:89): `private void parseArguments(String[] args, List<CliParameter> parameters, int i) throws CliParseException`
- [Line 105](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/CliParser.java:105): `public String getUsageString()`

### FlagCliParameter (4 declarations)

Parses a flag and formats its help.

- [Line 5](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/FlagCliParameter.java:5): `FlagCliParameter(CliParameterBuilder builder)`
- [Line 10](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/FlagCliParameter.java:10): `int appendSynopsis(StringBuilder sb)`
- [Line 26](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/FlagCliParameter.java:26): `void appendDescription(StringBuilder sb)`
- [Line 31](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/FlagCliParameter.java:31): `int parse(String[] args, int i) throws CliParseException`

### IntCliParameter (5 declarations)

Parses an integer option and exposes its value/default description.

- [Line 8](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/IntCliParameter.java:8): `IntCliParameter(CliParameterBuilder builder, String parameterName, int defaultValue)`
- [Line 14](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/IntCliParameter.java:14): `IntCliParameter(CliParameterBuilder builder, String parameterName)`
- [Line 18](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/IntCliParameter.java:18): `public int getValue()`
- [Line 23](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/IntCliParameter.java:23): `int parse(String[] args, int i) throws CliParseException`
- [Line 39](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/IntCliParameter.java:39): `void appendDescription(StringBuilder sb)`

### LongCliParameter (5 declarations)

Parses a long option and exposes its value/default description.

- [Line 8](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/LongCliParameter.java:8): `LongCliParameter(CliParameterBuilder builder, String parameterName, long defaultValue)`
- [Line 14](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/LongCliParameter.java:14): `LongCliParameter(CliParameterBuilder builder, String parameterName)`
- [Line 18](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/LongCliParameter.java:18): `public long getValue()`
- [Line 23](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/LongCliParameter.java:23): `int parse(String[] args, int i) throws CliParseException`
- [Line 39](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/LongCliParameter.java:39): `void appendDescription(StringBuilder sb)`

### StringCliParameter (5 declarations)

Parses a string option and exposes its value/default description.

- [Line 8](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/StringCliParameter.java:8): `StringCliParameter(CliParameterBuilder builder, String parameterName, String defaultValue)`
- [Line 14](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/StringCliParameter.java:14): `StringCliParameter(CliParameterBuilder builder, String parameterName)`
- [Line 18](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/StringCliParameter.java:18): `public String getValue()`
- [Line 23](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/StringCliParameter.java:23): `int parse(String[] args, int i) throws CliParseException`
- [Line 35](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/StringCliParameter.java:35): `public void appendDescription(StringBuilder sb)`

### ValueCliParameter (3 declarations)

Shared synopsis and description formatting for options taking a value.

- [Line 7](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/ValueCliParameter.java:7): `ValueCliParameter(CliParameterBuilder builder, String parameterName)`
- [Line 13](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/ValueCliParameter.java:13): `int appendSynopsis(StringBuilder sb)`
- [Line 29](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/internal/cli/ValueCliParameter.java:29): `void appendDescription(StringBuilder sb)`

