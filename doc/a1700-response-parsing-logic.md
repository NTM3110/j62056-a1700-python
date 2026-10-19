# A1700 — Logic request và parse từng loại dữ liệu

Ngày verify: **20/09/2026**. Meter mẫu: **21000568**, identification `/GEC5090100301113@000`, PMU ghi firmware **Vision (030.11)**.

Tài liệu này là đặc tả thực nghiệm để phát triển driver Java, được đối chiếu với toàn bộ 12 file trong `/media/shou/New Volume/ATEnergy/Meter/docs/Log PMU` và [A1700_Response_Parsing_Guide.md](/home/shou/Documents/ATEnergy/Meter/A1700_Response_Parsing_Guide.md). Các log chỉ phục vụ nghiên cứu/kiểm thử; driver tự tạo request, không cần đọc file PMU lúc chạy.

**Đã verify trên dữ liệu lưu trong file, chưa chạy lại meter trực tiếp trong lần nghiên cứu này.** “Khớp mẫu” không có nghĩa định dạng đã được xác nhận cho mọi firmware, cấu hình kênh hoặc CT/PT.

## 1. Phạm vi và mức xác nhận

| Nhóm dữ liệu | Đầu ra cần có | Mức xác nhận hiện tại |
|---|---|---|
| Identity | Serial, model, identification raw | ASCII khớp |
| Current — Cumulative totals | 10 tổng tích lũy | Layout và số liệu khớp; 9 mẫu khác 0 |
| Current — Rates | 32 giá trị và nguồn tương ứng | Layout và số liệu khớp; 6 mẫu khác 0 |
| Current — Multi-utility | MU 1–4 | Chỉ có mẫu 0; layout 4 × 8 byte là giả thuyết |
| Current — Maximum demands | 8 register × 3 level, giá trị/nguồn/thời gian | 24 mục báo cáo; payload toàn 0, chưa chốt layout |
| Current — Co-incident demands | 5 register × 3 level | 15 mục báo cáo; payload toàn 0, chưa chốt layout |
| Current — Cumulative maximum demands | 8 giá trị và nguồn | Layout và cả 8 số khác 0 khớp |
| Instantaneous | I, U, P, Q, S, PF, Hz, góc pha, thứ tự pha | Workflow 9 nhóm đã rõ; decoder số mới xác nhận một phần |
| Historical | Các nhóm Current theo kỳ chốt; số lần chốt, kỳ, thời gian và lý do chốt | Set 1 khớp; nhiều set và cấu hình `548` còn thiếu quy tắc |
| Load Profile | Kênh, khoảng thời gian, giá trị, flags, raw events, trạng thái tải | Ngày 19/06/2023 khớp 192 số; event/scale khác chưa đủ bằng chứng |
| Cấu hình hỗ trợ | Nguồn `667/668`, raw `548`, metadata profile | Chỉ ánh xạ những trường có đối chứng; giữ phần còn lại raw |

Quy ước:

- **Khớp mẫu:** có byte raw và số/nhãn PMU đối chứng.
- **Suy luận:** phù hợp mẫu nhưng chưa phân biệt được các cách giải thích khác.
- **Chưa giải mã:** trả raw và lý do, không thay bằng số 0 hay gán đơn vị tùy ý.
- Offset/độ dài trong bảng là **byte đã hex-decode**, đánh số từ 0. Chỉ số Rates/MD hiển thị bắt đầu từ 1. `data[a:b]` gồm a, không gồm b.

## 2. Session, framing và kiểm tra payload dùng chung

### 2.1 Trình tự phiên trong log

```text
/?005! CR LF                      # địa chỉ outstation của log ngày 18/09
<- identification /GEC5... CR LF
ACK 051 CR LF
<- SOH P0 STX (challenge) ETX BCC
SOH P2 STX (0000000000000000) ETX BCC
<- ACK
R1 798001(10)                     # nhận dạng serial
R1 795001(08)                     # nhận dạng model
P2(response tính từ challenge mới và credential phù hợp)
<- ACK
[workflow nhóm dữ liệu]
SOH B0 ETX BCC
```

`ACK 051 CR LF` có thể bị log tách thành hai lần TX; vẫn là một chuỗi byte. Log `loadprofile.txt` ngày 16/09 dùng `/?!` không có địa chỉ; các log ngày 18/09 dùng `/?005!`. Địa chỉ `005`, serial `21000568`, địa chỉ register `507001` là ba trường khác nhau.

Không phát lại cặp P0/P2 đã ghi trong log. Phần session phải tính response từ challenge hiện tại và credential của đúng meter. API parse bên dưới bắt đầu từ payload sau khi session và BCC đã được kiểm tra. Java hiện có luồng này tại [Iec21Port.java](/home/shou/Documents/ATEnergy/j62056/src/main/java/org/openmuc/j62056/Iec21Port.java:521), nhưng còn hardcode serial và sign-on.

