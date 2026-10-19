# Chạy driver Java A1700 với meter thật

Nếu muốn hiểu từ phần cứng, serial 7E1 đến từng bước đọc register, xem [A1700: từ kết nối đến giải mã](/home/shou/Documents/ATEnergy/j62056/doc/a1700-tu-ket-noi-den-giai-ma.md).

Driver dùng lại `Iec21Port` của dự án, có session P0/P2 và bốn workflow: Current, Instantaneous, Historical set 1, Load Profile. Request được sinh trong Java; lúc chạy không cần PDF, workbook, log PMU hay Python.

Phiên bản này đã được kiểm thử bằng phản hồi PMU và cổng serial giả lập. Khi hoàn thiện code ngày 20/09/2026, máy không có `/dev/ttyUSB0`, nên **chưa xác nhận bản mới trực tiếp trên meter thật**. PTY kiểm tra luồng byte và CLI; không kiểm chứng tín hiệu điện RS485 hoặc parity vật lý.

## 1. Chạy lần đầu

Cắm MOXA, giữ chế độ RS485 4-wire đã cấu hình cho adapter và đóng chương trình khác đang sử dụng cùng cổng. Driver đặt 9600 baud, 7 data bits, even parity, 1 stop bit; tắt flow control và terminal echo. Driver không tự thay đổi chế độ điện RS232/RS485 của MOXA.

Đọc Current trước:

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 \
  --port /dev/ttyUSB0 --address 005 --serial 21000568 --group current
```

Mặc định dùng **PMU password level 3 `FEDC0003`**, được chuyển thành đúng 8 byte ASCII. Có thể chọn credential khác bằng `--key /absolute/path/to/key`; file phải chứa đúng 8 byte. Không đưa chuỗi P2 từ log vào tham số key: response phải được tính lại từ P0 của phiên hiện tại.

Script biên dịch Java bằng JDK đang cài (JDK 9+ cho `--release 8`, máy hiện có JDK 21), dùng JAR local `build/libs-all/jrxtx-1.0.1.jar`. Không tải thư viện qua mạng. Bytecode target Java 8. CLI live hiện hỗ trợ Linux qua `stty -F`; nhánh device-stream tránh RXTX native không tương thích trên máy này.

## 2. Chọn nhóm dữ liệu

| `--group` | Request và đầu ra |
|---|---|
| `current` | Hai identity reads trong login; 18 R1 như Current PMU; 10 Totals, 32 Rates, 8 Cumulative MD đã gán tên/value/unit; giữ raw MU/MD/Co-incident |
| `instantaneous` | 9 selector `W1 605001`; poll ready có deadline và kiểm tra selector; đọc 28 byte từ `606001`; gán các slot A/B/C/Total theo nhóm |
| `historical` | Sources `667/668`, 14 block `543`, auxiliary `548`; parse set 1, giá trị và billing metadata; không tự thêm `544` |
| `load-profile` | `W1 551001(selector)`, đọc count, tải liên tiếp `550xxx`; parse cấu hình mẫu 4 kênh/30 phút, flags, timestamp và giữ raw events |
| `all` | Lần lượt cả bốn nhóm; mỗi nhóm có một phiên đăng nhập mới giống các capture PMU |

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group instantaneous
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group historical
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group load-profile --profile-selector 0100
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group all
```

Tải theo selector của log lịch sử dài:

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 \
  --group load-profile --profile-selector FFFF --max-profile-blocks 4095
