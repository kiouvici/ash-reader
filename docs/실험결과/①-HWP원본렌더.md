# 실험 ① HWP/HWPX 원본 렌더 (spike/hwp-render)

- 엔진: rhwp v0.8.7(커밋 1a76570, MIT) → 쪽 SVG → resvg 0.45(MIT/Apache-2.0) → 안드로이드 Bitmap. `:engine-hwp`(cargo-ndk, JNI)
- API(:bench용): `HwpDoc.installFonts(dirs)`, `HwpDoc.open(path)`, `pageCount()`, `renderPage(i, widthPx)`, `pageTextChars(i)`, `close()`; 판정 `HwpHealth.firstPageFailed / looksTruncated / inkRatio`
- 공개 시험 파일: `test-files/public/hwp/`(법령·고시, 출처.md)

## 데스크톱 결과 (x86_64, 같은 경로)

| 파일 | 기준(한컴 PDF) 쪽 | rhwp 쪽 | 판정 |
|---|---|---|---|
| 실무 HWPX(비공개) | 15 | 14 | ±1 합격(한컴은 1쪽 표를 2쪽으로 넘김, 이후 1쪽씩 밀림, 내용 육안 일치) |
| 실무 HWP(비공개) | 9 | 9 | 합격(글자 유사도 0.98) |
| 행정업무운영 편람 HWPX 13.6MB(비공개) | 383 | 383 | 합격(일부 쪽 그림 위치 차이) |
| gov-02 법령 HWPX | 27 | 27 | 합격 |
| gov-03 한글 맞춤법 HWP | – | 40 | 한자 334자 표시 |
| gov-04 인명용한자 HWP | – | 4 | 한자·이체자 표시 육안 정상(기기 CJK 글꼴 대체) |
| 사업안내 HWPX 16MB(비공개) | – | 450 | 열기 1.07초(x86) |

- 쪽 래스터(110dpi) 중앙값 0.27~0.38초, 최대 0.76초(x86 데스크톱).
- F-28 첫 쪽 빈 쪽 판정: 7개 파일 오전환 0. 손상 파일(쓰레기·빈 파일·앞부분만 남은 파일)은 예외로 거부.
- 잘린 파일: 앞 95%만 남은 HWP가 오류 없이 열리는 경우가 있어 `looksTruncated`(뒤쪽 빈 쪽 2쪽 이상 연속) 추가.

## 에뮬레이터(API 26) 결과
CI `Spike engine-hwp` Step Summary: `.so` 크기·16KB 정렬 표, 쪽 수·열기·첫 쪽 렌더 시간, 앞 3쪽 기준 이미지 불일치율(합격 ≤ 3%).

## 남은 일
- 기준 기기 측정(첫 쪽 5초, N-01), .so 크기 기준 APK 증가량 확정
- 편람·사업안내 공공누리 유형 확인 후 공개 여부 결정

## CI 결과 (2026-10-22)
- cargo-ndk 빌드 통과. `.so` 크기(release, libash_hwp.so): arm64-v8a 10.9MB(gzip 4.7MB), x86_64 12.5MB, armeabi-v7a 7.6MB, x86 13.0MB → 배포 APK(arm64 하나)는 약 4.7MB 증가(설계서 예상 5~10MB 이내).
- 16KB 정렬: arm64-v8a·x86_64 통과. 32비트는 경고만 낸다.
- 에뮬레이터 시험: 2코어 러너에서 에뮬레이터가 부팅하지 못한다(pagecurl-smoke와 같은 증상). continue-on-error로 두고 측정 앱(PR #5) ① 버튼으로 실기기에서 판정한다.