Cấu hình nhánh Java hiện tại là 9600, 7E1, không flow control; log PMU không tự chứng minh baud/parity hay chế độ điện RS485 của adapter. Không suy ra cấu hình vật lý chỉ từ nội dung ASCII.

### 2.2 Frame và độ dài

```text
Request đọc:     SOH R1 STX ADDRESS(LENGTH_HEX) ETX BCC
Request chọn:    SOH W1 STX ADDRESS(SELECTOR_HEX) ETX BCC
Response dữ liệu: STX (HEX_PAYLOAD) ETX BCC
Response ACK:    06
Response NAK:    15
```

Đối với các frame đã quan sát, BCC là XOR từ byte ngay sau SOH/STX đầu frame đến hết ETX, không gồm byte BCC. Ví dụ R1 bao gồm ASCII `R1`, STX, địa chỉ, dấu ngoặc, length và ETX. Tính trên **byte thực sự gửi**, kể cả chữ hoa/thường của địa chỉ.

Quy trình parse response:

1. Nhận byte đầu và phân biệt ACK/NAK với frame dữ liệu.
2. Với frame, nhận đến ETX rồi **đọc thêm đúng một byte BCC**. BCC có thể chính là LF, SOH, STX hoặc ETX; không coi nó là đầu frame tiếp theo.
3. Kiểm tra BCC, command nếu có và cặp dấu ngoặc.
4. Hex-decode phần trong ngoặc. Chỉ chấp nhận số chẵn ký tự hex.
5. Với R1, số byte phải bằng `int(LENGTH_HEX, 16)`. `(40)` là 64 byte, không phải 40 byte.
6. Ghép các payload theo đúng thứ tự register trước khi cắt record. Ghi lỗi và partial raw khi thiếu frame/byte.

`W1 ... (0100)` là **hai byte selector**, không phải độ dài cần đọc. Tương tự các selector `605001` dài bốn byte.

## 3. Identity và metadata báo cáo

| Request | Byte trả về | Parse | Kết quả mẫu |
|---|---:|---|---|
| `R1 798001(10)` | 16 | ASCII sau hex-decode | `--------21000568` |
| `R1 795001(08)` | 8 | ASCII sau hex-decode | `A1700---` |

Giữ chuỗi raw; nếu cần ID chuẩn hóa, chỉ bỏ ký tự padding ở đầu/cuối theo quy tắc đã xác định. Không dùng decoder BCD cho hai trường này.

Firmware `Vision (030.11)` là nhãn trong PDF. Chưa có dedicated firmware-register request được xác nhận trong bộ log này; không tự tách các chữ số identification thành version chính thức. `Program completed normally (000000)` và thời gian xuất báo cáo là metadata của PMU, chưa được ánh xạ sang register meter.

## 4. Current Values

“Current Values” ở đây là các chỉ số hiện tại trong báo cáo PMU; dòng điện tức thời thuộc phần Instantaneous.

### 4.1 Danh sách request đầy đủ

Thực hiện sau đăng nhập, theo thứ tự trong `current value.txt`:

| Thứ tự | Request | Byte | Nhóm |
|---:|---|---:|---|
| 1 | `667001(20)` | 32 | Nguồn Rates |
| 2 | `668001(08)` | 8 | Nguồn liên quan Cumulative MD |
| 3–4 | `507001(40)`, `507002(10)` | 64 + 16 | Tổng tích lũy |
| 5–8 | `508001(40)`…`508004(40)` | 4 × 64 | Rates |
| 9 | `516001(20)` | 32 | MU |
| 10–14 | `510001(40)`…`510004(40)`, `510005(20)` | 4 × 64 + 32 | MD |
| 15–16 | `509001(40)`, `509002(08)` | 64 + 8 | Cumulative MD |
| 17–18 | `511001(40)`, `511002(38)` | 64 + 56 | Co-incident |

Tất cả dùng R1. Có **18 request dữ liệu/cấu hình**, ngoài hai identity read của session. Sáu family đo lường có tổng 848 byte; cộng 40 byte nguồn là 888 byte.

### 4.2 Decoder BCD 8 byte, little-endian, scale 3

Đã xác nhận cho Totals, Rates và phần value của Cumulative MD:

```text
decodeBcd8LE(bytes):
    require length == 8
    digits = ""
    for i = 7 down to 0:
        high = (bytes[i] unsigned) >> 4
        low  = (bytes[i] unsigned) & 0x0F
        require high <= 9 and low <= 9
        append high, low to digits
    return BigDecimal(BigInteger(digits), scale=3)
```

Ví dụ `88 66 18 27 00 00 00 00` → `0000000027186688` → **27186.688**. Đảo **byte**, không đảo hai nibble trong byte. Không đọc toàn trường như số nguyên nhị phân, IEEE float hoặc 5-byte BCD.

### 4.3 Cumulative totals — 10 mục

Ghép `507001 + 507002` thành 80 byte. Mục i từ 1 đến 10 dùng `[8(i−1):8i]` với decoder ở §4.2.

