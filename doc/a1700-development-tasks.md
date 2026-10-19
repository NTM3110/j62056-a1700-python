# A1700: kết quả đối chiếu PMU và các task cần hoàn thiện

Ngày nghiên cứu: 20/09/2026. Phạm vi: toàn bộ 12 file trong `/media/shou/New Volume/ATEnergy/Meter/docs/Log PMU`, mã Java hiện tại và [A1700_Response_Parsing_Guide.md](/home/shou/Documents/ATEnergy/Meter/A1700_Response_Parsing_Guide.md). Meter `21000568`, identification `/GEC5090100301113@000`; hai PDF ghi firmware Vision `(030.11)`.

**Cập nhật triển khai:** đã có phiên bản Java chạy cả bốn workflow và decoder trong phạm vi đã đối chiếu; xem [a1700-java-driver.md](/home/shou/Documents/ATEnergy/j62056/doc/a1700-java-driver.md). Phần phân tích mã dưới đây là snapshot trước khi triển khai, không phải danh sách lỗi còn nguyên ở bản mới. T01/T03/T05 và luồng session/output chính đã được thực hiện; T06/T07/T09 hỗ trợ phần có bằng chứng; T04/T12/T13/T14 vẫn còn giới hạn cần mẫu bổ sung. Test fixture/PTY đã chạy; test meter thật còn chờ thiết bị USB.

Các quy tắc dưới đây được kiểm tra trên bộ mẫu này. Những vùng chỉ có số 0, mã trạng thái chưa phân biệt được, và quy tắc ngày giờ chưa được kiểm chứng trên cấu hình khác được ghi riêng. Các file log dùng để nghiên cứu và làm mẫu kiểm thử; driver phải tự tạo request từ cấu hình và trạng thái phiên.

## 1. Nguồn đã đọc

| Nhóm | Nguồn | Nội dung sử dụng |
|---|---|---|
| Current | `current value.txt`, `current-value -.png`, `current-value-2.png`, `current-value-1.pdf` (3 trang) | 18 request sau đăng nhập; tên và số liệu Cumulative, Rates, MU, MD, Co-incident, Cumulative MD |
| Instantaneous | `instant paramter.txt`, `instant.pdf` (2 trang) | 9 selector; trạng thái polling; thông số pha A/B/C và tổng |
| Historical | `historical.txt`, `historical.CSV` | Historical set 1, số liệu và thông tin kỳ chốt |
| Load Profile gần nhất | `loadprofile.txt`, `image load profile.png` | Selector `0100`, 7 block đầy đủ; ảnh ngày 16/09/2026, khoảng 30 phút, giá trị 0 |
| Load Profile lưu trữ | `loadprofile-from19-06-2023.txt`, `loadprofile-19-06-23.CSV` | Selector `FFFF`; log bị cắt; CSV gồm 48 khoảng ngày 19/06/2023 |

Ảnh chụp ngày 16/09 và PDF/log ngày 18/09 là các lần lấy dữ liệu khác nhau. Giá trị Current trùng nhau trong bộ mẫu, nhưng không coi các lần xuất này là cùng thời điểm đo. CSV Historical và PDF Current có mốc đọc tương ứng với log ngày 18/09.

Kết quả đối chiếu bằng script, hash SHA-256 của 12 nguồn và ví dụ byte nằm trong [a1700-pmu-evidence-20260920.json](/home/shou/Documents/ATEnergy/j62056/doc/a1700-pmu-evidence-20260920.json). File bằng chứng không chứa mật khẩu hay cặp P0/P2.

[Logic parse và bảng bao phủ request](/home/shou/Documents/ATEnergy/j62056/doc/a1700-response-parsing-logic.md) liệt kê đầy đủ các loại dữ liệu, lệnh và đầu ra dự kiến. Đã phân loại 396 dạng request R1/W1 duy nhất trong log (gồm request cuối bị cắt); không có địa chỉ R1/W1 nào bị bỏ ngoài phạm vi task. Đây là mức bao phủ nghiên cứu, chưa phải số chức năng đã triển khai trong Java.

