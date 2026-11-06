# ZaloDuo - Login Zalo lên thiết bị mobile thứ 2

**Bối cảnh:** Với sự phát triển của các camera phone tới từ TQ ngày càng nhiều nguời sử dụng 2 điện thoại. Zalo PC đã dùng trên PC rồi. Vậy là còn thừa lại Zalo Web. Mình sẽ tận dụng lại Zalo Web ít ai dùng để làm nền tảng cho ZaloDuo.

**Kiến trúc app:** sử dụng Zalo Web và làm lại giao diện để thân thiện với mobile hơn còn các chức năng, mã hoá, lưu trữ vẫn tuân thủ theo Zalo Web. Có bổ sung thêm service keep websocket với Zalo để push thông báo khi k mở app. App kết hợp giữa native code và webview.

**Nền tảng hỗ trợ:** Android only

**Thông báo trên ZaloDuo khi hoạt động với Zalo mobile:**
1. Chạy nền => Nhận thông báo song song với điện thoại chính
2. Đang mở ZaloDuo => Điện thoại chính tịt thông báo do Zalo phát hiện đang dùng Zalo PC/Web nên cơ chế Zalo sẽ dừng bắn thông báo về mobile
3. Chuyển ZaloDuo về nền tiếp tục dùng tác vụ khác hoặc khoá màn hình điện thoại phụ => Zaloduo vẫn bắt noti bình thường và điện thoại Zalo chính sẽ có thông báo sau vài phút do Zalo phát hiện ra không còn sử dụng Zalo PC/Web nữa nên sẽ bắn về thêm cho Zalo chính. Cái này do cơ chế của Zalo k can thiệp dc.

Nchung là thông báo Zalo sẽ đối xử ZaloDuo giống việc bạn sử dụng Zalo PC.

**Cuộc gọi (từ bản 3.0 beta):** Zalo Web không gọi được nên ZaloDuo tự làm phần gọi riêng, kết nối như Zalo PC.
1. Gọi thoại và gọi video 1-1: gọi đi, nhận cuộc gọi đến (popup khi đang dùng máy, toàn màn hình khi máy khoá).
2. Màn hình gọi: loa ngoài, tắt/bật mic, tắt/bật camera, đổi camera trước/sau, kéo ô hình của mình tới chỗ bất kỳ, chụm/tách 2 ngón để vừa khung/toàn màn hình, nút và thông tin tự ẩn khi gọi video (chạm để hiện lại).
3. Cửa sổ nổi (PiP) khi gọi video: vừa gọi vừa chat hoặc dùng app khác, có nút Mic và Kết thúc.
4. Thông số cuộc gọi (ping, tốc độ tiếng/hình, mất gói, độ phân giải), bật/tắt trong Cài đặt.
5. Cài đặt → Cuộc gọi: bật/tắt nhận cuộc gọi đến, nhạc chuông, giữ kết nối khi khoá màn hình.
6. Gọi nhóm: đang thử nghiệm.

**Nhược điểm:**
1. App cần đc ngoại lệ tối ưu hoá pin tối đa do app k bắn thông báo được qua FCM nên cần duy trì websocket với Zalo để nhận và bạn notification. Đối với Huawei/Honor cần tắt Quản lý tự động và bật đủ Tự khởi chạy - Khởi chạy thứ cấp - Chạy dưới nền hệ thống
2. Chức năng phụ thuộc vào Zalo Web k đầy đủ như Zalo mobile nhưng đủ dùng với 1 máy phụ.

**Cách vào cài đặt app:** giữ icon app ngoài màn hình sẽ có menu Cài đặt

**Nhóm Telegram:** tham gia để trao đổi, góp ý và báo lỗi: https://t.me/+cT9bjoT7nJhmZTZl

**Chính sách:**
- Ứng dụng liên quan tới dữ liệu nhạy cảm nên ứng dụng đc công khai mã nguồn tại github: quocanh21081992/ZaloDuo. Mọi người có thể tải last build ở đây.
- Ứng dụng hoàn toàn miễn phí. Mọi người có thể fork về và tùy chỉnh theo nhu cầu cá nhân.

Vì là ứng dụng miễn phí. Mọi người có thể gửi coffee cho mình qua STK: 0778211992 MBBank. Xin cảm ơn.
