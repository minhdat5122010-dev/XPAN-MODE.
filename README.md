# Xpan-Mode

Ứng dụng chụp ảnh Android chuyên cho khung hình panorama **XPan 65:24**, giao diện và hiệu ứng chụp mô phỏng chế độ X-Pan trên các dòng OPPO × Hasselblad.

## Tính năng

- **Hai giao diện, chuyển bằng nút ⇄ ở góc trên trái**
  - *Hasselblad*: nền xám đậm, chữ HASSELBLAD, thang zoom 0,6 · 1 · 2 · 3×, nút chụp trắng, huy hiệu bộ lọc.
  - *Tối giản*: nền đen, viền ngắm mờ, nút chụp cam, các nút tiêu cự 14 · 23 · 65 · 135 mm.
- **Hiệu ứng chụp**: đảo âm bản → xám phẳng → mờ dần về ảnh.
- **Màn hình xem lại**: khung trắng + chữ HASSELBLAD + thông số (tên máy, FL, khẩu độ, tốc độ, ISO). Chạm vào ảnh thu nhỏ để mở; có nút lưu ảnh khung.
- **Đọc đầy đủ ống kính**: gộp camera trong `cameraIdList` + camera vật lý ẩn trong camera logic + dò ID 0–99. Xem danh sách chi tiết tại ⚙ Cài đặt (tiêu cự thật, tương đương 35mm, khẩu độ, cảm biến, MP, OIS, RAW...).
- **Chất lượng ảnh**: chụp JPEG độ phân giải tối đa của từng ống kính, `JPEG_QUALITY=100`, khử nhiễu/làm nét/quang sai ở chế độ HIGH_QUALITY. Khung 65:24 và zoom số được **cắt từ ảnh gốc** (không phóng to nội suy), lưu JPEG chất lượng 100 kèm EXIF. Tuỳ chọn lưu thêm DNG (RAW) và ảnh gốc full-frame.
- **Bộ lọc**: Original, Natural, Portrait, Landscape, XPan Chrome, Golden Hour, Mono (phong cách màu tự nhiên, tương phản mềm, bão hoà vừa phải).
- Chạm để lấy nét/đo sáng, vuốt dọc bên trái để bù sáng, chụm hai ngón để zoom.
- Ảnh lưu tại `Pictures/Xpan-Mode`.

## Build

**Cách 1 – GitHub Actions (không cần cài gì):** đẩy toàn bộ thư mục lên GitHub, vào tab *Actions* → workflow *Build APK* → tải artifact `Xpan-Mode-debug-apk`.

**Cách 2 – Android Studio:** mở thư mục, chờ Gradle sync, bấm Run.

Yêu cầu: Android 10+ (API 29), JDK 17.

## Ghi chú

- Tên "Hasselblad" và phong cách giao diện thuộc về chủ sở hữu thương hiệu. Dự án này chỉ dùng chữ, không dùng logo gốc; hãy dùng cho mục đích cá nhân/học tập.
- Xem trước bộ lọc là xấp xỉ bằng ColorMatrix; ảnh xuất dùng đường cong từng kênh nên có thể lệch nhẹ.
- Một số máy chặn app bên thứ ba mở camera vật lý; app tự dự phòng qua camera logic.
