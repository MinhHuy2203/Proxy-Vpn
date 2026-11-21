# PAC Proxy VPN 2.0

VPN theo từng app: chỉ app đích (mặc định `busidol.mobile.tower`) bị đưa qua proxy.

## Cách hoạt động
- `VpnService` tạo TUN, route `0.0.0.0/0` nhưng chỉ cho app đích đi vào.
- `TunEngine` (Engine.kt) tự xử lý TCP ở userspace và mở mỗi kết nối qua **HTTP CONNECT** tới proxy (Python bridge `127.0.0.1:8888`).
- Nhập URL PAC hoặc `host:port`. Với PAC, app lấy địa chỉ trong dòng `PROXY host:port` đầu tiên (không chạy JavaScript của PAC, mọi TCP đều đi qua proxy đó).
- UDP đi thẳng (proxy HTTP không hỗ trợ UDP). Truy vấn DNS AAAA bị chặn để app dùng IPv4; gói IPv6 bị bỏ.
- Nếu proxy không mở được TCP, VPN sẽ không bật (tránh làm game mất mạng).

## Gỡ lỗi
Nút **Kiểm tra proxy** và khung **Nhật ký** hiển thị từng kết nối thành công/thất bại (✓/✗) cùng lý do từ proxy.

## Build
Chạy `bash push.sh` trong Termux (tự giải nén, commit, push lên gốc repo) và chạy GitHub Actions. Artifact: `PACProxyVPN-debug`.

## 2.1 — lọc theo luật PAC
Trước đây app đẩy toàn bộ TCP của app đích qua proxy, bỏ qua luật trong file PAC. Giờ app đọc các điều kiện `host.indexOf("...")` trong PAC, dò tên máy chủ qua TLS SNI (không giải mã nội dung), rồi tự quyết định mỗi kết nối nên **qua proxy** hay **đi thẳng**, đúng như PAC quy định. Khung thống kê hiển thị số kết nối theo từng loại để kiểm tra.


## HTTP forwarding và giới hạn inject

- HTTP thường được gửi tới Python dưới dạng absolute-form (`GET http://host/path ...`) để handler `do_GET`/`do_POST` có thể xử lý và inject response.
- HTTPS được chuyển bằng HTTP CONNECT tunnel. Tunnel không giải mã TLS, vì vậy Python không thể chèn JavaScript vào response HTTPS nếu không có cơ chế TLS interception và chứng chỉ tin cậy riêng.
- UDP (bao gồm phần lớn traffic realtime của game) vẫn đi trực tiếp, không qua HTTP proxy.
- VPN chỉ áp dụng cho package đã chọn; Python bridge phải đang lắng nghe tại địa chỉ/cổng đã cấu hình.