| i | Offset ghép | Giá trị khớp PMU | Nhãn/đơn vị PMU |
|---:|---:|---:|---|
| 1 | 0 | 27186.688 | Import Wh |
| 2 | 8 | 35767486.896 | Export Wh |
| 3 | 16 | 149.456 | Q1 varh |
| 4 | 24 | 27439821.848 | Q2 varh |
| 5 | 32 | 87.184 | Q3 varh |
| 6 | 40 | 21204.696 | Q4 varh |
| 7 | 48 | 45248629.144 | VAh |
| 8 | 56 | 25918347.304 | CD 1 |
| 9 | 64 | 1563278.792 | CD 2 |
| 10 | 72 | 0.000 | CD 3 |

`507002` chứa CD2 và CD3. Chỉ đổi Wh → kWh khi tầng ứng dụng yêu cầu, bằng một phép chia thêm 1000. CD là nhãn customer-defined; chưa biết công thức và đơn vị, không tự gán thành kWh.

### 4.4 Rates — 32 mục và source selection

Ghép `508001..508004` thành 256 byte. Rate i dùng `[8(i−1):8i]`, cùng decoder BCD8. Trong từng response, mỗi block chứa 8 rates.

Nguồn của Rate i là byte `[i−1]` trong `667001`: mẫu `01 01 01 02 02 02 00…`.

| Rate | Giá trị | Source | Nhãn PMU |
|---:|---:|---|---|
| 1 | 13886.800 | 01 | Import Wh |
| 2 | 5961.960 | 01 | Import Wh |
| 3 | 7337.928 | 01 | Import Wh |
| 4 | 20450261.160 | 02 | Export Wh |
| 5 | 6480644.304 | 02 | Export Wh |
| 6 | 8836406.904 | 02 | Export Wh |
| 7–32 | 0.000 từng mục | 00 | No Source |

Source ngoài 00/01/02 chưa có đối chứng: giữ numeric raw/decoded nếu BCD hợp lệ, nhưng source và unit phải là unknown. `No Source` là trạng thái cấu hình; không đồng nhất nó với phép đo tiêu thụ hợp lệ bằng 0.

### 4.5 Cumulative Maximum Demands — 8 mục

Ghép `509001 + 509002` thành **72 byte**. Record i dùng `[9(i−1):9i]`:

```text
value  = decodeBcd8LE(record[0:8])
source = unsigned(record[8])
01 -> Import W; 02 -> Export W trong mẫu này
```

| Register | Offset ghép | Value | Source/unit |
|---:|---:|---:|---|
| 1 | 0 | 890.848 | 01 / Import W |
| 2 | 9 | 704.096 | 01 / Import W |
| 3 | 18 | 608.096 | 01 / Import W |
| 4 | 27 | 640.096 | 01 / Import W |
| 5 | 36 | 95706.928 | 02 / Export W |
| 6 | 45 | 68256.000 | 02 / Export W |
| 7 | 54 | 65662.752 | 02 / Export W |
| 8 | 63 | 64800.000 | 02 / Export W |

Record 8 bắt đầu ở byte cuối của `509001` và kết thúc ở `509002`; **không parse riêng `509002` như một giá trị 8 byte**. Đơn vị của báo cáo là **W**, không phải Wh.

`668001` trả `01 01 01 01 02 02 02 02`, trùng 8 source bytes trong records. Có thể kiểm tra tính nhất quán của mẫu này; chưa biết toàn bộ ý nghĩa cấu hình. Không lấy `668001` gán nguồn cho MD thường, vì PMU đang ghi MD thường là No Source.

### 4.6 MU, Maximum Demands và Co-incident — giữ raw tới khi có bằng chứng

| Family | Cách ghép | Mục PMU | Layout ứng viên | Logic được phép kết luận |
|---|---|---|---|---|
| `516` | `516001`, 32 byte | 4 MU | 4 × 8 | Mẫu toàn 0 phù hợp, chưa chứng minh nonzero encoding/thứ tự/đơn vị |
| `510` | `510001..004` 64 byte + `510005` 32 byte = 288 | 8 MD × 3 level | 24 × 12 | Chưa biết value width, timestamp, source, row-major/level-major |
| `511` | `511001` 64 + `511002` 56 = 120 | 5 Co-incident × 3 level | 15 × 8 | Chưa chứng minh cấu trúc/nguồn/thứ tự |

Cả ba family đều toàn byte 0 trong mẫu. PMU hiển thị MU 0; MD 0/No Source/Not available; Co-incident 0/No Source. Không đủ căn cứ để khẳng định offset từng `(register, level)`. Đầu ra driver hiện nên gồm raw family, số mục dự kiến và `decodeStatus=unsupported`; giá trị hiển thị PMU được lưu như dữ liệu đối chứng, không giả lập thành kết quả đã giải mã.

**Kiểm kê Current đủ 93 mục:** 10 Totals + 32 Rates + 4 MU + 24 MD + 15 Co-incident + 8 Cumulative MD. Có 50 mục thuộc families đã có bằng chứng nonzero, 4 MU chỉ khớp 0, 39 MD/Co-incident chưa xác nhận layout.