## 2. Current Value: đã có cơ sở viết decoder

### Tổng tích lũy, Rates và Cumulative Maximum Demands

`507001(40)` + `507002(10)` trả về 80 byte, tương ứng **10 giá trị × 8 byte**, không phải 16 giá trị × 5 byte như cách giải thích trước đây. Với mẫu này:

1. Đảo thứ tự 8 byte; giữ thứ tự hai nibble trong từng byte.
2. Mỗi nibble là một chữ số BCD; từ chối nibble A–F nếu trường đang được xử lý như số BCD.
3. Chuyển thành số nguyên thập phân và đặt 3 chữ số sau dấu thập phân.

Ví dụ: `88 66 18 27 00 00 00 00` → chuỗi BCD `0000000027186688` → `27,186.688 Import Wh`.

| Vùng raw | Cách ghép và cấu trúc quan sát | Kết quả đối chiếu |
|---|---|---|
| `507001` + `507002` | 80 byte, 10 × 8 byte BCD đảo byte, scale 3 | Khớp 10/10 tổng tích lũy, trong đó 9 giá trị khác 0 |
| `508001`…`508004` | 256 byte, 32 × 8 byte cùng cách giải mã | Khớp 32/32 Rates; 6 giá trị khác 0 |
| `509001` + `509002` | 72 byte, 8 × 9 byte; 8 byte giá trị + 1 byte loại nguồn | Khớp 8/8 Cumulative MD, đều khác 0; byte cuối `01` tương ứng Import W, `02` tương ứng Export W trong mẫu |
| `667001` | 32 byte `01 01 01 02 02 02 00…` | Khớp thứ tự nhãn 32 Rates: 3 Import, 3 Export, còn lại No Source |
| `668001` | 8 byte `01 01 01 01 02 02 02 02` | Khớp các nguồn Import/Export của Cumulative MD; vai trò chính xác so với byte nguồn trong `509` cần xác nhận thêm |

Thứ tự 10 tổng tích lũy: Import Wh, Export Wh, Q1 varh, Q2 varh, Q3 varh, Q4 varh, VAh, CD 1, CD 2, CD 3. Giữ nguyên tên CD khi chưa biết công thức cấu hình của khách hàng; không tự gán CD thành kWh.

Các ví dụ khác: Export Wh `35,767,486.896`; Rate 1 `13,886.800 Import Wh`; Cumulative MD 1 `890.848 Import W`. Các giá trị giải mã cũng khớp Historical CSV và PDF Current. Điều này chưa chứng minh mọi firmware hoặc cấu hình CT/PT đều có cùng hệ số.

### Những vùng Current chưa đủ bằng chứng giải mã

| Vùng | Kích thước / bố cục dự kiến | Điều còn thiếu |
|---|---|---|
| `516001(20)` | 32 byte, có thể là 4 MU × 8 byte | Mẫu chỉ có 0; cần MU khác 0 và đơn vị cấu hình |
| `510001`…`510005` | 288 byte; phù hợp 8 MD × 3 level × 12 byte | Toàn bộ 0; chưa chứng minh offset timestamp, value, source hay cờ |
| `511001` + `511002` | 120 byte; phù hợp 5 Co-incident × 3 level × 8 byte | Toàn bộ 0; chưa chứng minh layout số và nguồn |

PDF ghi `No Source` và `Not available` cho MD. Driver cần giữ các trạng thái này riêng với số đo hợp lệ bằng 0. Không lấy việc giải mã toàn số 0 làm bằng chứng đã giải quyết layout MD/MU.

## 3. Instantaneous: workflow rõ, decoder còn cần xác nhận một phần

Workflow của mỗi nhóm:

`W1 605001(selector)` → ACK → đọc `R1 605001(04)` tới khi cả bốn byte có bit `0x80` → `R1 606001(1C)`.

