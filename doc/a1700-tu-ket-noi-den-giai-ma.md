# A1700: từ kết nối dây đến đọc và hiểu dữ liệu

Tài liệu này giải thích workflow của **driver Java trong dự án này**, dựa trên các log, ảnh và CSV PMU của meter **21000568**. Mục tiêu là đọc được log và biết đoạn Java nào thực hiện từng bước.

Các ví dụ có số đo lấy từ bộ đối chứng PMU, **không phải kết quả của một phiên đọc thật mới**. `[BCC]`, `[CHALLENGE]`, `[RESPONSE]` và `...` là chỗ rút gọn để giải thích; không gửi nguyên văn chúng tới meter.

## 1. Trước hết, phân biệt bốn lớp

```text
Dây và tín hiệu     RS485: truyền tín hiệu điện giữa MOXA và module meter
Ký tự serial       9600 baud, 7E1: truyền từng ký tự như /, ?, R, 1
Giao thức          IEC 62056-21: nhận dạng, chọn phiên, xác thực, đóng khung
Dữ liệu A1700      Địa chỉ 507001, 798001... và cách giải mã payload
```

IEC 62056-21 quy định trao đổi dữ liệu trực tiếp với meter, gồm phần cứng và giao thức. Nhưng biết chuẩn này chưa đủ để biết mọi địa chỉ và định dạng dữ liệu riêng của A1700. [Phạm vi tiêu chuẩn IEC](https://webstore.iec.ch/en/publication/6398).

Trong các log đang dùng, `798001` và `507001` là **địa chỉ register A1700** được đặt trong request `R1`. Driver không coi chúng là mã OBIS và không thực hiện phiên DLMS/COSEM.

## 2. Cần những gì để kết nối?

| Thành phần | Vai trò trong setup này |
|---|---|
| Meter A1700 và module truyền thông phù hợp | Đầu nhận request và trả dữ liệu |
| MOXA UPort 1150 | Chuyển USB thành đường serial RS485 |
| Cáp nối đúng pinout của module | Nối cặp phát của bên này tới cặp thu của bên kia |
| Driver MOXA trên Linux | Tạo cổng như `/dev/ttyUSB0`, hỗ trợ chọn chế độ điện |
| Quyền truy cập cổng | Java phải mở được thiết bị; đóng PMU/chương trình khác đang dùng cổng |
| JDK và thư viện trong project | Launcher biên dịch, chạy driver Java |
| Địa chỉ, serial mong đợi và credential | Chọn meter, kiểm tra đúng thiết bị và xác thực |

![Đường kết nối và hai cặp truyền nhận](/home/shou/Documents/ATEnergy/j62056/doc/images/a1700-basics/connection.png)

### Chú ý: terminal MOXA và breakout DB9 đánh số khác nhau

| Tín hiệu RS485 4 dây | Chân DB9 UPort | Cọc terminal MOXA chính hãng |
|---|---:|---:|
| TX+ | 2 | 1 |
| TX− | 1 | 2 |
| RX+ | 3 | 3 |
| RX− | 4 | 4 |
| Signal GND | 5 | 5 |

Bảng áp dụng cho adapter được mô tả trong manual UPort 1100; **hai vị trí TX bị đổi số**. Breakout DB9 thông thường phải đối chiếu số chân DB9 của nó. Với driver MOXA được manual mô tả, `port 3` chọn RS485 4-wire; đây không phải quy ước chung cho mọi USB serial. [Manual MOXA, phần Linux và “Pin Assignments”, mục 3-1](https://www.moxa.com/getmedia/a2924269-6076-4c8f-9c1e-7268e235dde1/moxa-uport-1100-series-manual-v9.0.pdf).

```bash
# Chỉ áp dụng khi đang dùng driver MOXA hỗ trợ quy ước này
sudo setserial /dev/ttyUSB0 port 3
setserial -G /dev/ttyUSB0
```

**Tài liệu này không khẳng định pinout RJ45 của module UK504-034.** Cần đối chiếu đúng sơ đồ module trước khi chỉ định màu dây. T568B cho biết màu tương ứng chân RJ45, không tự cho biết chân nào là TX/RX của meter. Không nối lại theo màu chỉ từ bảng MOXA ở trên.

RS485 truyền bằng chênh lệch điện áp giữa hai dây trong một cặp. Mức logic idle của UART không đồng nghĩa meter đang nhận dữ liệu liên tục. Đèn sáng chỉ là dấu hiệu quan sát; muốn xác nhận trao đổi thành công phải có response hợp lệ trong log. Ở đây chưa dùng màu LED làm tiêu chí xác nhận login.

## 3. Serial 9600, 7E1 nghĩa là gì?

`9600` là tốc độ bit của cấu hình này. `7E1` nghĩa là **7 bit dữ liệu, parity chẵn, 1 stop bit**. Một ký tự còn có 1 start bit. UART gửi bit dữ liệu thấp nhất trước; start là logic 0, stop là logic 1. [Mô tả khung USART của Microchip](https://onlinedocs.microchip.com/oxy/GUID-173AD72D-41FE-4760-A93C-7078A02BD908-en-US-7.1.1/GUID-585072A2-2328-4EDD-B24F-E2E7672632B5.html).

![Ví dụ ký tự slash trong khung UART 7E1](/home/shou/Documents/ATEnergy/j62056/doc/images/a1700-basics/uart-7e1.png)

Ví dụ ký tự `/` có mã ASCII `0x2F`:

```text
D6 ... D0     0 1 0 1 1 1 1
Thứ tự gửi    D0 D1 D2 D3 D4 D5 D6
Giá trị       1  1  1  1  0  1  0
Parity        1  (5 bit 1 trong data + 1 parity = 6, là số chẵn)
Toàn ký tự    START + 7 DATA + PARITY + STOP = 10 bit
```

Một bit khoảng `1/9600 = 104,17 µs`; một ký tự khoảng `1,04 ms`. Khoảng chờ meter xử lý nằm ngoài thời gian này.

Ảnh mẫu bạn gửi vẽ **8 data bits D0–D7**. Driver này dùng **D0–D6**. Java đưa byte cho cổng serial; phần cứng/driver xử lý start, parity và stop, không tự ghép các bit đó vào chuỗi request.

Cấu hình live hiện tại là **9600 ngay từ sign-on**, 7E1, không flow control, tắt echo. Đây là cấu hình của setup và code hiện tại, không phải kết luận mọi meter IEC 62056-21 đều bắt đầu ở 9600. Xem [cấu hình cổng trong Iec21Port](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:549).

## 4. Cách đọc ký hiệu trong log

`TX` là máy tính gửi; `RX` là máy tính nhận. Các ký hiệu dưới đây đại diện cho **một byte điều khiển**, không phải chuỗi có dấu `<` và `>`:

| Hiển thị | Hex | Dùng ở đâu trong ví dụ |
|---|---|---|
| `<SOH>` | 01 | Bắt đầu command frame |
| `<STX>` | 02 | Bắt đầu phần dữ liệu |
| `<ETX>` | 03 | Kết thúc phần dữ liệu |
| `<ACK>` | 06 | Chấp nhận / chọn phiên tùy vị trí |
| `<NAK>` | 15 | Từ chối |
| `<CR>` | 0D | Kết thúc dòng sign-on, cùng LF |
| `<LF>` | 0A | Kết thúc dòng; cũng có thể tình cờ là giá trị BCC |

Phân biệt ba cách biểu diễn:

```text
Ký tự ASCII '8'          một byte 0x38 trên đường serial
Chuỗi ASCII "88"         hai byte 0x38 0x38 trên đường serial
Sau giải mã hex "88"     một byte dữ liệu 0x88 trong payload
```

Vì payload được truyền dưới dạng ký tự hex, vẫn có thể mang dữ liệu `0x88` dù cổng dùng 7 data bits. Hàm [render / hex / unhex](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Protocol.java:59) xử lý các biểu diễn này.

## 5. Một phiên đọc bắt đầu như thế nào?

Đừng đồng nhất **có identification** với **đăng nhập thành công**. Code thực hiện chuỗi sau theo log PMU:

```text
Máy tính                                      Meter
TX /?005!<CR><LF>                 ───────────► Hỏi địa chỉ 005
                                 ◄─────────── RX /GEC5090100301113@000<CR><LF>
TX <ACK>051<CR><LF>               ───────────► Chọn phiên programming
                                 ◄─────────── RX <SOH>P0<STX>([CHALLENGE])<ETX>[BCC]
TX <SOH>P2<STX>(0000000000000000)<ETX>b ──────► Bước P2 đầu như PMU
                                 ◄─────────── RX <ACK>
TX R1 798001(10)                 ───────────► Đọc serial
                                 ◄─────────── RX dữ liệu serial
TX R1 795001(08)                 ───────────► Đọc model
                                 ◄─────────── RX dữ liệu model
             Kiểm tra serial đúng thiết bị được cấu hình
TX <SOH>P2<STX>([RESPONSE])<ETX>[BCC] ────────► P2 tính từ key + challenge phiên này
                                 ◄─────────── RX <ACK>
             Bây giờ mới bắt đầu workflow Current / Historical / ...
TX B0                            ───────────► Kết thúc phiên khi xong
```

Các dòng `R1 ...` và `B0` trong sơ đồ là cách viết ngắn, không phải frame đầy đủ. `P0` là command mang challenge; `P2` mang response xác thực. Challenge/response không phải hai thông tin cố định để chép từ log cũ sang mọi phiên.

`ACK051` thực tế là `06 30 35 31 0D 0A`: `ACK`, ký tự `0`, mã tốc độ `5`, mode `1`, CR, LF. Mã tốc độ `5` tương ứng 9600 baud. [Bảng mã tốc độ IEC trong tài liệu Gurux](https://www.gurux.fi/DLMSIntro). Chuỗi programming `051` là chuỗi được PMU và code dự án này sử dụng.

`005` là địa chỉ sign-on; `21000568` là serial; `798001` là địa chỉ register. Chúng có vai trò khác nhau. Có log dùng `/?!` không kèm địa chỉ; CLI hỗ trợ `--address ''` cho trường hợp đó.

Code: [login()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:120), [tính P2 động](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Protocol.java:50), [capture và kết thúc phiên](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:88).

## 6. Ví dụ nhỏ nhất: đọc serial number

![Log nhận dạng và đọc serial đã chú thích](/home/shou/Documents/ATEnergy/j62056/doc/images/a1700-basics/log-identity.png)

Hình là bản trình bày lại một đoạn log PMU, bỏ thời gian và lược xác thực; không phải ảnh terminal của lần chạy mới. Frame serial đầy đủ:

```text
TX <SOH>R1<STX>798001(10)<ETX>e
RX <STX>(2D2D2D2D2D2D2D2D3231303030353638)<ETX><LF>
```

Đọc request theo từng phần:

```text
R1       yêu cầu đọc
798001   địa chỉ serial trong workflow A1700
(10)     độ dài hex 0x10 = 16 byte dữ liệu
ETX      hết payload
 e       BCC = 0x65; không phải một phần địa chỉ
```

Response có 32 ký tự hex, giải ra 16 byte. `2D` là dấu `-`; `32 31 30 30 30 35 36 38` là ASCII `21000568`. Kết quả là `--------21000568`; code bỏ dấu đệm để lấy serial.

**LF cuối response này là BCC có giá trị 0x0A.** Không được dùng quy tắc “gặp LF là hết mọi loại frame”. Bộ nhận đọc tới ETX rồi lấy thêm đúng byte BCC. Sign-on là trường hợp nhận dòng riêng. Xem [receive()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:237).

## 7. Request được Java tạo ra như thế nào?

Ví dụ đọc block đầu của Totals:

```java
byte[] request = A1700Protocol.frame("R1", "507001(40)");
// Kết quả: <SOH>R1<STX>507001(40)<ETX>d
// 0x40 = 64 byte dữ liệu; 'd' là BCC 0x64.
```

BCC được tính XOR từ byte sau SOH tới ETX, gồm ETX. Với response bắt đầu bằng STX, XOR từ byte sau STX tới ETX. **Parity kiểm tra từng ký tự; BCC kiểm tra frame.** Hai phép kiểm tra không thay thế nhau.

Sau khi nhận, code kiểm tra khung, BCC, dấu ngoặc, hex và độ dài dữ liệu. Response R1 không nhắc lại địa chỉ, nên driver gửi từng request rồi đợi response tương ứng, không gửi hàng loạt chồng nhau.

Code: [tạo frame và kiểm tra BCC](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Protocol.java:11), [payload()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Protocol.java:34), [read()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:224).

## 8. Current value: chỉ số tích lũy hiện tại

“Current” trong báo cáo này là bộ chỉ số hiện tại, gồm điện năng tích lũy và các nhóm demand. Nó không đồng nghĩa dòng điện tức thời; dòng điện nằm ở workflow Instantaneous.

```text
Login → đọc sources → đọc các block → nối từng family → giải mã → B0
```

Danh sách request được lưu trong Java, không đọc lại workbook/log khi chạy:

| Nhóm | Register và độ dài hex | Ý nghĩa đã đối chiếu |
|---|---|---|
| Sources | 667001(20), 668001(08) | Nguồn Rates và Demand |
| Totals | 507001(40), 507002(10) | 80 byte = 10 giá trị × 8 byte |
| Rates | 508001 đến 508004, mỗi block (40) | 256 byte = 32 giá trị × 8 byte |
| Multi Utility | 516001(20) | Giữ raw; chưa xác nhận layout tổng quát |
| Maximum Demand | 510001 đến 510004(40), 510005(20) | Giữ raw; chưa xác nhận layout tổng quát |
| Cumulative MD | 509001(40), 509002(08) | 72 byte = 8 record × 9 byte |
| Co-incident Demand | 511001(40), 511002(38) | Giữ raw; chưa xác nhận layout tổng quát |

### Một giá trị từ byte tới con số

```text
TX  R1 507001(40)                     [viết ngắn]
RX  (8866182700000000...)             [chỉ trích đầu payload]

Lấy 8 byte đầu:      88 66 18 27 00 00 00 00
Đảo thứ tự byte:     00 00 00 00 27 18 66 88
Đọc chữ số BCD:      0000000027186688
Chia 1000:          27186.688 Wh
Tên đối chiếu PMU:  Import Wh
```

BCD nghĩa là mỗi nửa byte chứa một chữ số thập phân. Không lấy cả chuỗi này làm số nguyên hexadecimal. Nhãn Import Wh là mapping đã đối chiếu theo vị trí trong family, không suy ra từ riêng byte `88`.

![Ảnh Current gốc từ PMU](/home/shou/Documents/ATEnergy/j62056/doc/images/a1700-basics/pmu-current.png)

Rates dùng bảng source để gán nhãn: mẫu này `01` là Import Wh, `02` là Export Wh, `00` là No Source. Cumulative MD có **8 byte giá trị + 1 byte source**; record cuối đi qua biên hai block nên phải nối block trước rồi mới chia record.

Code: [18 request Current](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:27), [current()](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:170), [BCD](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Decoder.java:36), [ghép family và giải mã Current](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Decoder.java:117).

## 9. Instantaneous: điện áp, dòng, công suất, tần số

Phải **chọn nhóm → chờ sẵn sàng → đọc kết quả**. Ví dụ điện áp, log dưới đây lược SOH/STX/ETX/BCC:

```text
TX W1 605001(1B2B4B00)     Chọn điện áp
RX ACK
TX R1 605001(04)           Hỏi trạng thái
RX (9B2B4B00)              Chưa đủ bốn byte ready
TX R1 605001(04)
RX (9BAB4B00)              Vẫn chưa đủ
TX R1 605001(04)
RX (9BABCB00)              Vẫn chưa đủ
TX R1 605001(04)
RX (9BABCB80)              Đủ bốn byte ready
TX R1 606001(1C)           Đọc 28 byte = 4 slot × 7 byte
RX (...)                  Kết quả của nhóm đã chọn
```

Code kiểm tra bit cao `0x80` của cả bốn byte, đồng thời kiểm tra các bit còn lại khớp selector. Số lần poll không được cố định theo một log: meter có thể cần thêm thời gian.

Slot điện áp `00 00 00 02 22 46 33` cho ứng viên **222.4633 V**, đối chiếu PMU hiển thị **222.46 V**. Quy tắc ứng viên này không được áp bừa cho mọi nhóm. Các giá trị khác 0 của I/P/Q/S và một số trường vẫn chưa đủ bằng chứng giải mã tổng quát.

Code: [selector map](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:32), [workflow và poll](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:184), [giải mã slot và giới hạn](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Decoder.java:165).

## 10. Historical: ảnh chụp chỉ số tại kỳ chốt

Historical là các chỉ số được lưu cho kỳ chốt, khác với chuỗi từng khoảng thời gian của Load Profile.

```text
Login
R1 667001(20), R1 668001(08)     Sources
R1 543001(40) ... 54300D(40)    13 block × 64 byte
R1 54300E(1F)                  31 byte cuối
R1 548001(40)                  Block phụ, giữ raw
Nối family 543 → 863 byte → chia vùng → B0
```

| Offset trong 863 byte, tính từ 0 | Nội dung |
|---:|---|
| 0 | Totals, 80 byte |
| 80 | Rates, 256 byte |
| 336 | Multi Utility, 32 byte |
| 368 | Cumulative MD, 72 byte |
| 440 | Maximum Demand, 288 byte |
| 728 | Co-incident Demand, 120 byte |
| 848 | Metadata kỳ chốt, 15 byte |

Ví dụ metadata trong mẫu:

```text
1D00 | 004EC267 | 802CEB67 | 81 | 3654A96A
 29  | đầu kỳ   | cuối kỳ | mã | thời điểm reset
```

Kết quả đối chiếu: counter **29**, kỳ **01/03/2025–01/04/2025**, reset **15/09/2026 14:20:38**. Timestamp dùng cách tính giây từ epoch trên số little-endian để khớp mẫu; chưa tự kết luận timezone hay cộng 7 giờ. Reason `81` được giữ raw; chưa khẳng định ý nghĩa từng bit.

Driver hiện đọc **set 1 theo log**, chưa tự suy ra địa chỉ set 2. Nhãn Rates đang dựa sources của mẫu; nếu cấu hình source từng thay đổi, cần giải mã thêm cấu hình historical ở `548`.

Code: [đọc Historical](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:175), [chia vùng và metadata](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Decoder.java:128).

## 11. Load Profile: chuỗi giá trị theo khoảng thời gian

Với mẫu đang đối chiếu, mỗi hàng biểu diễn một khoảng **30 phút**, có bốn kênh hoạt động. Chu kỳ và danh sách kênh lấy từ cấu hình/CSV đối chứng, chưa phải bộ giải mã cấu hình mọi A1700.

```text
TX W1 551001(0100)      Chọn bộ profile như log ngắn
RX ACK
TX R1 551001(02)        Hỏi số block
RX (0007)              7 block
TX R1 550001(40)
RX (...64 byte...)
...
TX R1 550007(40)
RX (...64 byte...)
                       Nối payload theo thứ tự, rồi mới parse record
```

Mẫu ngắn có `7 × 64 = 448 byte`: header 8 byte, 33 record × 13 byte, 11 byte đệm FF. Record có thể cắt ngang block 64 byte; một response không tương ứng một hàng PMU.

![Ảnh Load Profile gốc: các khoảng 30 phút ngày 16/09/2026](/home/shou/Documents/ATEnergy/j62056/doc/images/a1700-basics/pmu-profile.png)

Ảnh trên thuộc mẫu ngắn có Import bằng 0. Ví dụ có số khác 0 bên dưới lấy từ **CSV/log dài ngày 19/06/2023**, không phải chính hàng trong ảnh:

```text
40 | 00 00 00 | 24 96 02 | 20 16 02 | 00 00 00
flag  Import     Export      CD1         CD2
                  2.496     2.016
```

Trong kiểu record đã đối chiếu: một byte flags + bốn trường 3 byte. Với tag `02`, hai byte đầu là BCD theo thứ tự lớn trước, chia 1000: `24 96` → `2496 / 1000 = 2.496`. Export có đơn vị kW trong CSV; CD1 giữ đơn vị chưa xác nhận, không tự gán kW.

Selector `FFFF` trong log dài trả count `0545`, nhưng bản log chỉ có 346 response block hoàn chỉnh và request kế tiếp bị cắt. Nó không phải bằng chứng rằng đã đọc đủ cả profile. Hai selector `0100`/`FFFF` cũng chưa đủ để xây API truy vấn ngày bất kỳ.

Khi gặp marker sự kiện E5/E6, decoder giữ raw và dừng gán tiếp thời gian cho interval; chưa đủ bằng chứng để tiếp tục cộng 30 phút xuyên qua sự kiện. Tag `01`, nonzero Import/CD2 và cấu hình kênh khác vẫn có giới hạn.

Code: [đọc count và các block](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Client.java:204), [giải mã stream và sự kiện](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/a1700/A1700Decoder.java:223).

## 12. Chạy driver và xem kết quả

Launcher hiện chạy trên Linux, cần JDK 9+ để biên dịch target Java 8 và JAR local của project. Credential mặc định là PMU password level 3 `FEDC0003`, được dùng dưới dạng 8 byte ASCII. Có thể ghi đè bằng `--key FILE` với file đúng 8 byte; không thay password bằng chuỗi P2 copy từ log.

Bắt đầu bằng Current:

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 \
  --port /dev/ttyUSB0 --address 005 --serial 21000568 --group current
```

Sau đó chọn nhóm cần đọc:

```bash
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group instantaneous
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group historical
/home/shou/Documents/ATEnergy/j62056/run-scripts/a1700 --group load-profile --profile-selector 0100
```

Mặc định: timeout một response 8 giây, chờ giữa giao dịch 300 ms, tối đa 3 lần sign-on khi hoàn toàn không có byte trả về, poll Instantaneous tối đa 15 giây. Driver không tự đổi chế độ điện MOXA.

```text
captures/a1700-<timestamp>-<id>/
  wire.log         Byte TX/RX được biểu diễn bằng ký hiệu điều khiển
  result.json      Trạng thái phiên và kết quả tổng hợp
  current.json     Dữ liệu nhóm được đọc, tương tự với các nhóm khác
```

Ví dụ kết quả giải mã từ bộ đối chứng:

```json
{"id":"total_1","label":"Import Wh","value":27186.688,"unit":"Wh","quality":"available"}
```

`transferStatus=complete` chỉ có nghĩa giao dịch hoàn tất. Cần đọc thêm `decodeStatus`, `quality`, `confidence`, `warning` và `rawHex`. `unsupported`/`value=null` là chưa giải mã được, không phải số 0. Wire log nguyên bản có P0/P2 của phiên; nên giữ bản gốc riêng và che các trường đó khi chia sẻ.

Xem [hướng dẫn chạy đầy đủ](/home/shou/Documents/ATEnergy/j62056/doc/a1700-java-driver.md) và [CLI Java](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/app/A1700Reader.java).

## 13. Khi không có kết quả, kiểm tra đang dừng ở đâu

| Dấu hiệu trong log | Điều đã biết và bước kiểm tra |
|---|---|
| Không mở được `/dev/ttyUSB0` | Kiểm tra thiết bị, driver, quyền truy cập, cổng có bị chương trình khác chiếm |
| Có TX nhưng không có RX | Chưa chứng minh meter nhận được; kiểm tra mode điện, pinout, serial settings và địa chỉ |
| RX giống nguyên TX `/?!` | Có thể là echo; chưa phải identification của meter |
| RX `/GEC...` | Meter đã trả nhận dạng; vẫn cần ACK051 và xác thực |
| Có P0, sau P2 bị NAK | Xác thực chưa được chấp nhận; kiểm tra credential, challenge phiên hiện tại và đúng meter |
| BCC/độ dài sai | Không giải mã tiếp như dữ liệu hợp lệ; giữ raw để kiểm tra frame bị cắt hoặc đường truyền |
| Nhận đủ block nhưng field unsupported | Giao tiếp có thể đã tốt; cần bổ sung bằng chứng cho quy tắc parse |
| Chỉ có LED sáng liên tục | Chưa xác định được bước giao thức; dùng TX/RX để kết luận |

## 14. Mức độ kiểm chứng và tài liệu đối chiếu

Driver đã được kiểm thử bằng fixtures PMU: **607 assertions** và ba kịch bản CLI trên PTY (đủ dữ liệu, timeout, ngắt giữa chừng). PTY kiểm tra luồng byte, không kiểm chứng tín hiệu RS485 hay parity vật lý. Chưa có xác nhận live mới cho bản driver này trong đợt hoàn thiện được ghi nhận ở tài liệu chạy.

Các địa chỉ và mapping trong tài liệu là những gì code đang thực hiện và đã đối chiếu được từ bộ mẫu; không phải register manual đầy đủ do nhà sản xuất công bố. Tài liệu chi tiết giữ các offset, bằng chứng và giới hạn:

- [Logic parse chi tiết](/home/shou/Documents/ATEnergy/j62056/doc/a1700-response-parsing-logic.md).
- [Danh mục bằng chứng PMU](/home/shou/Documents/ATEnergy/j62056/doc/a1700-pmu-evidence-20260920.json).
- [Log Current gốc](</media/shou/New Volume/ATEnergy/Meter/docs/Log PMU/current value.txt>).
- [Log Instantaneous gốc](</media/shou/New Volume/ATEnergy/Meter/docs/Log PMU/instant paramter.txt>).
- [Log Historical gốc](</media/shou/New Volume/ATEnergy/Meter/docs/Log PMU/historical.txt>).
- [Log Profile ngắn](</media/shou/New Volume/ATEnergy/Meter/docs/Log PMU/loadprofile.txt>).
- [Log Profile dài](</media/shou/New Volume/ATEnergy/Meter/docs/Log PMU/loadprofile-from19-06-2023.txt>).
- [Parsing Guide đối chiếu](/home/shou/Documents/ATEnergy/Meter/A1700_Response_Parsing_Guide.md).

Ảnh PMU được sao chép nguyên từ bộ nguồn. Hình kết nối, 7E1 và hình log chú thích được dựng để giải thích, không phải ảnh đo oscilloscope hay capture meter mới.
