# CLAUDE.md — ash-reader 저장소 규칙

공통 규칙(`C:\Users\Ahn SH\.claude\CLAUDE.md`)에 더해 이 저장소에서만 지키는 규칙입니다.

## 시작할 때
1. `HANDOFF.md` 를 읽는다(현재 상태·순서·결정 대기).
2. `docs/handoff/` 에서 상태가 '대기'인 파일을 위에서부터 하나 고른다. 시작하면 상태를 '진행'으로 바꾸고 커밋한다.
3. 지시 파일에 없는 일은 하지 않는다. 더 좋은 방법이 보이면 '결과' 칸에 제안만 적는다.

## 대표님과 말할 때
- 한국어 존댓말. 설명은 짧게, 무엇을 어떻게 고쳤는지 위주.
- 절차: 요약 → 보고 → 질의 → 진행. 화면(디자인)이 바뀌는 일은 직접 하지 말고 대화창으로 돌린다(목업 → 통과 → 감수 → 빌드).

## 빌드
- **저장소의 `gradlew.bat` 를 쓴다**(Gradle 9.6 wrapper). 노트북 전역 gradle 8.10.2 로 빌드하지 않는다.
- JDK 17, Android SDK: build-tools 36.0.0, **NDK 28.2.13676358**, **CMake 3.22.1**.
- Rust stable + `cargo-ndk 4.1.2`, 대상 `aarch64-linux-android x86_64-linux-android armv7-linux-androideabi i686-linux-android`.
- 네이티브 엔진 빌드에 make·unzip·pkg-config 가 필요하다(Windows 는 MSYS2 `C:\msys64\usr\bin` 을 PATH 에).
- 첫 빌드는 MuPDF·ncnn·DjVuLibre·libarchive 를 소스부터 빌드해 오래 걸린다. 패치를 바꿨으면 `engine-mupdf/build/mupdf-src` 를 지우고 다시 빌드.
- 폰 설치는 항상 release 빌드(`:app:assembleRelease`) + 정식 키 서명. `adb install -r` 로 덮어 설치.

## 버전 번호
- CI: versionCode = 1000 + 실행 번호, versionName = 1.0.0-rc.<실행 번호>.
- 노트북: 그냥 빌드하면 versionCode 1000(폰에 깔린 CI 판보다 낮아 덮어 설치 실패). 노트북 빌드는 **최근 GitHub 빌드 번호를 기준으로 '-local'** 을 붙인다(작업 1에서 규칙을 만든다).
- 정식 배포: `-Pash.versionName=1.0.0`.

## 서명 키
- 정식 키: `C:\Users\Ahn SH\.ash-keys\`. 사전 앱 키(`.kdict-keys`)와 다르다.
- 키 파일을 열어 보거나 복사·이동·커밋하지 않는다. 경로만 환경변수로 넘긴다(`ASH_KEYSTORE_FILE` 등, `AshBuild.kt`).
- 비밀번호는 대표님이 직접 입력한다. 화면·로그·파일에 비밀번호를 쓰지 않는다.

## 기기
- 휴대폰 S26 울트라, 태블릿 TB375FC(무선 adb). 끊기면 `adb connect IP:포트`.
- **금지: 앱 삭제(`adb uninstall`), 데이터 지우기(`pm clear`), 기기 설정 바꾸기.** 대표님 서재·책갈피가 들어 있다.
- 화면을 찍을 때 대표님 개인 문서(공문·실무 문서 등)가 보이면 저장소에 올리지 않는다.

## Git·GitHub
- 작업은 `feat/…`·`fix/…`·`device/…` 가지에서. main 에 직접 push 금지, 강제 push 금지.
- 병합·태그·릴리스는 대표님 승인 후에만(작업자는 PR까지).
- 커밋 메시지·주석은 한국어.
- `test-files/private/`(개인 문서), 키, `local.properties` 는 절대 커밋 금지.
- `.md` 만 바뀐 커밋도 CI 가 돈다(약 35분). 문서만 고칠 때는 커밋을 모아서 올린다.

## 지키는 기준 (바꾸면 대화창에 먼저 물을 것)
- 앱에 INTERNET 권한 넣지 않는다(CI 가 검사).
- 라이선스 AGPL-3.0-or-later, 제3자 고지 `THIRD_PARTY_NOTICES`(`scripts/third-party/gen_notices.py`).
- Room 스키마 바꾸면 이주(migration) + `app/schemas` 커밋 + `AshDbMigrationTest`.
- 측정 기준(N-01·N-06 등)은 `docs/01-기술설계서.md` 9.0.

## 보고
작업이 끝나면 PR 을 올리고, 지시 파일의 '결과' 칸에 아래 양식으로 적어 같은 PR 에 넣는다. 상태를 '완료'(또는 '막힘')로 바꾼다.
