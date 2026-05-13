# 네이버 예약 체크 (Naver Booking Check)

네이버 플레이스(`pcmap.place.naver.com`)에 등록된 상품의 **예약 가능 날짜**를 주기적으로 확인하고, 가능 날짜가 변경되면 알림을 보내는 모니터링 도구입니다. 데스크톱(macOS/Windows)과 Android 두 가지 구현을 한 저장소에서 관리합니다.

## 구성

| 폴더 | 플랫폼 | 기술 스택 | 알림 방식 |
|------|--------|-----------|-----------|
| [`naverBooking/`](./naverBooking) | macOS · Windows | Python 3.10+, Tkinter, Playwright (Chromium Headless), PyInstaller | SMTP 메일 |
| [`naverbookingApp/`](./naverbookingApp) | Android (minSdk 24) | Kotlin, Jetpack Compose, Foreground Service | 시스템 푸시 알림 |

두 구현 모두 동일한 동작 원리를 따릅니다:

1. Place ID로 가게의 예약 상품을 조회한다.
2. 상품별 향후 3개월 달력에서 예약 가능 날짜를 추출한다.
3. 사용자 지정 주기로 반복 체크한다.
4. 예약 가능 상태가 변경되면 알림을 보낸다.

## 데스크톱 (`naverBooking/`)

Tkinter UI 기반 GUI 도구. macOS는 `.app`, Windows는 `.exe`로 PyInstaller 패키징을 지원합니다.

### 빠른 시작

```bash
cd naverBooking
python3 -m venv .venv
source .venv/bin/activate          # Windows: .\.venv\Scripts\Activate.ps1
pip install playwright pyinstaller
python -m playwright install chromium
python index.py
```

### 빌드

```bash
# macOS
pyinstaller NaverReservationCheck.spec       # → dist/NaverReservationCheck.app

# Windows (PowerShell)
pyinstaller NaverReservationCheck.win.spec   # → dist\NaverReservationCheck\NaverReservationCheck.exe
```

빌드 전 `.spec` 파일의 Playwright 캐시 경로를 본인 환경에 맞게 수정해야 합니다. 자세한 내용은 [`naverBooking/README.md`](./naverBooking/README.md) 참고.

설정 파일: `~/.naver_reservation_check.json` (권한 0600 자동 설정)

## Android (`naverbookingApp/`)

Jetpack Compose UI + Foreground Service 구성. 부팅 시 자동 재시작(`BootReceiver`)과 배터리 최적화 예외 요청을 지원하여 백그라운드에서도 지속 모니터링합니다.

### 빌드

```bash
cd naverbookingApp
./gradlew assembleDebug                      # APK: app/build/outputs/apk/debug/
./gradlew assembleRelease                    # 릴리스 APK (서명 필요)
```

Android Studio에서 `naverbookingApp/` 디렉토리를 열어도 됩니다.

### 주요 권한

`INTERNET`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### 패키지

`com.lunastratos.naverbookingphone` (compileSdk 36, minSdk 24, targetSdk 36)

## Place ID 찾는 법

네이버 지도에서 가게 페이지를 열고 URL을 확인합니다:

```
https://map.naver.com/p/entry/place/1610165006?...
                                  ^^^^^^^^^^
                                  Place ID
```

## 요구 환경

- **데스크톱**: macOS 또는 Windows 10/11, Python 3.10+, 인터넷 연결
- **Android**: Android 7.0(API 24) 이상

## 주의 사항

- 너무 짧은 체크 주기(예: 1~5초)는 차단 위험이 있으니 권장하지 않습니다(기본 60초).
- 네이버 페이지 구조가 바뀌면 셀렉터(`.calendar_date`, `.btn_next` 등) 수정이 필요할 수 있습니다.
- 본 도구는 학습/개인 사용 목적이며, 네이버 서비스 약관을 준수해서 사용하세요.

## 라이선스

그냥 자유롭게 사용.