| Đại lượng | Selector |
|---|---|
| Dòng điện | `1A2A4A00` |
| Điện áp | `1B2B4B00` |
| Active power | `1C2C4C0C` |
| Reactive power | `1D2D4D0D` |
| Apparent power | `1E2E4E0E` |
| Power factor | `13234303` |
| Frequency | `18284800` |
| Phase angle | `19294900` |
| Phase rotation | `07000000` |

Nguồn: `instant paramter.txt`, dòng 15–120. Reactive power chỉ cần 3 lần polling trong log này; phase angle có một giá trị trạng thái lặp lại. Vì vậy không gửi cứng đúng 4 lần polling. Nên kiểm tra cả bit sẵn sàng và phần selector ở 7 bit thấp, kèm thời hạn chờ.

28 byte phản hồi chia được thành 4 slot × 7 byte. Các mẫu ủng hộ cách đọc mỗi slot gồm byte dấu/trạng thái và 6 byte BCD theo thứ tự big-endian, scale 4:

- Điện áp A: `00 00 00 02 22 46 33` → ứng viên `222.4633`, PMU hiển thị `222.46 V`.
- Tần số A: `00 00 00 00 50 24 92` → ứng viên `50.2492`, PMU hiển thị `50.24 Hz`. Làm tròn thông thường sẽ ra `50.25`; cần xác nhận quy tắc cắt/làm tròn của PMU.
- PF: `00 00 00 00 01 00 00` → `1.0000`, khớp PMU `1.000`.
- `00 FF 00 00 00 00 00` xuất hiện ở frequency B/C mà PMU ghi `Not available`.
- `80 00 00 00 00 00 00` đi cùng phase angle `-0.00`, gợi ý bit dấu; chưa có mẫu góc âm khác 0.
- Phase rotation có slot số 0 nhưng PMU ghi `Non available`; cần decoder enum riêng.

Đây là quy tắc ứng viên khớp các mẫu trên; chưa đủ dữ liệu để chốt thang đo dòng điện/công suất ở tải khác 0, tất cả mã trạng thái hay dấu của số khác 0.

## 4. Historical: xác định được một set và thông tin kỳ chốt

Log hiện tại đọc `667001`, `668001`, rồi `543001`…`54300D` mỗi block 64 byte, `54300E(1F)` 31 byte và `548001(40)`. **Không có request `544…` trong log này.** Cách đọc luôn kèm `544` trong bản Python cũ không thể coi là bắt buộc cho yêu cầu set 1 này.

Ghép 14 block `543` được 863 byte. Bố cục dưới đây khớp mẫu Current/CSV:

| Offset từ 0 | Độ dài | Nội dung |
|---:|---:|---|
| 0 | 80 | Tổng tích lũy, trùng `507` |
| 80 | 256 | Rates, trùng `508` |
| 336 | 32 | Vùng MU dự kiến, trùng vùng 0 của `516` |
| 368 | 72 | Cumulative MD, trùng `509` |
| 440 | 288 | Vùng MD dự kiến, trùng vùng 0 của `510` |
| 728 | 120 | Vùng Co-incident dự kiến, trùng vùng 0 của `511` |
| 848 | 15 | Thông tin kỳ chốt |

Vùng toàn 0 không xác nhận độc lập được cấu trúc của từng trường dù phép ghép khớp byte.

15 byte cuối: `1D00 004EC267 802CEB67 81 3654A96A`:

- Offset 848, uint16 little-endian: billing reset number `29`.
- Offset 850, 4 byte little-endian: giá trị theo phép chuyển epoch cho `2025-03-01 00:00:00`.
- Offset 854, 4 byte little-endian: `2025-04-01 00:00:00`.
- Offset 858: byte lý do `0x81`; CSV có đồng thời `Automatic billing date` và `Power-up process`. Chưa đủ bằng chứng để gán riêng bit 0 và bit 7 cho từng nhãn.
- Offset 859, 4 byte little-endian: `2026-09-15 14:20:38`; CSV chỉ hiển thị đến phút `2:20 PM`.

