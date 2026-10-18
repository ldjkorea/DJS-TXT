# DJS TXT

광고 없는 TXT 리더의 웹/PWA와 Android 앱입니다. 현재 공개 버전은 **2.2.0 / code 220**입니다. 2026-10-19 GitHub `main`과 GitHub Pages에 배포하고 공개 APK의 앱 내 다운로드·시스템 설치·데이터 보존을 검증했습니다.

- 웹: https://ldjkorea.github.io/DJS-TXT/
- 공개 APK: https://raw.githubusercontent.com/ldjkorea/DJS-TXT/main/DJS_TXT_v2.2.0.apk?v=220.1
- 버전/해시: https://raw.githubusercontent.com/ldjkorea/DJS-TXT/main/version.json

## 소스와 데이터

- 실제 작업 폴더: `C:\Users\c\Desktop\AI\ANTIGRAVITY\projects\txtreader`.
- `C:\Users\c\.gemini\antigravity\scratch`는 위 `projects`로 연결되는 정션입니다. 두 경로는 같은 소스를 가리킵니다.
- 웹 원본은 루트의 `index.html`, `css`, `js`, `fonts`, `sw.js`, `manifest.json`입니다. `android/assets`는 같은 웹 원본의 동기화 사본입니다. APK 빌드는 루트 웹 원본을 별도 빌드 폴더로 복사합니다.
- 본문 저장소 `NovelReaderDB` v1 / `books`, 메타데이터 `nr-books`, 설정 `nr-cfg`, 마지막 책 `nr-last`를 유지합니다.
- 구형 책 ID, 숫자 픽셀 위치, `{pIdx,offset,ratio}` 위치와 기존 북마크를 계속 읽습니다. 새 위치는 문단·문자 오프셋·렌더 창 시작점도 함께 보관합니다.
- Android는 기존 `https://ldjkorea.github.io/DJS-TXT/` origin에서 내장 웹 자산을 읽습니다. 연결 상태에 따라 저장소를 바꾸지 않습니다. 구형 `file://` 서재는 읽어서 병합하고 원본을 삭제하지 않습니다.
- 구형 서재 병합은 다음 시작에서 실제 본문·메타데이터·북마크를 재확인한 뒤 완료합니다. 사용자가 직접 삭제한 기록을 이후 병합으로 되살리지 않습니다.

## 2.2.0의 수정

- 백업 v3에는 모든 책 본문·설정·읽던 위치·북마크를 포함합니다. 기존 v2 메타데이터 백업도 받을 수 있지만 본문이 없다는 사실을 표시합니다.
- 복원은 형식·ID·본문 지문을 검증하고, 충돌하는 본문을 덮어쓰지 않습니다. 더 최근 기록과 기존/가져온 북마크를 병합합니다. 메타데이터 저장 실패 시 본문 트랜잭션을 취소합니다.
- 화면 주변 최대 180개 문단만 렌더링하며 세로/가로 모두 같은 문단·문자 앵커를 사용합니다. 검색·목차·북마크·글꼴 변경·크기 변경·재열기를 같은 위치 모델로 처리합니다.
- Android 시스템 파일 선택과 SAF 백업 저장을 연결했습니다. 외부 파일은 제목·본문을 하나의 큐 항목으로 받습니다.
- 리디바탕과 Pretendard를 라이선스와 함께 번들합니다. 외부 폰트 CDN에 의존하지 않습니다.
- PWA POST 공유 수신, 설정 검증, 키보드 스텝 버튼, 시트 접근성, 화면 켜짐 유지 복귀, 설정 중 볼륨키 처리를 보완했습니다.
- 업데이트 URL·크기·SHA-256·패키지·버전·서명을 검증하며 설치 권한 복귀를 처리합니다. 자동 서명키 생성과 평문 비밀번호 하드코딩을 제거했습니다.
- Windows ZIP의 역슬래시 자산 이름을 정규화해 실제 Android에서 하위 폴더 자산을 읽게 했습니다.
- 공개 배포 전환 시 기존 HTTP 캐시와 새 HTML이 섞이지 않도록 CSS/JS 주소에 버전을 붙이고 서비스 워커 설치 캐시를 새로 받습니다. APK 다운로드 주소에도 이번 전달 버전을 붙였습니다.

## 로컬 웹 실행과 검사

```powershell
python -m http.server 18792 --bind 127.0.0.1
node --test tests/data.test.cjs
node --check js/app.js
node --check js/reader.js
node --check js/data.js
node --check sw.js
git diff --check
```

서버를 연 뒤 `http://127.0.0.1:18792/`로 접속합니다. `http://localhost:18792/`는 별도 origin이므로 새 저장소 복원 시험에 사용할 수 있습니다. 실제 사용자 브라우저 저장소를 지우는 방식으로 시험하지 않습니다. `tests/share.html`은 서비스 워커가 활성화된 뒤 합성 TXT의 POST 공유 수신을 검증하는 페이지입니다.

## Android 빌드

기존 `android/release.keystore`가 필요합니다. 없으면 중단하며 새 키를 생성하지 않습니다. 저장소에 비밀번호를 넣지 말고 현재 셸의 `DJS_TXT_STORE_PASSWORD` 환경 변수로 공급합니다. 키 비밀번호가 다른 경우 `DJS_TXT_KEY_PASSWORD`도 공급합니다. 환경 변수 값은 로그에 출력하지 않습니다.

