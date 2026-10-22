# 실험 ⑤ 만화 칸 인식·AI 화질 향상 — 중단 시점 상태 (2026-10-22 14:20 KST)

PM 지시로 중단. 코드는 아직 이 브랜치에 들어가지 않았고, 조사·재료 확보까지 끝난 상태다.

## 끝난 것
- 문서 검토: 요구사항 F-48~F-60·F-72·F-73, N-02/N-09/N-10, 기술설계 K-7·K-9·6장·9장 ⑤.
- Kumiko(AGPL-3.0, 마지막 커밋 2024-12-22) 원본 `lib/page.py·panel.py·segment.py` 분석 완료.
  단계: 회색조 → Sobel(x,y 0.5:0.5) → 임계 100 → findContours(EXTERNAL) → approxPolyDP(0.001·둘레) →
  LSD 선분(최소 길이 = 짧은 변×0.1, 500개 이하가 되게 ×1.1) → 작은 칸 묶기 → 분할(선분 겹침 50% 초과) →
  작은 칸 제외 → 병합(50% 포함) → 겹침 해소 → 확장(거터 기준) → 큰 칸 묶기 → 순서 보정(ltr/rtl).
  → 이식은 **순수 Kotlin**을 권장(JVM 단위 테스트로 인식률 채점 가능, OpenCV .so 불필요). Sobel·윤곽·다각형 근사·LSD 대체(수평/수직 투영)만 구현하면 됨.
- Real-CUGAN: nihui/realcugan-ncnn-vulkan(MIT, 2022-07-28 커밋 395302c) 확보·분석. 2x 입력 처리 = 타일, prepadding 18,
  in0/out0, 0~1 정규화, nose 모델은 syncgap 0. 모델 SHA-256:
  - models-se/up2x-conservative.bin 2,573,648B `91c72c13…84a0`, .param `4b3b5c57…9708`
  - models-nose/up2x-no-denoise.bin 2,551,408B `2b6f4db8…84a0`... (전체 값은 다음 작업에서 표로 기록)
- ncnn 20261020 android-vulkan 프리빌드(40.7MB zip, sha256 6e0c5f9b…1086): 정적 libncnn.a(arm64 11.5MB)+glslang, OpenMP·SIMPLEVK(Vulkan 로더 동적).
- libarchive 3.8.10(sha256 56cad765…fb96): 압축 라이브러리 전부 끄고 정적 빌드 성공(호스트 1.9MB .a). RAR4 시험 파일은 libarchive 저장소 test/*.rar.uu(solid·암호 포함)를 쓰면 됨(rar 7은 RAR5만 생성).

## 막힌 것
- 시험 만화 이미지: 이 환경에서 upload.wikimedia.org·digitalcomicmuseum·archive.org 직접 다운로드 차단.
  후보(미국 PD, Wikimedia Commons): Little Nemo 1906 일요판(1,760×2,330 전후, 왼→오), Krazy Kat 1916(왼→오),
  Tokyo Puck 1909·北澤楽天 Tagosaku(오→왼 후보, 칸 구조 확인 필요). 컬러 = Little Nemo.
- 임시 브랜치 `tmp/comic-fetch`(CI로 Commons에서 받아오는 워크플로)는 실패 상태로 남아 있음. 삭제는 권한상 못 함 — 필요 없으면 대표님이 지워 주세요.

## 남은 것
1. `:engine-comic` 모듈(CBZ java.util.zip + CBR libarchive JNI, solid 판별 헤더 플래그, ComicInfo.xml Manga=YesAndRightToLeft, 4096px 초과 BitmapRegionDecoder 타일).
2. Kotlin Kumiko 이식 + 정답 상자 JSON 형식 + IoU≥0.7 채점기 JVM 테스트.
3. ncnn+Real-CUGAN JNI(Vulkan 있으면 GPU, 없으면 CPU), 쪽당 시간 API.
4. 시험 파일 3권(CBZ·CBR RAR5 solid·비solid) + docs/시험파일목록.md 출처.
5. :bench ⑤ 버튼, CSV, .so 크기·16KB 정렬 CI 단계.

## 다음에 할 첫 단계
시험 이미지 확보 방법을 대표님께 결정받기(① 대표님 PC에서 Commons 원본 10장 내려받아 test-files/public/comic 에 올리기 ② 허용 도메인에 upload.wikimedia.org 추가). 그동안 합성 쪽 이미지로 Kotlin 칸 인식기·채점기부터 작성.
