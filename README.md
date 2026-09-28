# 🎥 OnlineMeeting

OnlineMeeting là ứng dụng họp trực tuyến được xây dựng bằng **JavaFX** và lập trình mạng **TCP/UDP Socket**.

Ứng dụng cho phép nhiều người dùng tham gia cùng một phòng họp, giao tiếp bằng âm thanh thời gian thực, theo dõi danh sách thành viên và nhắn tin trong phòng.

> 📚 Project được xây dựng nhằm thực hành **Lập trình mạng với Java**, tập trung vào TCP Socket, UDP Socket, xử lý đa luồng và giao tiếp Client–Server.

---

## ✨ Chức năng

### 👤 Người dùng
- Đăng nhập bằng tên người dùng.
- Duy trì kết nối trong suốt phiên sử dụng.

### 🏠 Phòng họp
- Tạo phòng họp mới.
- Tự động sinh Room ID.
- Tham gia phòng bằng Room ID.
- Rời phòng và quay lại màn hình chính.

### 👥 Thành viên
- Hiển thị danh sách người tham gia phòng.
- Cập nhật thành viên theo thời gian thực.
- Hiển thị người vừa tham gia.
- Xóa người dùng khỏi giao diện khi họ rời phòng.

### 🎤 Audio
- Truyền âm thanh thời gian thực.
- Bật / tắt microphone.
- Âm thanh chỉ được truyền giữa các thành viên trong cùng phòng.
- Sử dụng UDP để giảm độ trễ khi truyền audio.

### 💬 Chat
- Chat toàn phòng.
- Chat riêng giữa hai thành viên.
- Chuyển đổi giữa chat "Mọi người" và chat riêng.
- Tách lịch sử chat của từng cuộc trò chuyện trong phiên hiện tại.

### 📹 Video
- Camera / Video Call: **đang phát triển**.

---

## 🛠 Công nghệ sử dụng

| Công nghệ | Mục đích |
|---|---|
| Java 21 | Ngôn ngữ lập trình chính |
| JavaFX 21 | Xây dựng giao diện |
| Maven | Quản lý project và dependency |
| TCP Socket | Login, quản lý phòng, thành viên và chat |
| UDP Socket | Truyền âm thanh thời gian thực |
| Java Sound API | Thu âm microphone và phát audio |
| Java Thread | Xử lý nhiều client và dữ liệu realtime |
| JPMS | Quản lý Java Module |

---

## 🏗 Kiến trúc hệ thống

OnlineMeeting sử dụng mô hình **Client – Server**.

```text
                         OnlineMeeting Server
                    ┌─────────────────────────┐
                    │                         │
                TCP :5000                UDP :6000
                    │                         │
                    │                         │
          Control / Signaling               Audio
                    │                         │
        ┌───────────┼───────────┐             │
        │           │           │             │
      Login       Rooms       Chat         Voice
                    │
              Participants
                    │
        ┌───────────┴───────────┐
        │                       │
     Client A                Client B
```

### TCP - Port 5000

TCP được sử dụng cho các dữ liệu cần đảm bảo độ tin cậy:

```text
Login
Create Room
Join Room
Leave Room
Participants
Room Chat
Private Chat
```

### UDP - Port 6000

UDP được sử dụng để truyền:

```text
Microphone Audio
```

Audio được gửi qua server và relay tới những client khác trong cùng phòng.

---

## 📁 Cấu trúc project

```text
OnlineMeeting/
│
├── pom.xml
│
└── src/
    └── main/
        │
        ├── java/
        │   ├── module-info.java
        │   │
        │   └── com/example/onlinemeeting/
        │       │
        │       ├── client/
        │       │   ├── OnlineMeetingApplication.java
        │       │   ├── TCPClient.java
        │       │   └── AudioClient.java
        │       │
        │       ├── controller/
        │       │   ├── LoginController.java
        │       │   ├── HomeController.java
        │       │   └── MeetingController.java
        │       │
        │       ├── model/
        │       │   ├── Room.java
        │       │   └── ChatMessage.java
        │       │
        │       ├── server/
        │       │   ├── MeetingServer.java
        │       │   ├── ClientHandler.java
        │       │   ├── RoomManager.java
        │       │   └── AudioServer.java
        │       │
        │       └── test/
        │           ├── TCPTestClient.java
        │           ├── TCPTestClient2.java
        │           └── AudioTestClient.java
        │
        └── resources/
            └── com/example/onlinemeeting/
                ├── fxml/
                │   ├── login-view.fxml
                │   ├── home-view.fxml
                │   └── meeting-view.fxml
                │
                ├── css/
                └── images/
```

---

## 🚀 Cách chạy

### Yêu cầu

Đảm bảo máy đã cài đặt:

- JDK 21
- Maven
- IntelliJ IDEA hoặc IDE hỗ trợ JavaFX
- Microphone và loa/tai nghe

Kiểm tra Java:

```bash
java --version
```

Kiểm tra Maven:

```bash
mvn --version
```

---

## 💻 Chạy trên một máy

### 1. Khởi động Server

Chạy:

```text
MeetingServer.java
```

Server sẽ mở:

```text
TCP Server  : 5000
Audio Server: 6000
```

### 2. Khởi động Client

Chạy:

```text
OnlineMeetingApplication.java
```

Có thể chạy nhiều instance Client để kiểm tra nhiều người dùng trên cùng một máy.

Khi chạy local, địa chỉ server có thể sử dụng:

```java
localhost
```

hoặc:

```java
127.0.0.1
```

---

## 🌐 Chạy trên hai máy cùng Wi-Fi/LAN

Ví dụ:

```text
Laptop A
IP: 192.168.1.10
├── MeetingServer
└── OnlineMeeting Client

            Wi-Fi / LAN
                 ↕
Laptop B
└── OnlineMeeting Client
```

### Bước 1: Lấy IP của máy Server

Trên Windows:

```bash
ipconfig
```

Tìm:

```text
IPv4 Address
```

Ví dụ:

```text
192.168.1.10
```

### Bước 2: Cấu hình Client

Thay địa chỉ:

```java
localhost
```

bằng IP máy Server:

```java
192.168.1.10
```

TCP:

```java
client.connect("192.168.1.10", 5000);
```

Audio:

```java
new AudioClient("192.168.1.10", roomId);
```

### Bước 3: Kiểm tra Firewall

Máy chạy Server cần cho phép kết nối:

```text
TCP 5000
UDP 6000
```

Nếu Windows Firewall hỏi quyền truy cập Java, cần cho phép ứng dụng hoạt động trên mạng đang sử dụng.

### Bước 4: Test

Laptop A:

```text
Username: Qanh
→ Tạo phòng
→ Room ID: ABC123
```

Laptop B:

```text
Username: MVy
→ Join Room
→ ABC123
```

Hai máy sau đó có thể kiểm tra:

```text
✓ Hiển thị thành viên realtime
✓ Audio realtime
✓ Mute / Unmute
✓ Chat toàn phòng
✓ Chat riêng
✓ Join / Leave Room
```

---

## 📡 TCP Protocol

Một số message được sử dụng giữa Client và Server:

```text
LOGIN|username

CREATE_ROOM
JOIN_ROOM|roomId
LEAVE_ROOM

MEMBERS|user1,user2
USER_JOINED|username
USER_LEFT|username

CHAT_ROOM|message
CHAT_ROOM|sender|message

CHAT_PRIVATE|receiver|message
CHAT_PRIVATE|sender|receiver|message
```

---

## 🎤 Audio Protocol

Audio sử dụng UDP.

Packet cơ bản:

```text
┌──────────────┬──────────────┬───────────────────┐
│ Room ID Size │   Room ID    │     PCM Audio     │
└──────────────┴──────────────┴───────────────────┘
```

Audio format hiện tại:

```text
Sample Rate : 16000 Hz
Sample Size : 16 bit
Channels    : Mono
Encoding    : PCM
```

Server nhận packet và relay audio tới các client khác thuộc cùng Room ID.

---

## 🔄 Luồng hoạt động

```text
Login
  │
  ▼
Home
  │
  ├──── Create Room ────┐
  │                     │
  └──── Join Room ──────┤
                        ▼
                     Meeting
                        │
          ┌─────────────┼─────────────┐
          │             │             │
          ▼             ▼             ▼
        Audio       Participants     Chat
          │                           │
      UDP :6000                 TCP :5000
                        │
                        ▼
                    Leave Room
                        │
                        ▼
                       Home
```

---

## ⚠️ Hạn chế hiện tại

Phiên bản hiện tại vẫn đang trong quá trình phát triển:

- Chưa hỗ trợ Video Call / Camera.
- Chat hiện chỉ lưu trong phiên đang chạy.
- Chưa có database lưu tài khoản, phòng hoặc tin nhắn.
- Chưa có xác thực tài khoản bằng mật khẩu.
- UDP audio chưa có cơ chế mã hóa.
- Chưa triển khai cơ chế bảo mật cho Room ID.
- Chưa tối ưu cho số lượng lớn người tham gia.
- Chưa triển khai server public để sử dụng qua Internet.

---

## 🔮 Hướng phát triển

Các chức năng dự kiến:

- [x] Login
- [x] Create Room
- [x] Join Room
- [x] Leave Room
- [x] Realtime Participants
- [x] UDP Audio
- [x] Mute / Unmute
- [x] Room Chat
- [x] Private Chat
- [ ] Camera / Video Call
- [ ] Bật / tắt Camera
- [ ] Screen Sharing
- [ ] Authentication
- [ ] Database
- [ ] Host / Moderator
- [ ] Kick thành viên
- [ ] Mã hóa dữ liệu
- [ ] Deploy Server lên Internet

---

## 👨‍💻 Tác giả

**Trần Lê Quốc Anh**

Sinh viên Trường Đại học Công nghệ Thông tin và Truyền thông Việt – Hàn.

---

## 📝 Ghi chú

Project được xây dựng phục vụ mục đích **học tập và nghiên cứu lập trình mạng**, tập trung vào việc áp dụng TCP/UDP Socket, Client–Server, Multithreading và JavaFX vào một ứng dụng thực tế.
