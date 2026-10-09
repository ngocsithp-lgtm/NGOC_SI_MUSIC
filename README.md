# NGỌC SĨ MUSIC PRO

Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Bản PRO hiện tại

- Version: **5.25 PRO**
- Version code: **43**
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

APK 5.25 PRO hiện được GitHub Actions ký bằng chứng thư ổn định với SHA-1:

**53:3F:A1:9C:58:A4:0D:92:66:8F:96:29:6E:7D:B3:4D:69:6A:ED**

Trong Google Cloud Console, Android OAuth 2.0 Client phải dùng đúng cặp:

- Package name: **com.ngocsi.music**
- SHA-1: **53:3F:A1:9C:58:A4:0D:92:66:8F:96:29:6E:7D:B3:4D:69:6A:ED**

Không dùng SHA-1 của debug keystore khác cho APK tải từ GitHub Release. Các chứng thư khác nhau cần Android OAuth client tương ứng.

Drive API cần được bật trong cùng Google Cloud project. App sử dụng hai scope Drive đúng với chức năng hiện tại:

`https://www.googleapis.com/auth/drive.readonly`

`https://www.googleapis.com/auth/drive.appdata`

`drive.readonly` dùng để đọc/tải các tệp Drive mà tài khoản được phép truy cập; `drive.appdata` dùng để lưu cấu hình nguồn của NGỌC SĨ MUSIC trong `appDataFolder`.

Trong Google Cloud Console, mục **Data Access** phải có đúng hai scope trên. Nếu project đang ở chế độ Testing, tài khoản Google dùng để thử phải nằm trong **Test users**.

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

## Thay đổi phiên bản 5.25

- Trang chủ hiển thị phiên bản tự động từ cấu hình build, tránh nhãn phiên bản cũ.
- Thanh tua trang chủ chỉ gửi lệnh seek khi thả tay; trong khi kéo, vị trí xem trước được cập nhật cục bộ để giảm lệnh tua dồn dập.
- Tăng nhẹ cỡ chữ nhãn điều hướng và ô tiện ích để dễ đọc hơn trên điện thoại.

- Sửa quy tắc thông báo hết giờ để không bị hụt khi giao diện và dịch vụ cập nhật trạng thái lệch nhịp; thêm kiểm thử đơn vị cho các trường hợp hết giờ/thay đổi hẹn giờ.
- Pipeline build cả Debug và Release, xác minh chữ ký ổn định cho cả hai bản và tạo SHA-256 riêng.

- Đã loại bỏ mục Bản đồ khỏi trang chủ, menu tiện ích và Cài đặt.
- Đã gỡ màn hình Bản đồ khỏi luồng điều hướng trong ứng dụng; các chức năng vệ tinh, vị trí và chỉ đường không còn được cung cấp trong giao diện.

## Widget

- Play/Pause
- Previous/Next
- Mở app
- Cập nhật trạng thái phát và metadata

## Build & kiểm tra

GitHub Actions chạy tự động:

1. Lint Debug
2. Unit tests
3. Build Debug APK để chẩn đoán
4. Build Release APK ký ổn định để dùng hằng ngày
5. Verify signing mode
6. Verify package/version và chữ ký cho cả hai APK
7. Kiểm tra SHA-1 chứng thư ký
8. Tạo SHA-256 cho cả hai APK
9. Upload artifact
10. Publish GitHub Release

Artifact chuẩn:

**NGOC_SI_MUSIC_5.25_PRO_APKS**

Bản Release dùng chứng thư ký ổn định giống Debug:

[**Tải APK Release 5.25 PRO**](https://github.com/ngocsithp-lgtm/NGOC_SI_MUSIC/releases/download/ngoc-si-music-latest/app-release.apk)

[**Tải APK Debug 5.25 PRO**](https://github.com/ngocsithp-lgtm/NGOC_SI_MUSIC/releases/download/ngoc-si-music-latest/app-debug.apk)

## YouTube

YouTube được phát bằng embedded player chính thức; app không tải xuống hoặc tách luồng âm thanh YouTube.