## 5. Instantaneous Values

### 5.1 Workflow theo trạng thái

```text
for each requested group:
    W1 605001(selector)
    require ACK
    until deadline:
        status = R1 605001(04)
        require (status[i] & 0x7F) == selector[i] for all 4 slots
        if every status[i] has bit 0x80: break
        wait configured poll delay
    if not ready: return timeout with last status
    payload = R1 606001(1C)        # đúng 28 byte
    parse according to group
```

Không poll cố định 4 lần: reactive power chỉ có 3 lần, phase angle có status lặp trong log. Cả 9 nhóm đã kiểm tra: 7 bit thấp luôn giữ selector, cả 4 bit ready chỉ đồng thời bật ở lần poll cuối.

| Nhóm | Selector | Số poll mẫu | Slot sử dụng | Nhãn/đơn vị PMU |
|---|---|---:|---|---|
| Dòng điện | `1A2A4A00` | 4 | A/B/C | A |
| Điện áp | `1B2B4B00` | 4 | A/B/C | V |
| Active power | `1C2C4C0C` | 4 | A/B/C/Total | kW |
| Reactive power | `1D2D4D0D` | 3 | A/B/C/Total | kvar |
| Apparent power | `1E2E4E0E` | 4 | A/B/C/Total | kVA |
| Power factor | `13234303` | 4 | A/B/C/Total | Không có đơn vị |
| Frequency | `18284800` | 4 | A/B/C | Hz |
| Phase angle | `19294900` | 4 | A/B/C | degree |
| Phase rotation | `07000000` | 4 | Slot 0 là kết quả toàn meter | Enum, chưa xác nhận mã |

Đây là thứ tự slot ứng viên theo selector/báo cáo; B/C có nhiều giá trị bằng nhau hoặc không khả dụng nên cần mẫu khác nhau để kiểm chứng độc lập thứ tự pha. Với rotation, không gán slot 0 thành một giá trị riêng của pha A.

### 5.2 Cắt slot và giải mã ứng viên

28 byte chia 4 slot × 7 byte: `payload[7i:7i+7]`, i=0..3.

| Byte mẫu của slot | Cách diễn giải khớp mẫu | Kết quả PMU |
|---|---|---|
| `00 00 00 02 22 46 33` | Byte đầu trạng thái; 6 byte còn lại BCD big-endian /10000 → 222.4633 | 222.46 V |
| `00 00 00 00 50 24 92` | Cùng ứng viên → 50.2492 | 50.24 Hz |
| `00 00 00 00 01 00 00` | Cùng ứng viên → 1.0000 | PF 1.000 |
| `80 00 00 00 00 00 00` | Gợi ý bit dấu 0x80, độ lớn 0 | Angle -0.00 |
| `00 FF 00 00 00 00 00` | Mẫu unavailable, không phải BCD hợp lệ | Frequency B/C: Not available; còn gặp ở slot không chọn |
| `00 00 00 00 00 00 00` của rotation | Không được áp numeric decoder như phép đo thông thường | Non available |

Quy tắc thiết kế decoder:

1. Chọn ý nghĩa slot theo selector trước; slot không chọn không phải phép đo bằng 0.
2. Nhận dạng mẫu unavailable trước khi kiểm tra BCD. Không biến FF thành một giá trị số.
3. Với numeric slot được hỗ trợ, đọc nibble theo thứ tự nhận (không reverse như totals). Giữ raw status/sign.
4. Công thức `/10000` mới là ứng viên phù hợp V/Hz/PF; chưa chứng minh thang đo P/Q/S hoặc I khi khác 0.
5. `50.2492` làm tròn gần nhất tới 2 chữ số cho 50.25, khác 50.24 của PMU. Chưa chốt quy tắc truncate/round; không làm sai raw value chỉ để khớp màn hình.
6. Mẫu angle chỉ có -0.00, chưa đủ xác nhận mọi giá trị âm hoặc các status bits khác.
7. Rotation cần bảng enum riêng và mẫu hoạt động; hiện chỉ có unavailable.

PDF có **29 mục hiển thị**: 8 đại lượng × 3 pha + PF/P/Q/S tổng + rotation. Request trả 36 slot, gồm slot không được dùng; không xuất thêm 7 phép đo giả để đủ số slot.

## 6. Historical Values / Billing Snapshot

### 6.1 Request và assembly set 1

```text
R1 667001(20)
R1 668001(08)
R1 543001(40) ... R1 54300D(40)  # 13 × 64 byte
R1 54300E(1F)                    # 31 byte
R1 548001(40)                    # 64 byte phụ trợ, giữ riêng
```

Ghép 14 block `543` thành **863 byte**. Chỉ số block là hex; log dùng chữ a–e thường. Không có `544` trong capture này. `548001` không ghép thêm vào 863 byte như một block continuation.

