# 실험 ⑤ 만화 칸 인식·AI 화질 향상 — 진행 상태 (2026-10-22)

## 상태 요약
- **JVM 단위 시험 통과** (`:engine-comic:testDebugUnitTest`, CI에서도 통과): 칸 인식 합성 20쪽(왼→오 10, 오→왼 10, 그중 절반은 잡음·누런 바탕) **인식률 100%(112/112칸)**, 쪽당 최대 약 0.1초(개발 PC JVM, 처리 해상도 긴 변 1200px).
- **APK 빌드 실패 — 멈춤**: ncnn 20261020 안드로이드 Vulkan 프리빌드의 `libglslang.a`가 clang 21(NDK r30 계열) libc++로 빌드돼 있다. NDK r28·r29로 링크하면 `std::__ndk1::__hash_memory` 정의가 없다고 나온다(2회). r30을 CI에서 설치하려던 3번째 시도는 설치 단계에서 실패했다(로그 열람 불가). 같은 원인으로 3번 실패해 지시대로 멈춘다.
- 시험 만화는 **대표님 제공 예정(CBZ·CBR, 왼→오/오→왼/컬러)**. 정답 상자 JSON 형식은 아래에 정했다.

## 만든 것 (`:engine-comic`)
| 부분 | 내용 |
|---|---|
| CBZ | `java.util.zip`, 파일명 UTF-8 엄격 → 실패 시 CP949 |
| CBR | libarchive 3.8.10 정적(RAR4·RAR5, 외부 압축 라이브러리 끔), JNI: 목록·한 항목 읽기·전체 순차 풀기(solid 캐시, 500MB 상한, 진행 콜백). 암호 항목 → `EncryptedArchiveException`(설계서 안내 문구) |
| 형식 판별 | 시그니처(ZIP/RAR4/RAR5), solid 판별: RAR4 MAIN_HEAD 0x0008, RAR5 main header archive flags 0x0004 |
| 방향 | ComicInfo.xml `<Manga>YesAndRightToLeft` → 오→왼 (F-49) |
| 쪽 | 이미지 확장자만, `__MACOSX`·숨김 제외, 자연 정렬(1, 2, 10) |
| 큰 이미지 | 한 변 4096px 초과 → `BitmapRegionDecoder` 타일(`PageDecoder.tiles/decodeRegion`) |
| 칸 인식 | Kumiko(AGPL-3.0) 처리 순서를 **순수 Kotlin**으로 옮김: Sobel → 임계 100 → 바깥 성분 사각형 → 작은 조각 묶기 → 거터로 나누기 → 작은 칸 제외 → 포함 병합 → 겹침 해소 → 확장 → 읽기 순서(ltr/rtl, `fix_panels_numbering`). 다른 점: OpenCV LSD 선분 분할 대신 '양옆에 칸 테두리가 있는 빈 띠(폭 6% 이하)' 투영으로 나눔, 3×3 흐림 추가 |
| 채점 | `PanelScoring`: IoU ≥ 0.7로 탐욕 짝짓기, 짝지은 칸 사이 순위가 정답과 다르면 틀림, 인식률 = 맞은 칸 ÷ 정답 칸 |
| 화질 향상 | ncnn + Real-CUGAN 2x(se conservative / nose) JNI. realcugan-ncnn-vulkan `process_cpu` 흐름(타일 200, prepadding 18, in0→out0). Vulkan 있으면 GPU, 아니면 CPU. `Upscaler.upscale()`이 쪽당 처리 시간 반환, `vulkanInfo()`가 장치·API 버전 |
| 측정 앱 ⑤ | 시험 파일 열기·solid 풀기 시간, 쪽마다 칸 인식 시간(디코딩 제외, N-10), `<책>.panels.json` 있으면 인식률, 800×1200 화질 향상 GPU·CPU 각 3회, Vulkan 정보 → CSV |
| CI | 단위 시험 추가, `libash_comic.so` 크기·APK 안 압축 크기·16KB LOAD 정렬(64비트 필수)·모델 assets 크기 요약 단계 |

서드파티는 저장소에 넣지 않고 `engine-comic/scripts/fetch-third-party.sh`가 받아 SHA-256을 확인한다:
libarchive-3.8.10.tar.xz `56cad765…fb96`, ncnn-20261020-android-vulkan.zip `6e0c5f9b…1086`,
Real-CUGAN(nihui/realcugan-ncnn-vulkan 395302c, MIT) se-up2x-conservative.bin 2,573,648B `91c72c13…84a0`, nose-up2x-no-denoise.bin 2,551,408B `2b6f4db8…9b84`.

## 정답 칸 상자 JSON 형식 (QA용)
```json
{"book": "책 이름", "pages": [
  {"page": "압축 안 쪽 파일 이름", "direction": "ltr|rtl", "size": [원본폭, 원본높이], "panels": [[x, y, w, h], ...]}
]}
```
`panels`는 읽기 순서대로. 측정 앱은 시험 폴더의 `<책 파일 이름>.panels.json`을 자동으로 찾는다. 예: `engine-comic/src/test/resources/panels-example.json`.

## 남은 것
1. **ncnn 링크 문제 해결 (다음 첫 단계, PM 결정 필요)** — 선택지:
   ① NDK r30을 CI에 설치(정확한 패키지 이름을 확인해야 함, 설치 단계 로그 필요)
   ② ncnn을 태그 20261020 소스에서 우리 NDK r28로 직접 빌드(Vulkan 켬, 빌드 시간 +10~15분, 캐시로 줄임)
   ③ 우선 CPU 전용(ncnn 프리빌드 android 비-Vulkan판)으로 측정하고 GPU는 뒤로
   추천: ②(툴체인을 우리가 고정할 수 있고 16KB 정렬도 확실).
2. 빌드가 통과하면 .so 크기·16KB 정렬 보고(CI 요약에 자동 표시).
3. 대표님 샘플 도착 후: 정답 상자 작성(QA), 실제 쪽 인식률·쪽당 시간 측정. 합성 100%는 실제 만화(테두리 없는 칸, 칸을 넘는 그림)에서 크게 낮아질 수 있음.
4. 기준 휴대폰·태블릿에서 측정 앱 ⑤ 실행(칸 인식 1초, 화질 향상 5초, 메모리·발열).