```powershell
.\android\build_apk.ps1 -SdkRoot '<Android SDK>' -JdkHome '<JDK 21>'
```

Android SDK의 `platforms/android-34`와 `build-tools/35.0.0`을 사용합니다. 출력은 매번 새 `android/.qa/builds/<시각>` 폴더에 생성하며 기존 결과·키·데이터를 덮어쓰지 않습니다. 모든 도구 종료코드를 확인하고, 기존 앱의 인증서 SHA-256과 일치하는 APK만 성공으로 판정합니다. `-Unsigned`는 서명 전 패키징 검사, `-TestDebuggable`은 에뮬레이터 전용 시험에 사용합니다. 실제 휴대폰 배포에는 기본 Release 빌드를 사용합니다.

새 APK의 SHA-256과 `version.json`의 `sha256`은 같아야 합니다. 공개 APK는 루트의 `DJS_TXT_v2.2.0.apk`이며, 이전 `DJS_TXT_v2.1.0.apk`도 보존했습니다. 최종 공개 APK SHA-256은 `5a3e4ddf230e075bfc12a00e5deeaa35826c94f44c0fdf8f3327e7f20ea31b2e`입니다.

## Android 보존 회귀 시험

```powershell
.\android\tests\build-tests.ps1 -SdkRoot '<Android SDK>' -JdkHome '<JDK 21>'
adb -s <전용 에뮬레이터> install -r <생성된 tests.apk>
adb -s <전용 에뮬레이터> shell am instrument -w -e phase seed com.ldjkorea.djstxt.tests/com.ldjkorea.djstxt.tests.SmokeInstrumentation
# 구버전 데이터 생성 후 같은 서명의 수정 APK를 install -r로 업데이트
adb -s <전용 에뮬레이터> shell am instrument -w -e phase verify com.ldjkorea.djstxt.tests/com.ldjkorea.djstxt.tests.SmokeInstrumentation
```

`seed`는 합성 서재를 씁니다. 실제 휴대폰에서 실행하지 않습니다. 코드에서도 실제 기기는 읽기/보호 백업용 `audit`만 허용합니다. `verify`는 최초 구형 레코드를 엄격히 검사하고, `reopen`은 정상 사용 후 변환된 문자 앵커를 검사합니다. `pixel`은 구형 숫자 위치와 숫자 북마크를 실제 스크롤 좌표로 확인합니다. `seed-file` / `legacy-merge`는 구형 오프라인 origin 병합을 검증합니다. 어느 단계도 테스트를 위해 기존 앱 저장소를 초기화하지 않습니다.

`audit`는 해당 기기의 책 본문을 포함하는 보호 스냅샷을 앱 소유 외부 폴더의 `preservation`에 만듭니다. 이를 개인 자료로 취급하며 Git이나 공개 산출물에 포함하지 않습니다. `android/.qa`는 Git에서 제외되어 있습니다. Google Drive 자동 업로드는 하지 않습니다.

## 확인한 범위

2026-10-19 수정 검증에서 구버전→수정 APK 업데이트, 원래 문단·숫자 위치·북마크 보존, 네트워크 차단 재실행, 구형 file origin 병합, 시스템 파일 선택, 웹 본문 백업/새 origin 복원, CP949 POST 공유, 약 49MiB 파일의 후반부 검색 및 최대 180개 문단 유지를 확인했습니다. 실제 휴대폰 `SM_S942N`의 기존 책은 앞선 2.2.0 후보 설치 전후 ID·본문·메타데이터·설정·마지막 책이 모두 같았습니다. 최종 공개 APK는 웹 자산 캐시 전환 보강을 반영해 다시 패키징했으며 네이티브 DEX는 앞선 후보와 같습니다.

최종 에뮬레이터에서 시스템 문서 저장기로 만든 v3 JSON에는 책 4권·본문 4개·북마크 3개가 있었습니다. 같은 JSON을 시스템 선택기로 복원한 뒤 책·본문·설정·마지막 책이 모두 일치했고 북마크가 중복되지 않았습니다. 실제 휴대폰의 기존 서재는 1권·북마크 0개였으므로 북마크 보존은 합성 에뮬레이터 기록으로 검증했습니다. 실제 휴대폰의 터치·볼륨키·장시간 독서는 완료 판정에 포함하지 않습니다.

공개 배포 검증에서는 실제 2.1.0 앱이 공개 메타데이터에서 2.2.0을 발견하고 APK를 내려받아 시스템 설치기로 업데이트했습니다. 앱이 내려받은 파일과 실제 설치된 APK의 SHA-256이 최종 공개 파일과 같았습니다. 업데이트 전후 합성 서재 4권·북마크 3개, 본문·메타데이터·설정·마지막 책이 모두 일치했습니다. 이어 10회 연속 재열기에서 문단 137·문자 오프셋 144가 유지됐습니다. 공개 웹에서도 기존 서재와 83.3% 위치를 재열고 2,501번 문단 이동 및 최대 180개 문단을 확인했습니다.

위 공개 업데이트 시험은 전용 에뮬레이터에 APK 설치 권한을 사전 허용한 조건입니다. 2.2.0에서 향후 상위 버전으로 이동할 때의 설치 권한 설정 복귀 분기, 장시간 발열·배터리·기기별 60fps는 별도 검증 대상입니다. 사용자 결정에 따라 이번 배포의 화면/설치 검증은 에뮬레이터에서 마무리했습니다.