### 6.2 Bố cục 863 byte

| Offset | Length | Nội dung | Decoder / mức xác nhận |
|---:|---:|---|---|
| 0 | 80 | 10 Totals | Như §4.3; khớp CSV |
| 80 | 256 | 32 Rates | Như §4.4; khớp CSV của cấu hình mẫu |
| 336 | 32 | MU ứng viên | Trùng vùng 0 của `516`, chưa xác nhận nội bộ |
| 368 | 72 | 8 Cumulative MD | Như §4.5; source nằm trong record |
| 440 | 288 | MD ứng viên | Trùng vùng 0 của `510`, chưa xác nhận nội bộ |
| 728 | 120 | Co-incident ứng viên | Trùng vùng 0 của `511`, chưa xác nhận nội bộ |
| 848 | 15 | Billing metadata | Bảng bên dưới |

Đối chiếu trực tiếp thấy sáu vùng đầu trùng byte với Current trong bộ mẫu. CSV ghi Historical data set 1 và có 93 mục tương ứng. Việc số hiện tại và số historical trùng nhau trong mẫu không chứng minh chúng luôn bằng nhau.

### 6.3 Billing metadata

Raw tail: `1D00 004EC267 802CEB67 81 3654A96A`.

| Offset trong stream | Byte | Logic | Kết quả đối chứng |
|---:|---:|---|---|
| 848 | 2 | uint16 little-endian | Reset number 29 |
| 850 | 4 | uint32 little-endian, chuyển epoch-like | Start 2025-03-01 00:00:00 |
| 854 | 4 | Cùng quy tắc | End 2025-04-01 00:00:00 |
| 858 | 1 | Giữ reason bitmask `0x81` | CSV đồng thời ghi Automatic billing date + Power-up process |
| 859 | 4 | Cùng quy tắc thời gian | Reset 2026-09-15 14:20:38; CSV hiển thị đến phút 14:20 |

Chưa đủ bằng chứng để tách riêng bit 0 và bit 7 của reason thành hai tên sự kiện. Giữ giá trị raw và danh sách nhãn đối chứng; không tuyên bố bảng bit đầy đủ.

Phép chuyển thời gian hiện là `1970-01-01 00:00:00 + unsigned_seconds`, so với **giờ PMU hiển thị**. Chưa xác nhận đây là UTC vật lý hay giờ local được mã hóa theo epoch. Trong Java không tự gọi chuyển timezone để cộng 7 giờ; giữ timezone unknown/cấu hình riêng. Dùng unsigned conversion trước khi chuyển timestamp.

### 6.4 `548001` và nhiều set

`548001` trả 64 byte và phải được bảo lưu như historical auxiliary/configuration raw. Chưa chốt cấu trúc, vai trò và cách nó lưu nguồn theo từng kỳ. Không lấy `667` hiện tại gán nhãn mọi kỳ nếu meter đã đổi cấu hình nguồn.

Bộ log chỉ chứa **set 1**. Chưa đủ bằng chứng để xác nhận stride địa chỉ/set 2, giới hạn số set hoặc thời gian nào xác định thứ tự set. Không biến công thức tăng địa chỉ của Python cũ thành quy tắc đã verify.

## 7. Load Profile

### 7.1 Chọn dữ liệu và tải block

```text
W1 551001(selector) -> ACK
R1 551001(02)       -> count_raw
count_candidate = unsigned big-endian uint16(count_raw)
for index = 1 .. count_candidate:
    R1 address("550" + index formatted as 3 hex digits)(40)
    require 64-byte payload
    persist validated block and progress
concatenate blocks in sequence
parse stream
```

| Nguồn | Selector | Count raw | Đã nhận trong file | Kết luận |
|---|---|---|---|---|
| `loadprofile.txt` | `0100` | `0007` | 7 × 64 = 448 byte, có B0 | Tải đủ theo count của mẫu |
| `loadprofile-from19-06-2023.txt` | `FFFF` | `0545` | 346 × 64 = 22144 byte | Count ứng viên 1349; capture thiếu |

Log dài dừng sau response `55015A`; request `55015B` ở dòng 712 bị cắt, không có response/B0. Không kết luận meter từ chối block kế tiếp. Ý nghĩa count được hỗ trợ bởi mẫu 7 block; count 1349 của mẫu dài chưa kiểm chứng bằng một lượt tải đủ.

Địa chỉ block đi `550009 → 55000A`, không phải số thập phân. Sinh từ index, không lấy danh sách từ log. Nếu count vượt giới hạn tài nguyên hoặc miền địa chỉ đã hỗ trợ, trả lỗi rõ; không cắt ở 256 rồi báo complete.

`0100` và `FFFF` là hai selector quan sát được; chưa biết cách tạo selector từ ngày tùy ý. Tên file “from19-06-2023” không phải chứng cứ request mã hóa ngày đó. Có thể lọc ngày sau decode với phần dữ liệu có timestamp xác nhận; không tuyên bố đã truy vấn meter theo ngày.

