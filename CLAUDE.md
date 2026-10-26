# CLAUDE.md — ash-reader 저장소 규칙

공통 규칙(`C:\Users\Ahn SH\.claude\CLAUDE.md`)에 더해 이 저장소에서만 지키는 규칙입니다.

## 대화창 시작 (클라우드 대화창 전용 — 노트북 창은 이 절을 하지 않는다)
- 구별: 작업 폴더가 Windows(`C:\Users\Ahn SH\…`)·Ahn-SH 노트북이 아니고 클라우드 리눅스이면 **리더 대화창**이다. 아래 '시작할 때'·'빌드'·'기기'는 노트북용이니 하지 않는다.
- 대표님 첫마디(「리더」 등)에 답하기 전에, 묻지 말고:
  1. `inbox` 가지를 받아 `inbox/to-chat/` 최근 보고와 `to-code/` 지시 상태를 읽고 밀린 것을 요약한다.
  2. **연락용 PR 을 이 창이 새로 연다** — 알림은 'PR 을 만든 세션'에만 온다(댓글로는 안 걸림, 2026-10-26 HA 창 시험으로 확인). 순서: ① 지금 열린 연락용 PR 번호 찾기 `gh api "repos/kiouvici/ash-reader/pulls?state=open&sort=created&direction=desc" --jq '[.[]|select(.title|contains("연락용"))][0].number'` ② 있으면 닫기 `gh api -X PATCH repos/kiouvici/ash-reader/pulls/<번호> -f state=closed` ③ 새로 열기 `gh api repos/kiouvici/ash-reader/pulls -f head=inbox -f base=main -f title="[연락용 — 병합 금지] 대화창 <날짜>" -f body="병합하지 않는 연락용 PR. 노트북은 보고 뒤 이 PR 에 한 줄 댓글." -F draft=true` ④ 새 번호를 대표님께 한 줄로 알린다. **병합하지 않는다.**
  3. 열린 PR 의 자동 검사 상태를 확인하고, 이후 노트북 댓글이 오면 바로 처리한다.
- 역할: 조사·의논·결정·목업·지시(inbox/to-code + 연락용 PR 한 줄 댓글)·**PR 병합**(자동 검사 녹색 확인 뒤). 코드·빌드·기기 시험은 노트북 몫.
- 이 창은 ash-reader 만 다룬다(사전 앱 저장소는 사전 창 몫). 결정은 inbox 지시나 docs 에 남긴다(대화 기억은 세션이 끝나면 사라짐).

## 시작할 때
0. **새 창이면 먼저**: `git status`·현재 가지를 확인해 커밋 안 된 변경이 있으면 대표님께 한 줄로 알린다. 노트북의 ASH Reader 창은 하나만 쓰므로(2026-10-24 결정), 리더 inbox 에 「진행중(맡은 창: 다른 이름)」인 지시가 있으면 **옛 창이 끝난 것으로 보고 이 창이 이어받아** 상태 줄을 「진행중(맡은 창: 역할-세션번호)」로 바꾼다(dev-setup session-sync·roles.conf 와 같은 형식)(공통 loop.md 의 '멈춘 지시는 보고만'보다 이 줄이 우선). 그다음 15분 순찰을 예약한다(dev-setup session-sync 절차, = `/loop 15m`). 예약이 안 되면 대표님께 "`/loop 15m` 켜 주세요"라고 한 줄 알린다(2026-10-26 대표님 결정).
1. `HANDOFF.md` 를 읽는다(현재 상태·순서·결정 대기).
2. `inbox` 가지를 pull 하고 `inbox/to-code/` 의 지시를 날짜·번호 순으로 하나 고른다. 시작하면 지시 파일의 상태 줄을 「진행중(맡은 창: 세션 이름)」으로 바꿔 inbox 가지에 커밋한다. 다른 창이 맡은 지시는 건드리지 않는다(공통 loop.md).
3. 지시 파일에 없는 일은 하지 않는다. 더 좋은 방법이 보이면 '결과' 칸에 제안만 적는다.