Phép chuyển epoch ở đây khớp giờ trên PMU khi so như giờ mặt đồng hồ. Chưa kết luận timezone/DST; không tự cộng thêm 7 giờ. Giữ riêng thời gian bắt đầu/kết thúc kỳ và thời gian reset, vì trong mẫu chúng ở các năm/ngày khác nhau.

Chưa có set 2 trong thư mục này để xác nhận quy tắc tăng địa chỉ/độ dài giữa các set. `548001` cần được giữ raw và nghiên cứu cấu hình; chưa giải mã trọn vẹn.

## 5. Load Profile: hai mẫu có mức độ đầy đủ khác nhau

### Thu thập block

- `loadprofile.txt`, dòng 15–32: `W1 551001(0100)` → ACK → `R1 551001(02)` trả `0007` → đọc đủ `550001`…`550007`, sau đó B0.
- `loadprofile-from19-06-2023.txt`, dòng 16–21: `W1 551001(FFFF)` → ACK → count `0545`. Diễn giải count unsigned big-endian cho `0x0545 = 1349` block.
- Log dài chỉ có **346 phản hồi đầy đủ**, đến `55015A`. Dòng 712 là request `55015B` bị cắt ở `<ET`, không có phản hồi và không có B0. Không được coi đây là một lần tải đủ 1349 block hay bằng chứng rằng meter từ chối block kế tiếp.
- Log dài gửi lại `/?005!` sau khoảng 3 giây trước khi nhận identification. Bản Java hiện chỉ gửi một lần.
- Tên file có ngày 19/06/2023, nhưng command chỉ có selector `FFFF`, không chứa ngày đó. Chưa đủ dữ liệu để tạo selector từ ngày tùy ý. API đọc khoảng thời gian cần tách bước thu raw và lọc theo timestamp sau giải mã, hoặc có thêm bằng chứng cho selector theo ngày.

### Ghép record và đối chiếu CSV

Đầu stream ngày 19/06 là `E4 80 9A 8F 64 03 03 99` (8 byte). Bốn byte sau `E4`, đọc little-endian theo epoch, khớp ngày `2023-06-19 00:00:00`. Mẫu các ngày đầu có header cách nhau 632 byte = 8 + 48 × 13. Không được đồng nhất một block truyền 64 byte với một khoảng đo.

Record đầu: `40 | 000000 | 249602 | 201602 | 000000`:

- Trong mẫu này, 1 byte trạng thái + 4 trường 3 byte, phù hợp các kênh bật: Import, Export, CD1, CD2.
- Với các trường có byte cuối `02`, đọc hai byte đầu như BCD big-endian và chia 1000 tái tạo Export/CD1 khớp **48/48 dòng CSV**. Đây chưa phải công thức cho mọi giá trị của byte thứ ba.
- Các giá trị khác 0 kiểm chứng được: Export `2.496`, `2.592`, `2.688`, `2.784`; CD1 `1.920`, `2.016`, `2.112`. CSV ghi đơn vị kW cho Import/Export.
- Có 192 phép so sánh số khớp (48 khoảng × 4 kênh), trong đó 96 giá trị khác 0. Quy tắc cho **Import hoặc CD2 khác 0** chưa được xác nhận; CD1/CD2 cũng không có đơn vị vật lý được xác nhận từ CSV. Không áp dụng chung mọi kênh chỉ vì trường có cùng độ dài.
- Trạng thái `0x40` đi cùng `Phase C fail` trong 48 dòng. Mẫu gần nhất dùng `0x60`; ảnh cho thấy `Phase B fail, Phase…` nhưng phần còn lại bị cắt, nên chưa chốt toàn bộ bảng bit.
- Mẫu `loadprofile.txt` có header ngày 16/09/2026 và phù hợp 33 record 13 byte, sau đó 11 byte FF đệm. Cần kiểm tra padding trong parser, không xuất FF thành giá trị đo.
- Ý nghĩa đầy đủ của `03 03 99`, mã scale/sign của trường 3 byte, marker sự kiện, cấu hình kênh và thay đổi khoảng tích phân chưa được xác nhận.