### 7.2 Header ngày

Mẫu đầu stream lịch sử:

```text
E4 | 80 9A 8F 64 | 03 03 99
```

| Offset trong header | Length | Diễn giải |
|---:|---:|---|
| 0 | 1 | Marker E4 ở ranh giới record |
| 1 | 4 | uint32 little-endian epoch-like → 2023-06-19 00:00:00 |
| 5 | 3 | Metadata `030399`, chưa giải mã |

Header tiếp theo ở offset 632, timestamp 20/06/2023 00:00. Một ngày đầy đủ của cấu hình này: `8 + 48 × 13 = 632 byte`.

Khoảng tích phân 30 phút và cấu hình kênh được lấy từ CSV đối chứng, **chưa giải mã từ `030399`**. Chỉ nhận marker ở ranh giới record; không quét mọi byte E4 bên trong payload rồi coi là header. Record/header có thể cắt qua biên block 64 byte.

### 7.3 Record thường và kênh

```text
40 | 00 00 00 | 24 96 02 | 20 16 02 | 00 00 00
```

| Offset record | Length | Kênh cấu hình PMU | Value đầu ngày |
|---:|---:|---|---:|
| 0 | 1 | Status | 0x40 |
| 1 | 3 | Channel 1: Import kW | 0 |
| 4 | 3 | Channel 2: Export kW | 2.496 |
| 7 | 3 | Channel 8: Customer Defined 1 | 2.016 |
| 10 | 3 | Channel 9: Customer Defined 2 | 0 |

Channels 3–7 được CSV ghi Off; chúng không chiếm năm trường 3 byte riêng trong mẫu này. Bốn trường đi theo thứ tự kênh bật. CD1/CD2 chưa có đơn vị vật lý được xác nhận; không gán kW theo hai kênh đầu.

### 7.4 Numeric field ba byte — chỉ hỗ trợ quy tắc có đối chứng

```text
if field == 00 00 00:
    value = 0
else if channel in {Export, CD1} and tag == 02 and first 2 bytes valid BCD:
    value = decimal_digits(first 2 bytes in received order) / 1000
else:
    value = null; decodeStatus = unsupported; preserve raw
```

| Field | Kết quả khớp |
|---|---:|
| `249602` | 2.496 |
| `259202` | 2.592 |
| `268802` | 2.688 |
| `278402` | 2.784 |
| `192002` | 1.920 |
| `201602` | 2.016 |
| `211202` | 2.112 |

Không reverse hai byte BCD. Không parse cả ba byte như số BCD thông thường. Byte cuối `02` chưa được chứng minh là exponent theo một công thức tổng quát; `192001` trong đoạn sau phải giữ unresolved.

Import và CD2 đều bằng 0 trong 48 dòng CSV. Scale nonzero của hai kênh này chưa được verify. Nếu cho phép giả định Import dùng chung scale Export, phải là cấu hình opt-in và output ghi rõ assumption; mặc định không âm thầm áp dụng.

CSV ghi Export là **kW**: `2.496` đã khớp kW, không nhân 2 lần nữa. Năng lượng suy ra của khoảng 30 phút đầy đủ là `2.496 × 0.5 = 1.248 kWh`. Tổng 48 khoảng trong ngày đối chứng là **60.480 kWh**, là số suy ra chứ không phải register energy được đọc riêng.

### 7.5 Thời gian và flags

Khi chưa gặp event/discontinuity:

```text
start[0] = header time
end[i]   = start[i] + configured interval
start[i+1] = end[i]
```

Mỗi header ngày mới phải nhất quán với thời điểm kế tiếp dự kiến. Không nhảy qua chỗ lệch giờ mà tiếp tục gán timestamp như bình thường.

- `0x40`: cả 48 dòng CSV tương ứng Phase C fail.
- `0x60`: ảnh ngày 16/09 ghi nhãn bị cắt “Phase B fail, Phase…”. Do `0x60 = 0x40 | 0x20`, `0x20` là **suy luận mạnh** cho Phase B fail; chưa đủ xác nhận các bit khác.
- Giữ raw byte và unknown bits. Số đo có thể decode được nhưng quality vẫn báo phase failure.
- CSV dòng 51 ghi start 23:30, end `12:00:00 PM` (trưa). Chuỗi nửa giờ và header kế tiếp hỗ trợ end **20/06/2023 00:00**. Ghi source anomaly, không sửa file gốc.

### 7.6 Event E5/E6, padding và incomplete

Tại offset **16661**, sau **1265** record thường, xuất hiện:

| Offset | Raw event ứng viên | Timestamp ứng viên |
|---:|---|---|
| 16661 | `E6625BB264` | 15/07/2023 08:40:02 |
| 16666 | `E5695BB264` | 15/07/2023 08:40:09 |
| 16671 | `E6695BB264` | 15/07/2023 08:40:09 |
| 16676 | `E5715BB264` | 15/07/2023 08:40:17 |

