# ZaloDuo - Login Zalo lên thiết bị mobile thứ 2

**Bối cảnh:** Với sự phát triển của các camera phone tới từ TQ ngày càng nhiều nguời sử dụng 2 điện thoại. Zalo PC đã dùng trên PC rồi. Vậy là còn thừa lại Zalo Web. Mình sẽ tận dụng lại Zalo Web ít ai dùng để làm nền tảng cho ZaloDuo.

**Kiến trúc app:** sử dụng Zalo Web và làm lại giao diện để thân thiện với mobile hơn còn các chức năng, mã hoá, lưu trữ vẫn tuân thủ theo Zalo Web. Có bổ sung thêm service keep websocket với Zalo để push thông báo khi k mở app. App kết hợp giữa native code và webview.

**Nền tảng hỗ trợ:** Android only

**Thông báo trên ZaloDuo khi hoạt động với Zalo mobile:**
1. Chạy nền => Nhận thông báo song song với điện thoại chính
2. Đang mở ZaloDuo => Điện thoại chính tịt thông báo do Zalo phát hiện đang dùng Zalo PC/Web nên cơ chế Zalo sẽ dừng bắn thông báo về mobile
3. Chuyển ZaloDuo về nền tiếp tục dùng tác vụ khác hoặc khoá màn hình điện thoại phụ => Zaloduo vẫn bắt noti bình thường và điện thoại Zalo chính sẽ có thông báo sau vài phút do Zalo phát hiện ra không còn sử dụng Zalo PC/Web nữa nên sẽ bắn về thêm cho Zalo chính. Cái này do cơ chế của Zalo k can thiệp dc.

Nchung là thông báo Zalo sẽ đối xử ZaloDuo giống việc bạn sử dụng Zalo PC.

**Nhược điểm:**
1. ZaloDuo k có cuộc gọi do Zalo web k hỗ trợ.
2. App cần đc ngoại lệ tối ưu hoá pin tối đa do app k bắn thông báo được qua FCM nên cần duy trì websocket với Zalo để nhận và bạn notification. Đối với Huawei/Honor cần tắt Quản lý tự động và bật đủ Tự khởi chạy - Khởi chạy thứ cấp - Chạy dưới nền hệ thống
3. Chức năng phụ thuộc vào Zalo Web k đầy đủ như Zalo mobile nhưng đủ dùng với 1 máy phụ.

**Cách vào cài đặt app:** giữ icon app ngoài màn hình sẽ có menu Cài đặt

**Nhóm Telegram:** tham gia để trao đổi, góp ý và báo lỗi: https://t.me/+cT9bjoT7nJhmZTZl

**Chính sách:**
- Ứng dụng liên quan tới dữ liệu nhạy cảm nên ứng dụng đc công khai mã nguồn tại github: quocanh21081992/ZaloDuo. Mọi người có thể tải last build ở đây.
- Ứng dụng hoàn toàn miễn phí. Mọi người có thể fork về và tùy chỉnh theo nhu cầu cá nhân.

Vì là ứng dụng miễn phí. Mọi người có thể gửi coffee cho mình qua STK: 0778211992 MBBank. Xin cảm ơn.
