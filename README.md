# NGỌC SĨ MUSIC PRO

Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Bản PRO hiện tại

- Version: **5.9 PRO**
- Version code: **29**
- Package: **com.ngocsi.music**
- Target/Compile SDK: **Android 16 / API 36**
- Min SDK: **26**
- Java/Kotlin: **17**
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
- Google Drive: duyệt nhiều tầng, tìm kiếm, thêm từng bài hoặc thêm tất cả, hiển thị tên dài và dung lượng
- Google Drive: hỗ trợ phân trang khi thư mục/Drive có trên 1.000 mục
- Google Drive: hỗ trợ nguồn được chia sẻ và khôi phục playback bằng Media3

## Google Drive / OAuth Android

APK 5.8 PRO hiện được GitHub Actions ký bằng chứng thư ổn định với SHA-1:

**53:3F:A1:9C:58:A4:0D:92:66:8F:96:29:6E:7D:B3:4D:69:6A:ED**

Trong Google Cloud Console, Android OAuth 2.0 Client phải dùng đúng cặp:

- Package name: **com.ngocsi.music**
- SHA-1: **53:3F:A1:9C:58:A4:0D:92:66:8F:96:29:6E:7D:B3:4D:69:6A:ED**

Không dùng SHA-1 của debug keystore khác cho APK tải từ GitHub Release. Các chứng thư khác nhau cần Android OAuth client tương ứng.

Drive API cần được bật trong cùng Google Cloud project. App sử dụng scope đọc Drive:

`https://www.googleapis.com/auth/drive.readonly`

Sau khi OAuth client khớp package + SHA-1, màn hình đăng nhập Google Drive mới có thể hoàn tất luồng cấp quyền Drive.

## TV PRO

- Direct HTTPS .m3u8/.mp4/.webm sources use native Media3 video playback with buffering state and playback controls
- Provider web pages continue to use the hardened WebView player

## Radio PRO

- VOV1, VOV2, VOV3, VOV4, VOV5, VOV6
- VOV Giao Thông Hà Nội, TP.HCM, Mekong, Duyên Hải
- VOH FM 95.6, AM 610, FM 99.9
- Nhiều luồng HTTPS dự phòng
- Tự chuyển luồng khi buffering/lỗi
- Nguồn chính thức trong ứng dụng khi không còn stream trực tiếp khả dụng

Các URL livestream có thể thay đổi theo hạ tầng của đài; app giữ nhiều candidate và nguồn chính thức để giảm lỗi chết stream.

## Map PRO

- Current location permission flow with cached-location fast path
- Active GPS/network provider fallback and timeout recovery
- OpenStreetMap embedded map with current-position marker
- Bản đồ, chỉ đường và vị trí hiện tại hoạt động theo luồng riêng

## Widget

- Play/Pause
- Previous/Next
- Mở app
- Cập nhật trạng thái phát và metadata

## Build & kiểm tra

GitHub Actions chạy tự động:

1. Lint Debug
2. Unit tests
3. Build PRO APK
4. Verify signing mode
5. Verify APK
6. Kiểm tra SHA-1 chứng thư ký
7. Tạo SHA-256
8. Upload artifact
9. Publish GitHub Release

Artifact chuẩn:

**NGOC_SI_MUSIC_5.8_PRO_DEBUG_APK**

## YouTube

YouTube được phát bằng embedded player chính thức; app không tải xuống hoặc tách luồng âm thanh YouTube.