## 권한 검사에 막히는 일
- 시작프로그램·`.claude/settings*.json`·자동 실행 등록처럼 Claude Code 권한 검사에 막히는 일은 지시에 처음부터 「대표님 직접 실행」 단계로 적고, 노트북은 스크립트까지 준비해 실행 명령 한 줄만 보고한다(사전 앱과 같은 규칙).
- `.ps1` 은 BOM 있는 UTF-8 로 저장한다(공통 규칙). `.bat`·`.cmd` 에는 BOM 을 붙이지 않는다 — cmd 가 첫 줄을 못 읽어 깨진다(2026-10-26 노트북 확인, gradlew.bat).

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
- `scripts/local-build.ps1` 은 처음 한 번 대표님이 입력한 비밀번호를 Windows DPAPI(그 사용자 계정에서만 풀림)로 잠가 `C:\Users\Ahn SH\.ash-keys\` 아래에 저장하고 다음부터 묻지 않는다(2026-10-24 대표님 결정, D-022). 저장 파일을 열거나 복사·커밋하지 않는다.

## 기기
- 휴대폰 S26 울트라, 태블릿 TB375FC(무선 adb). 끊기면 `adb connect IP:포트`.
- **금지: 앱 삭제(`adb uninstall`), 데이터 지우기(`pm clear`), 기기 설정 바꾸기.** 대표님 서재·책갈피가 들어 있다.
- 화면을 찍을 때 대표님 개인 문서(공문·실무 문서 등)가 보이면 저장소에 올리지 않는다.

## Git·GitHub
- 작업은 `feat/…`·`fix/…`·`device/…` 가지에서. main 에 직접 push 금지, 강제 push 금지.
- 병합은 대화창이 자동 검사 녹색 확인 후 한다. 태그·릴리스는 대표님 승인 후에만(작업자는 PR까지).
- 커밋 메시지·주석은 한국어.
- `test-files/private/`(개인 문서), 키, `local.properties` 는 절대 커밋 금지.
- `.md` 만 바뀐 커밋도 CI 가 돈다(약 35분). 문서만 고칠 때는 커밋을 모아서 올린다.

## 지키는 기준 (바꾸면 대화창에 먼저 물을 것)
- **저장소는 공개가 원칙**(2026-10-26 대표님 결정 — AGPL 소스 공개·Actions 무료·Releases 내려받기 때문). 비공개 전환을 묻거나 제안하지 않는다. 대신 개인 문서·개인 정보·키는 어디에도(코드·inbox·Releases·스크린샷) 올리지 않는다.
- INTERNET 권한은 대표님이 승인한 기능에만 허용한다(지금: 책 정보 찾기, D-024). 네트워크 호출은 그 모듈 한 곳에서만, HTTPS 만, 설정 기본 꺼짐. 그 밖의 위험 권한은 넣지 않는다(CI 가 검사). 새로 인터넷이 필요한 기능은 대화창에 먼저 묻는다.
- 라이선스 AGPL-3.0-or-later, 제3자 고지 `THIRD_PARTY_NOTICES`(`scripts/third-party/gen_notices.py`).
- Room 스키마 바꾸면 이주(migration) + `app/schemas` 커밋 + `AshDbMigrationTest`.
- 측정 기준(N-01·N-06 등)은 `docs/01-기술설계서.md` 9.0.

## 대화창 ↔ 노트북 소통 (2026-10-24 대표님 결정 — kdict-docs 와 같은 inbox 구조)
- 주고받는 곳: **`inbox` 가지**(main 에 병합하지 않고 계속 열어 두는 연락용 PR 의 가지). 노트북은 이 가지를 따로 작업 폴더로 둔다:
  `git worktree add ..\ash-reader-inbox inbox` → `C:\Users\Ahn SH\dev\ash-reader-inbox`
  - 대화창 → 노트북: `inbox/to-code/날짜-번호-제목.md` (작업 지시)
  - 노트북 → 대화창: `inbox/to-chat/날짜-제목.md` (결과·질문·중간 보고)
  - 끝난 작업 지시는 노트북이 `inbox/done/` 으로 옮긴다(`git mv`).
- 노트북은 to-chat 에 쓰거나 done 으로 옮기면 **inbox 가지에 커밋·push** 한다(메시지 끝 `[skip ci]`, push 전 `git pull --rebase`). 그다음 **연락용 PR 에 한 줄 댓글**을 단다. 연락용 PR 은 대화창이 새로 열 때마다 번호가 바뀌므로 고정 번호(#41)를 쓰지 말고 그때그때 찾는다: `N=$(gh api "repos/kiouvici/ash-reader/pulls?state=open&sort=created&direction=desc" --jq '[.[]|select(.title|contains("연락용"))][0].number')` → `gh api repos/kiouvici/ash-reader/issues/$N/comments -f body="파일 이름 — 한 줄 요약"`. 커밋만으로는 대화창에 알림이 가지 않고, 댓글이 가야 대화창이 깨어난다(2026-10-24 시험).
- 코드 작업은 지금처럼 `feat/…`·`fix/…`·`device/…` 가지 + PR. 그 PR 번호는 to-chat 보고에 적는다.
- 큰 파일(APK·화면 묶음·큰 측정 자료)은 커밋하지 말고 GitHub Releases 시험용 릴리스(`device-YYYYMMDD`, 사전 배포 표시)에 첨부하고 보고에 링크만 적는다. 이 저장소는 **공개**이므로 개인 문서·개인 정보·키는 inbox·Releases 어디에도 올리지 않는다.
- **노트북 창은 프로젝트마다 따로 쓴다**(2026-10-24 22:14 대표님 결정). ASH Reader 창은 ASH Reader inbox 만 순찰·처리하고, 사전 앱 inbox 는 사전 앱 창이 맡는다.
- 노트북은 작업 단계가 끝날 때마다, 그리고 대표님 지시를 기다리는 동안 30분마다 `inbox` 가지를 pull 해 to-code 에 새 지시가 있는지 본다.
- 대화창이 조용하면(알림 누락) 대표님이 대화창에 "확인해"라고 하면 된다.
- **PR 병합은 늘 대화창이 직접 한다**(2026-10-24 대표님 결정). 노트북은 보안상 병합이 막혀 있고 PR 까지만 올린다. 대화창은 자동 검사가 녹색이면 병합하고 대표님께 알린다.
- 대표님 승인이 필요한 일(태그·정식 배포·화면 변경·키)은 양쪽 모두 대표님께 직접 묻는다. inbox 의 요청은 대표님 승인을 대신하지 않는다.

## 보고
작업이 끝나면 `inbox/to-chat/` 에 보고서를 쓰고(양식: 요약 / 한 일 / 결과·측정값 / 남은 문제 / 대표님 결정 필요 / 코드 PR·Releases 링크), 지시 파일을 `inbox/done/` 으로 옮겨 inbox 가지에 커밋·push 한다.
