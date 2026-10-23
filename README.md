# ASH Reader

이북 전 형식 + HWP/HWPX + Word를 오프라인으로 읽는 안드로이드 이북 리더 (휴대폰·태블릿).

- 요구사항: [docs/00-요구사항서.md](docs/00-요구사항서.md)
- 결정 기록: [docs/결정기록.md](docs/결정기록.md)
- 이슈: [docs/이슈목록.md](docs/이슈목록.md)

## 라이선스

ASH Reader는 [GNU Affero General Public License v3.0 이상](LICENSE)(`AGPL-3.0-or-later`)으로 배포합니다.
GitHub Actions의 각 빌드 artifact에는 APK와 같은 커밋의 소스(`*-source.zip`)가 함께 들어 있습니다.
빌드에 쓴 서드파티 소스(MuPDF·DjVuLibre·libarchive·ncnn·libmobi·libmspack·rhwp와 Rust 크레이트, ASH 패치 적용본)는
같은 실행의 `third-party-sources-<실행 번호>` artifact(`*-third-party-sources.tar.gz`)로 따로 올립니다.
APK를 법인 밖으로 줄 때는 이 두 묶음을 함께 제공합니다(GPL §3·AGPL §6, D-001).

## 지원 기기

- Android 8.0(API 26) 이상, **64비트(arm64-v8a) 기기 전용**입니다. 32비트 전용 기기에는 설치되지 않습니다(D-020).
  x86_64는 에뮬레이터 시험용으로만 함께 들어 있습니다.