```

`all` mặc định dùng `0100` để thử profile ngắn như capture 7 block. `FFFF` có thể trả nhiều block và chạy lâu hơn; chương trình hiển thị `Profile block i/n`. Hai selector là những giá trị đã quan sát, chưa phải API truy vấn ngày bắt đầu/kết thúc. Các suffix block là hexadecimal; không dừng ngầm ở 256.

Các tùy chọn khác:

```text
--port /dev/ttyUSB0
--address 005                 Dùng --address '' nếu cần sign-on không địa chỉ
--serial 21000568             Kiểm tra đúng meter trước khi gửi P2 có credential
--timeout-ms 8000             Deadline nhận trọn một response
--delay-ms 300                Khoảng chờ giữa các giao dịch
--signon-attempts 3           Chỉ retry sign-on khi không nhận byte nào
--poll-timeout-ms 15000       Thời hạn chờ ready của mỗi nhóm Instantaneous
--max-profile-blocks 4095     Giới hạn tài nguyên và miền suffix 3 ký tự hex
--output /absolute/directory  Thư mục gốc lưu các phiên
```

Không tự retry R1/W1 sau lỗi giữa phiên: response không chứa lại địa chỉ, nên retry thiếu kiểm soát có thể gán nhầm dữ liệu cũ. Phiên lỗi sẽ giữ raw, gửi B0 nếu có thể, rồi trả trạng thái lỗi; người chạy có thể mở một phiên mới.

## 3. Kết quả và log

Mỗi lần chạy tạo một thư mục riêng, không ghi đè phiên cũ:

```text
captures/a1700-<timestamp>-<id>/
    wire.log                TX/RX nguyên bản, có ngày, timezone, cổng, control bytes
    result.json             Tổng hợp settings, metadata, trạng thái và dữ liệu các nhóm
    current.json            Kết quả nhóm nếu đã được thử
    instantaneous.json
    historical.json
    load-profile.json
```

Thư mục phiên có quyền 0700; log và JSON có quyền 0600. `wire.log` giữ P0/P2 của phiên vì đây là log nguyên bản; JSON chỉ có metadata/measurement, không chứa credential hoặc challenge/response đăng nhập. Wire log được flush theo từng frame, JSON được lưu sau mỗi nhóm và khi lỗi/Ctrl+C. Đóng terminal bằng cách kill cưỡng bức không đảm bảo có JSON cuối cùng, nhưng các frame đã flush vẫn nằm trong log.

Ví dụ số liệu được tái tạo từ fixture PMU, **không phải một lần đọc thật mới**:

```json
{"id":"total_1","label":"Import Wh","value":27186.688,"unit":"Wh","quality":"available"}
{"id":"cumulative_md_8","label":"Export W","value":64800.000,"unit":"W","quality":"available"}
```

`transferStatus=complete` nghĩa là đã nhận đủ request/response của workflow. Nó không có nghĩa mọi field đã có decoder tổng quát. Kiểm tra thêm `data.decodeStatus`, `quality`, `confidence`, `warning`, `sessionError` và raw bytes.

`confidence` mô tả mức xác nhận của **quy tắc parse**, không khẳng định số liệu đọc mới phải bằng số liệu cũ trong fixture.

Mã thoát:

- `0`: các giao dịch hoàn tất; các giới hạn decoder đã được ghi trong JSON.
- `2`: có lỗi/thiếu dữ liệu trong phiên, hoặc payload không hợp lệ để giải mã.
- `1`: lỗi tham số, credential, môi trường hoặc file output.
- Ctrl+C thường trả `130`; vẫn cố lưu phần đọc được và gửi B0.

## 4. Phần đã giải mã và giới hạn

| Nhóm | Đã có | Giới hạn được biểu diễn trong output |
|---|---|---|
| Current | BCD 8 byte little-endian scale 3; CMD record 9 byte qua biên block; source Rates/CMD | MU/MD/Co-incident giữ raw với số mục dự kiến; chưa dựng giá trị từ layout chưa xác nhận |
| Instantaneous | 9 workflow; 29 mục được chọn; V/Hz/PF dùng ứng viên BCD scale 4, unavailable, góc -0.00 | Nonzero I/P/Q/S/angle và rotation enum chưa xác nhận; raw được giữ, value null. `50.2492` giữ nguyên; `50.24` chỉ là display candidate với truncate |
| Historical | Set 1, 50 mục thuộc families đã giải mã; counter 29, kỳ chốt, timestamp reset, reason raw | Các family chưa rõ như Current; `548` raw; chưa sinh địa chỉ set 2; timezone và từng reason bit chưa được khẳng định |
| Profile | 4 kênh (1,2,8,9), 30 phút theo cấu hình mẫu; 192 giá trị ngày đối chứng, flags, header; 33 record mẫu gần nhất | Tag `01`, nonzero Import/CD2 và cấu hình khác chưa hỗ trợ. Dừng gán thời gian tại E5/E6; giữ event candidates, offset và raw còn lại |

Giá trị CD giữ nguyên nhãn customer-defined và `unit=null` khi chưa có đơn vị đối chứng. `No Source`, `unavailable`, giá trị 0 và `unsupported` được phân biệt. Không tự nhân CT/PT hay cộng timezone.

Chi tiết byte/offset và bằng chứng nằm trong [a1700-response-parsing-logic.md](/home/shou/Documents/ATEnergy/j62056/doc/a1700-response-parsing-logic.md).

## 5. Kiểm thử đã chạy

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/test-a1700
cd /home/shou/Documents/ATEnergy/j62056
python3 src/test/python/test_a1700_pty.py
```

