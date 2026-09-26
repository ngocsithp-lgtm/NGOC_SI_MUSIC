# NGỌC SĨ MUSIC PRO

Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Bản PRO

- Version: **5.2 PRO**
- Version code: **22**
- Target SDK: **Android 16 / API 36**
- Min SDK: **26**
- Media3: **1.9.4**

## Player PRO

- Nhạc trong thiết bị qua MediaStore
- Phát nền bằng Media3/MediaSession
- Lock-screen / media notification
- Queue, Previous/Next, Shuffle, Repeat, tốc độ phát
- Khôi phục bài hát, vị trí và queue
- Yêu thích
- Playlist lưu trên thiết bị
- Hẹn giờ tắt nhạc, tự khôi phục sau khi mở lại

## Online PRO

- Audius + Jamendo
- Hủy request tìm kiếm cũ khi tìm kiếm mới
- YouTube Search + thumbnail + embedded player chính thức
- Lịch sử và yêu thích YouTube
- Tìm kiếm bằng giọng nói
- Google Drive: file hoặc thư mục

## Radio PRO

- VOV1, VOV2, VOV3, VOV4, VOV5, VOV6
- VOV Giao Thông Hà Nội, TP.HCM, Mekong, Duyên Hải
- VOH FM 95.6, AM 610, FM 99.9
- Nhiều luồng HTTPS dự phòng
- Tự chuyển luồng khi buffering/lỗi
- Nguồn chính thức trong ứng dụng khi không còn stream trực tiếp khả dụng

Các URL livestream có thể thay đổi theo hạ tầng của đài; app giữ nhiều candidate và nguồn chính thức để giảm lỗi chết stream.

## Widget

- Play/Pause
- Previous/Next
- Mở app
- Cập nhật trạng thái phát và metadata

## Build & kiểm tra

GitHub Actions tự động chạy:

1. Lint Debug
2. Build Debug APK
3. Verify APK
4. SHA-256
5. Upload artifact

Artifact: **NGOC_SI_MUSIC_5.2_PRO_DEBUG_APK**

## YouTube

YouTube được phát bằng embedded player chính thức; app không tải xuống hoặc tách luồng âm thanh YouTube.