Sau khi đối chiếu guide và đọc lại stream, xác nhận marker `E6` đầu tiên ở offset **16661**, sau **1265** record thường: 26 ngày đầy đủ và 17 khoảng của ngày 15/07/2023. Bốn record tiếp theo là `E6625BB264`, `E5695BB264`, `E6695BB264`, `E5715BB264`; cách chuyển timestamp đang dùng cho các mốc 08:40:02, 08:40:09, 08:40:09, 08:40:17 ngày 15/07/2023. Chưa biết tên sự kiện hay cách nó ảnh hưởng khoảng tích phân. Phải giữ raw và dừng gán thời gian cho đoạn chưa hiểu; không bỏ qua E5/E6 rồi nối tiếp các khoảng 30 phút.

Chỉ 48 record ngày đầu có CSV đối chứng; 1217 record tiếp theo trước E6 mới khớp cấu trúc, chưa có đối chứng độc lập về số liệu. Các trường `192001` xuất hiện sau sự kiện xác nhận có mã khác `02`; chưa thể dùng quy tắc scale của ngày đầu cho toàn stream. Log gần nhất được kiểm tra lại: đúng 33 record, kết thúc 16:30 và 11 byte FF đệm.

48 start time và 48 nhãn trạng thái khớp CSV; 47/48 end time khớp. Cộng công suất Export của 48 khoảng rồi nhân 0.5 giờ cho **60.480 kWh**; đây là năng lượng suy ra từ profile, không phải giá trị thanh ghi tích lũy. Giá trị `2.496` của profile đã là kW theo CSV, không nhân đôi lần nữa.

CSV dòng cuối ghi start `11:30 PM`, end `12:00 PM`. Đây là mốc giờ không nhất quán với chuỗi khoảng 30 phút. Task kiểm thử phải phát hiện sai lệch nguồn này và kiểm tra cách biểu diễn qua nửa đêm; không lấy nhãn đó làm chân lý về thời gian.

## 6. Backlog đề xuất cho driver Java

