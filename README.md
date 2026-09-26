# NGỌC SĨ MUSIC

Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Bản hiện tại

- Version: **4.3**
- Target SDK: **Android 16 / API 36**
- Media3: **1.9.4**
- Min SDK: **26**

## Tính năng chính

- Nhạc trong thiết bị qua MediaStore
- Phát nền bằng Media3/MediaSession
- Queue, Previous/Next, Shuffle, Repeat, tốc độ phát
- Lưu trạng thái bài đang phát
- Yêu thích
- Playlist lưu trên thiết bị
- Google Drive: chọn file hoặc thư mục
- Nhạc online: Audius và Jamendo
- YouTube Search + thumbnail + player nhúng chính thức
- Radio: VOV1/VOV2/VOV3 phát trực tiếp khi có luồng HLS hợp lệ; các kênh khác mở nguồn chính thức
- Widget điều khiển phát nhạc
- Tìm kiếm bằng giọng nói
- Hẹn giờ tắt nhạc

## Build APK

GitHub Actions tự động build khi push vào main hoặc chạy thủ công.

Artifact hiện tại:

NGOC_SI_MUSIC_4.3_DEBUG_APK

APK debug được kiểm tra tồn tại và tạo kèm SHA-256.

## Ghi chú YouTube

YouTube được phát bằng embedded player; ứng dụng không tải xuống hoặc tách luồng âm thanh YouTube. WebView cung cấp HTTP Referer theo yêu cầu của YouTube.

## Ghi chú Radio

URL livestream có thể thay đổi theo hạ tầng của từng đài. Ứng dụng dùng nhiều URL theo thứ tự ưu tiên và có watchdog tự chuyển sang nguồn dự phòng khi Media3 gặp lỗi hoặc buffering kéo dài.