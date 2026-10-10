# NGỌC SĨ AI — bản xem trước giao diện

## Mục tiêu
Chuẩn bị giao diện trợ lý NGỌC SĨ AI trước, theo cách có thể thử trên điện thoại mà chưa phải cấu hình Firebase/Gemini hoặc bật thanh toán AI.

## Đã có trong nhánh này
- Ô mở nhanh **NGỌC SĨ AI** trên trang chủ.
- Màn hình chat nền tối, bong bóng tin nhắn, ô nhập liệu, nút gửi, gợi ý câu hỏi và nhập liệu bằng giọng nói của Android.
- Nhãn rõ ràng cho biết đây là bản xem trước; câu trả lời hiện chỉ là thông báo mẫu, chưa phải câu trả lời từ AI.
- Không có yêu cầu chat gửi tới Gemini/Firebase; không cần API key để dùng giao diện xem trước.
- Cả APK Debug lẫn Release của nhánh này dùng mã gói riêng `com.ngocsi.music.aipreview`, không ghi đè ứng dụng NGỌC SĨ MUSIC đang dùng mã gói `com.ngocsi.music`.
- Workflow riêng chỉ chạy lint, unit tests và build Debug APK; không phát hành Release và không thay thế APK sản phẩm.

## Giới hạn cần biết
- Tin nhắn chỉ được giữ trong trạng thái màn hình hiện tại; thoát màn hình có thể làm mất cuộc hội thoại.
- Nhập giọng nói mở dịch vụ nhận dạng giọng nói có sẵn trên Android. Tùy thiết bị/dịch vụ, tính năng này có thể yêu cầu kết nối mạng hoặc quyền riêng của dịch vụ đó; nó không gọi Gemini.
- Muốn có câu trả lời AI thật cần một bước tích hợp máy chủ được rà soát riêng. Không đưa khóa Gemini trực tiếp vào APK hoặc kho GitHub.

## Kiểm tra trên điện thoại
1. Mở nhánh `feature/ngoc-si-ai-ui-preview` trên GitHub.
2. Mở tab **Actions**, chọn workflow **NGOC SI AI UI Preview CI**, và xem lần chạy mới nhất có trạng thái **Success**.
3. Trong lần chạy thành công, mở artifact `NGOC_SI_AI_UI_PREVIEW_DEBUG_APK` để tải APK xem trước.
4. Cài APK xem trước. Tên gói riêng giúp cài song song với NGỌC SĨ MUSIC hiện tại.
5. Thử mở NGỌC SĨ AI, gửi câu mẫu, dùng nút micro (nếu thiết bị hỗ trợ), mở bàn phím và quay lại trang chủ.

Việc kiểm tra trên GitHub không thay thế bước mở thử trên điện thoại thực tế. Nhánh này không được gộp vào `main` và không xuất bản APK sản phẩm.