| ID | Ưu tiên | Task | Phụ thuộc | Điều kiện hoàn thành |
|---|---|---|---|---|
| T01 | P0 | Tạo bộ mẫu kiểm thử từ log/ảnh/CSV đã đối chiếu | Không | Có nguồn, hash, serial, thời điểm, byte raw, giá trị kỳ vọng; parser phát hiện log profile bị cắt; fixture không dùng để tạo request runtime |
| T02 | P0 | Hoàn thiện session Java và cấu hình meter | Không | Hỗ trợ `/?005!` qua `-a 005`; retry sign-on có giới hạn; delay và timeout cấu hình được; key/serial/scheme là cấu hình; tính P2 theo challenge mới; lỗi có stage/register và raw đã nhận |
| T03 | P0 | Viết decoder Current cho totals, Rates, Cumulative MD | T01 | `BigDecimal` BCD8 scale 3; ghép đúng block; 10/10 totals, 32/32 Rates, 8/8 CMD khớp PMU; mã nguồn 0/1/2 và No Source được biểu diễn rõ |
| T04 | P1 | Hoàn thiện MU, MD, Co-incident và cấu hình nguồn | T03 + mẫu bổ sung | Có mẫu MU/MD/Co-incident khác 0 và timestamp hợp lệ; xác định byte layout trước khi gán nghĩa; kiểm chứng 4 MU, 24 MD, 15 Co-incident |
| T05 | P1 | Thêm workflow đọc Instantaneous trong Java | T02 | 9 selector; ACK; polling tới ready, chấp nhận 3/4/nhiều lần và trạng thái lặp; kiểm tra selector; timeout; đọc đúng 28 byte |
| T06 | P1 | Viết decoder Instantaneous và trạng thái khả dụng | T01, T05 | Khớp 222.46 V, 50.24 Hz, PF 1.000 và Not available B/C; chốt cách hiển thị làm tròn/cắt; có thêm tải và góc âm khác 0 để xác nhận scale/sign/power; phase rotation có enum |
| T07 | P1 | Đọc và giải mã Historical set 1 | T02, T03 | Đọc đúng 14 block 543 + 548 theo log này; 863 byte; tái dùng decoder; reset 29, kỳ 01/03–01/04/2025, reset 15/09/2026 khớp CSV; bảo lưu reason raw/timezone chưa rõ |
| T08 | P1 | Thêm tải Load Profile có báo đầy đủ/thiếu | T02 | Selector → ACK → count → đủ số block; sample 7-block hoàn tất; log 346-block báo thiếu; giới hạn tài nguyên cấu hình được, không cố định 256; lưu raw theo tiến độ; không lặp W1 tùy tiện khi retry |
| T09 | P1 | Viết decoder stream Load Profile cho cấu hình đã đối chiếu | T01, T08 | Ghép record qua ranh giới 64 byte; 192 số, 48 start time và 48 flags của ngày đối chứng khớp; xử lý 11 byte FF ở mẫu ngắn; dừng tại E6 offset 16661; giá trị/tag/kênh chưa hiểu phải có raw và trạng thái chưa giải mã |
| T10 | P1 | Xuất dữ liệu có cấu trúc và log theo từng phiên | T03; mở rộng theo T06–T09 | Kết quả có meter ID, nhóm, value/unit/quality, thời gian, register/offset/raw; log có ngày, timezone, cổng thật, tiến độ và kết quả cuối; giữ phần đã đọc khi lỗi; không ghi đè log thành công mặc định |
| T11 | P2 | Hoàn thiện transport, build và kiểm thử hồi quy Java | T02 và các decoder | Kiểm thử giao tiếp bằng stream giả lập, frame chia nhỏ/BCC sai/NAK/EOF/timeout; build tái lập; tách transport khỏi A1700; sửa tương tác mode; kiểm tra nhiều phiên thật liên tiếp |
| T12 | P1 | Giải mã sự kiện và định dạng số còn thiếu của Load Profile | T09 + mẫu bổ sung | Đối chiếu CSV quanh 15/07/2023 08:40; xác định E5/E6, khoảng thiếu/không đủ thời gian và mã `01`; có dữ liệu Import/CD2 khác 0; chỉ tiếp tục gán thời gian sau sự kiện khi quy tắc đã được xác nhận |
| T13 | P1 | Cấu hình kênh, đơn vị, khoảng tích phân và thời gian | T03, T06, T09 + cấu hình PMU | Cấu hình rõ 4 kênh bật/5 kênh Off và 30 phút của mẫu; bảo lưu `030399` chưa giải mã; phân biệt thời gian meter với thời gian thu thập; không tự áp CT/PT hay timezone; không gán kW cho CD khi chưa biết đơn vị |
| T14 | P1 | Historical nhiều set và cấu hình `548` | T07 + mẫu set 2 | Chốt quy tắc chọn set/địa chỉ từ ít nhất 2 set có dữ liệu khác nhau; giải thích 64 byte `548`; xác định nguồn lịch sử từ cấu hình đi cùng set; chưa tự gán nhãn bằng cấu hình Current nếu nguồn đã thay đổi |

### Chi tiết việc sửa nền Java để triển khai các task