Byte marker + bốn byte timestamp phù hợp dữ liệu. Chưa xác định E5/E6 nghĩa là mất điện, có điện, đổi giờ hay sự kiện khác; chưa biết ảnh hưởng đến khoảng đo đang dang dở. Logic mặc định: trả các record đã hiểu, event raw, stop offset/reason và phần raw còn lại; **dừng gán thời gian cho đoạn sau**.

1265 record trước event gồm 26 ngày đầy đủ và 17 khoảng ngày 15/07, kết thúc 08:30. Chỉ 48 record ngày đầu có CSV đối chứng; không gọi toàn bộ 1265 record là đã verify giá trị.

Mẫu ngắn: header 16/09/2026 + **33 record** = 437 byte; kết thúc 16:30, còn **11 byte FF** đệm. Chỉ nhận FF padding khi đang ở biên record và phần còn lại toàn FF; không biến FF thành khoảng đo.

Tách hai kết quả:

- `transferStatus`: đã tải đủ block, còn thiếu block, hoặc lỗi giao tiếp.
- `decodeStatus`: giải mã hết phần tải được, còn event/tag/layout chưa hỗ trợ, hoặc record bị cắt.

Một transfer đầy đủ vẫn có thể decode một phần; một capture thiếu vẫn có thể chứa một ngày đầu đã verify được.

## 8. Bảng bao phủ toàn bộ request trong thư mục

Sau khi chuẩn hóa hex hoa/thường, có **396 dạng R1/W1 khác nhau**, gồm request `55015B(40)` chưa hoàn tất. Tất cả được phân loại bên dưới; các lệnh session P0/P2/ACK/B0 xử lý tại §2.

| Request / family | Dạng request khác nhau | Logic trong tài liệu |
|---|---:|---|
| `798001(10)`, `795001(08)` | 2 | Identity §3 |
| `667001(20)`, `668001(08)` | 2 | Source §4.4–4.5, lưu cùng snapshot |
| `507001(40)`, `507002(10)` | 2 | Totals §4.3 |
| `508001..004(40)` | 4 | Rates §4.4 |
| `516001(20)` | 1 | MU §4.6 |
| `510001..004(40)`, `510005(20)` | 5 | MD §4.6 |
| `509001(40)`, `509002(08)` | 2 | Cumulative MD §4.5 |
| `511001(40)`, `511002(38)` | 2 | Co-incident §4.6 |
| 9 selector `W1 605001(...)` + `R1 605001(04)` | 10 | Instantaneous chọn/poll §5.1 |
| `R1 606001(1C)` | 1 | Instantaneous payload §5.2 |
| `543001..00D(40)`, `54300E(1F)` | 14 | Historical snapshot §6.1–6.3 |
| `548001(40)` | 1 | Historical auxiliary raw §6.4 |
| `W1 551001(0100/FFFF)`, `R1 551001(02)` | 3 | Profile selector/count §7.1 |
| `550001..55015B(40)` | 347 | Profile block stream §7; block cuối chưa có response |

Không có capture event-log độc lập (`750…`), instrumentation profile (`555/556…`) hay đọc clock riêng trong thư mục này. E5/E6 là record bên trong Load Profile, không chứng minh workflow event-log độc lập. Những tính năng đó cần bằng chứng riêng trước khi mở rộng đặc tả.

## 9. Đầu ra đề xuất cho Java và các lỗi phải giữ lại

Mỗi kết quả nên giữ:

```text
meterId, group, itemId, phase/channel/level nếu đã xác nhận
value: BigDecimal hoặc null; unit: đã biết hoặc null
sourceCode, rawHex, register(s), offset, byteLength
meterTime / intervalStart / intervalEnd nếu đã xác nhận
collectedAt: thời gian máy đọc, có timezone rõ
quality: available / no_source / phase_failure / unavailable / unknown
decodeConfidence: matched_fixture / inferred / unsupported
warnings: scale chưa biết, timezone chưa biết, capture thiếu, ...
```

Trường cấu trúc như reset counter, source hoặc enum dùng kiểu phù hợp, không ép tất cả thành measurement decimal. `0`, `No Source`, `Not available` và `unsupported` phải phân biệt.

Các kiểm tra bắt buộc khi triển khai:

- Sai BCC, hex lỗi, length sai, ACK/NAK sai giai đoạn: không đưa vào numeric decoder như dữ liệu hợp lệ.
- Thiếu block hoặc response lặp: không nối lệch stream; trong log response không mang lại địa chỉ, phải ghép với request đang chờ.
- CMD record qua biên 64 byte; header/record profile qua biên 64 byte.
- Retry có giới hạn, ghi rõ stage/register và partial bytes; không phát lại W1 selector tùy tiện làm thay đổi lượt lấy dữ liệu đang chạy.
- B0/close thất bại không che lỗi chính; kết quả đã thu được vẫn được lưu với trạng thái partial.
- Không suy ra timezone, scale chung, đơn vị CD hoặc offset MD từ mẫu toàn 0.