Python ở lệnh thứ hai chỉ là **test harness tạo PTY**, không tham gia driver hoặc đọc meter thật.

- So khớp từng request Java với fixtures R1/W1 từ PMU; đăng nhập dùng key/challenge tổng hợp, không copy credential thật vào tests.
- Kiểm tra Current 10/32/8 giá trị, CMD qua biên block, sources và invalid BCD.
- Kiểm tra 9 Instantaneous workflow, 3/4 polls, status lặp, selector sai, unavailable và nonzero scale chưa rõ.
- Historical set 1, epoch không tự đổi timezone, giữ `548`, không gửi thêm `544`.
- Profile ngắn đầy đủ; profile dài giữ 346 block và báo thiếu `55015B`; 192 số và 48 khoảng đầu khớp CSV, dừng event ở offset 16661.
- BCC sai, NAK, length sai, hex lỗi, frame bị cắt, sign-on retry/echo, wrong serial, giới hạn profile, lỗi B0 không che lỗi chính.
- Chạy **CLI Java thật** trên PTY: cả bốn nhóm, response chia nhỏ, timeout giữa frame, Ctrl+C, log/JSON và quyền file. PTY không hỗ trợ 7E1 điện nên shim kiểm tra flags yêu cầu rồi chuyển riêng PTY sang raw 8-bit để mô phỏng byte.

Lần kiểm thử hoàn thiện: **607 assertions** đạt; ba kịch bản PTY (`all`, `timeout`, `interrupt`) đạt. Thiết bị thật chưa có mặt để chạy kiểm chứng cuối cùng.

Kết quả fixture/simulation để xem cấu trúc nằm ở `build/a1700-test-results/`. Đây là dữ liệu kiểm thử, không phải capture thật mới.

## 6. API Java

```java
A1700Client.Config config = new A1700Client.Config();
config.address = "005";
config.expectedSerial = "21000568";
Iec21Port port = new Iec21Port.Builder("/dev/ttyUSB0")
        .setInitialBaudrate(9600).setTimeout(config.timeoutMs)
        .enableA1700Direct(true).buildAndOpen();
try {
    A1700Client.Capture capture = port.a1700Client(config, observer).capture("current", keyBytes);
    Map<String, Object> result = A1700Decoder.decode(capture);
    // Check capture.complete / capture.error and each field's quality/confidence.
} finally {
    port.close();
}
```

`keyBytes` là 8 byte credential được caller cung cấp; `observer` nhận TX/RX và progress. Caller sở hữu và đóng port. API cũ `readA1700Current(...)` vẫn trả 18 raw blocks, nay dùng chung session engine. `run-scripts/a1700-current` vẫn là launcher raw cũ; dùng `run-scripts/a1700` để nhận kết quả đã giải mã của phiên bản mới.