- `Iec21Port.java:531` đang gửi cứng `/?!`; `Reader` nhận `-a` nhưng nhánh A1700 chưa sử dụng địa chỉ. Log mới dùng `005`. Phân biệt outstation `005` với serial `21000568` và các register sáu ký tự.
- `Iec21Port.java:548` gắn serial vào code; chuyển kiểm tra serial sang cấu hình của credential, không bỏ kiểm tra nhầm meter.
- `Iec21Port.java:594` gắn timeout vào `stty time 80`; `Reader.java:73` cũng ép 8000, nên `-t` chưa điều khiển được nhánh Current. Phải có deadline cả frame ngoài timeout từng lần đọc.
- Nhánh direct chỉ chạy trên Linux (`stty -F`). Khi dùng direct, `serialPort` là null; các API read/listen chuẩn cần guard hoặc transport chung. Tổ hợp CLI `-cv -l` hiện có thể rơi vào listen với null port.
- `Iec21Port.java:615`/`:629` chỉ ghi RX khi nhận đủ line/frame. Cần giữ cả partial bytes khi timeout, mất kết nối hoặc lỗi framing. Phân biệt NAK với ACK sai và báo request liên quan.
- `Iec21Port.java:562` gửi B0 trong finally: lỗi đóng phiên không được che lỗi đọc ban đầu. Đóng stream/port cần độc lập và có xử lý thất bại khi mở một trong hai stream.
- `Iec21Port.java:760` chỉ ghi giờ và nhãn cứng TTYUSB0. Cần ngày/offset timezone, tên cổng thật và sự kiện bắt đầu/kết thúc/lỗi. Log nguyên bản vẫn phải giữ được frame byte để đối chiếu.
- `Reader.java:117` mở log theo kiểu ghi đè; `PrintWriter` có thể giữ lỗi ghi mà không ném IOException. Cần file theo session hoặc chế độ explicit overwrite và báo lỗi ghi.
- `Reader.java:123` chỉ in kết quả sau khi toàn bộ map trả về. Thêm callback tiến độ để theo dõi live và kết quả từng phần.
- Java hiện mới có test BCC cơ bản. Những lần đã đọc meter thành công chứng minh luồng mẫu hoạt động, chưa thay thế kiểm thử lỗi và decoder.

### Mẫu cần bổ sung có mục đích

1. MU/MD/Co-incident có giá trị khác 0, với màn hình/export PMU và log cùng lần đọc.
2. Instantaneous khi dòng/công suất khác 0; phase B/C hoạt động; góc âm khác 0; nếu có, các trạng thái đảo chiều công suất.
3. Historical set 2 và một set có chỉ số khác Current để xác định thứ tự set, không suy ra stride chỉ từ set 1.
4. Một log load profile đầy đủ tới B0; các trường có byte scale khác `00/02`; cấu hình kênh, khoảng tích phân, CT/PT; một lần chọn thời gian khác có log selector tương ứng.
5. Nếu cần hỗ trợ điều chỉnh đồng hồ/DST/mất điện: mẫu riêng có các sự kiện này và CSV đối chứng.

Không cần chờ đủ mọi mẫu mới làm decoder Current đã có bằng chứng. Thứ tự đề xuất: **T01 + T02 → T03 → T10**, sau đó T05/T06, T07, T08/T09. T04 cần thu thêm mẫu; T11 được bổ sung theo từng nhóm triển khai.

T12–T14 hoàn thiện những nhánh đã có dữ liệu/request trong thư mục nhưng chưa đủ quy tắc tổng quát. Mốc đầu ra bao phủ thư mục gồm Current (93 mục báo cáo), Instantaneous (9 nhóm), Historical (cùng 93 mục theo kỳ chốt và billing metadata), Load Profile (cả hai selector, interval và record sự kiện), nhận dạng meter và cấu hình nguồn. Chỉ công nhận hoàn tất decoder một trường khi có bằng chứng; đọc được raw chưa đồng nghĩa biết giá trị vật lý.

## 7. Đối chiếu với A1700_Response_Parsing_Guide.md

Guide là phân tích thực nghiệm cùng bộ dữ liệu, không phải tài liệu register do hãng phát hành. Đã kiểm tra các khẳng định dưới đây trực tiếp với log/CSV thay vì chỉ chép lại guide.