**Cập nhật triển khai 20/09/2026:** Java đã có `A1700Client` và `A1700Decoder` cho cả bốn workflow, dùng lại `Iec21Port`; xem [hướng dẫn chạy](/home/shou/Documents/ATEnergy/j62056/doc/a1700-java-driver.md). Các trường chưa đủ bằng chứng trong đặc tả này vẫn trả raw/unsupported. API `readA1700Current` cũ giữ giao diện raw để tương thích. Bản mới đã chạy qua fixtures PMU và CLI trên PTY; chưa được xác nhận trên meter thật do `/dev/ttyUSB0` chưa xuất hiện.

## 10. Kết quả verify và nguồn

| Hạng mục | Kết quả kiểm tra lại |
|---|---|
| BCC của R1/W1 hoàn chỉnh | **455/455** đúng; 1 request profile cuối bị cắt không tính là frame hoàn chỉnh |
| BCC và length response dữ liệu R1 | **444/444** đúng; ACK-only và P0/P2 không nằm trong số này |
| Current PDF | Đủ **93 mục** đối chiếu theo thứ tự; tất cả 23 mục khác 0 khớp; 4 MU + 39 MD/Co-incident vẫn chỉ có bằng chứng số 0 |
| Totals/Rates/Cumulative MD | **10/10 + 32/32 + 8/8** số khớp; source Rates và CMD khớp nhãn mẫu |
| Instantaneous | Cả **9 workflow** có selector/status đúng và read 28 byte sau ready; V/Hz/PF và unavailable đã đối chiếu, scaling/rounding còn giới hạn ở §5 |
| Historical | **863 byte**, sáu vùng khớp Current; reset **29** và ngày/giờ kỳ chốt khớp CSV ở độ chính xác được hiển thị |
| Profile ngày 19/06/2023 | **192/192 số** khớp (96 nonzero), **48/48 start**, **47/48 end**, **48/48 flags**; ngoại lệ CSV được ghi rõ |
| Profile ngắn | **7 block**, **33 record**, **11 FF padding** |
| Profile dài | **346 block** liên tục; first event **offset 16661** sau **1265** record; capture còn thiếu so với count ứng viên 1349 |
| Bao phủ request | **396 dạng R1/W1**, không bỏ sót family; phạm vi decoder chưa biết được ghi explicit |

Nguồn gốc và hash được lưu trong [a1700-pmu-evidence-20260920.json](/home/shou/Documents/ATEnergy/j62056/doc/a1700-pmu-evidence-20260920.json), gồm cả request/response line của từng R1/W1; không chứa credential hay giá trị challenge/response đăng nhập.

| Nhóm | File nguồn đã đối chiếu |
|---|---|
| Current | `current value.txt`; `current-value -.png`; `current-value-2.png`; `current-value-1.pdf` trang 1–3 |
| Instantaneous | `instant paramter.txt`; `instant.pdf` trang 1–2 |
| Historical | `historical.txt`; `historical.CSV` |
| Load Profile | `loadprofile.txt`; `image load profile.png`; `loadprofile-from19-06-2023.txt`; `loadprofile-19-06-23.CSV` |
| Reference bổ sung | `/home/shou/Documents/ATEnergy/Meter/A1700_Response_Parsing_Guide.md` — phân tích thực nghiệm, không phải đặc tả của hãng |

Guide nhắc `current value(1).txt`, nhưng tên đó không có trong thư mục khảo sát; đã dùng `current value.txt` và PDF đầy đủ làm bằng chứng trực tiếp. Guide và lần verify này thống nhất BCD8, CMD9, profile ba byte có giới hạn scale, ranh giới sự kiện và lỗi timestamp CSV. Instantaneous/Historical được bổ sung từ nguồn tương ứng vì guide chưa mô tả đầy đủ hai nhóm này.

## 11. Những bằng chứng còn cần để hoàn thiện decoder

1. MU, MD, Co-incident có giá trị khác 0 và thời gian MD hợp lệ, cùng log/export PMU.
2. Instantaneous có dòng/công suất khác 0, các pha có giá trị khác nhau, góc âm khác 0, rotation hoạt động; đối chiếu precision của Hz.
3. Historical set 2 có số khác Current; thông tin cấu hình/source của kỳ, byte layout `548` và quy tắc chọn set.
4. Profile có Import/CD2 khác 0, tag `01` và tag khác; CSV quanh 15/07/2023 08:40 để hiểu E5/E6 và khoảng đo không đầy đủ.
5. Capture tải profile hoàn tất, cùng cấu hình kênh/interval/CT/PT; log khi chọn ngày khác nếu cần tạo selector theo thời gian.

Các điểm còn thiếu này không ngăn triển khai phần Totals/Rates/Cumulative MD đã khớp, workflow raw cho mọi nhóm, và decoder profile trong phạm vi cấu hình đã đối chứng. Chúng ngăn việc tuyên bố đã giải mã tổng quát toàn bộ dữ liệu A1700.