| Nội dung guide | Kết quả đối chiếu / ảnh hưởng task |
|---|---|
| Totals/Rates BCD 8 byte little-endian, scale 3 | Đồng nhất; áp dụng T03. PDF đủ 32 Rates, trong khi phần cũ của guide chỉ nói screenshot có 16; dùng cập nhật §2.4 của guide |
| Cumulative MD 8 record × 9 byte, record 8 qua biên hai response | Đồng nhất; bắt buộc ghép `509001` và `509002`; không giải mã `509002` độc lập |
| 93 mục Current, 54 mục tái tạo kể cả MU bằng 0 | Phân biệt 50 mục thuộc families có mẫu khác 0 với 4 MU toàn 0; 39 MD/Co-incident toàn 0 không xác nhận layout |
| `668001` liên quan Cumulative MD | Không dùng nó để gán nguồn cho MD thường: PDF MD thường vẫn ghi No Source |
| Profile: 346 block, ngày đầu 192 số khớp | Đã kiểm tra lại; 96 số khác 0; 1349 là cách diễn giải trường `0545` theo count, chưa được kiểm chứng bằng một bản tải đủ cùng phiên |
| E5/E6 và mã `01` trong đoạn sau | Đã xác nhận byte và offset. Bổ sung T12; không tuyên bố decoder toàn bộ profile chỉ vì ngày đầu khớp |
| Import khác 0 chỉ là giả thuyết dùng chung scale | Giữ unresolved mặc định; giả định nếu bật phải đi kèm chất lượng/nguồn của giả định |
| Cấu hình 4 kênh, 30 phút | Đúng theo CSV của mẫu; chưa suy ra được từ header `030399`; bổ sung T13 |
| Epoch là giờ meter hiển thị | Đồng nhất với mẫu; timezone vẫn chưa xác nhận; tránh lệch +7 giờ khi chuyển sang Java |
| Phạm vi guide | Chủ yếu Current và Load Profile. Instantaneous, Historical và polling/session được bổ sung từ hai log và báo cáo tương ứng trong thư mục |

Guide nhắc `current value(1).txt`; file có tên đó không có trong thư mục khảo sát hiện tại. Nguồn kiểm chứng ở đây là `current value.txt` và `current-value-1.pdf`, được hash riêng trong file bằng chứng.

## 8. Tiêu chí hoàn thành toàn bộ phạm vi

1. Có workflow Java cho mỗi request trong bảng bao phủ, tự sinh frame và BCC, không cần file PMU lúc runtime.
2. Session nhận credential/cấu hình và tính P2 từ challenge mới; không phát lại P2 cố định từ log. Không đổi cấu hình đo lường hoặc reset chỉ số trong các workflow đọc này.
3. Tách kết quả **thu thập** (`complete`/`partial`/`failed`) khỏi **giải mã** (`verified_for_fixture`/`inferred`/`unsupported`/`not_available`). Không xuất unknown thành 0.
4. Bao phủ Current 93 mục và Historical 93 mục trong schema, nhưng chưa gán offset từng MD/Co-incident hoặc số MU khác 0 khi thiếu bằng chứng. Khi chưa giải mã, giữ raw theo family và trạng thái; không dựng dữ liệu giả cho đủ hàng.
5. Các mẫu đối chứng số học, timestamp, nguồn và chất lượng nêu trên đều khớp; ghi riêng ngoại lệ end-time dòng 51 của CSV, không sửa file nguồn.
6. Load Profile nhận đủ block theo count trước khi báo tải đủ; kết quả thiếu vẫn giữ block và lỗi. Marker sự kiện/tag mới phải được báo rõ ngay cả khi raw transfer đã hoàn tất.
7. Các chức năng chưa có capture trong thư mục, như event-log độc lập, instrumentation profile, đồng hồ/configuration write, không được đánh dấu đã kiểm chứng nhờ các log này. Chúng cần mẫu và task riêng nếu mở rộng phạm vi.
