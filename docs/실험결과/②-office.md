# 실험 ② 결과 — DOCX/DOC 엔진 (`:engine-office`)

- 브랜치 `spike/office`, 작성 2026-10-22, 문서 엔진 개발자 2(Word)
- 범위: 변환기·DOC 추출·암호 해제·시그니처 판별의 **JVM 단위 시험**과 APK 크기 실측. MuPDF 조판(방법 ②)·기기 첫 화면 시간(9.0 규칙)·Word 출력본 육안 대조는 아직 하지 않았다(아래 '남은 일').
- CI: Build 워크플로 `:engine-office:testDebugUnitTest` + 크기 측정 job `office-apk-size`. 수치는 CI run 39666161600(ubuntu-24.04, JDK 17) 주석에서 옮김.

## 1. 만든 것

| 파일 | 내용 |
|---|---|
| `DocxConverter.kt` | DOCX → XHTML(합성 EPUB 장용). 문단·제목(outlineLvl/heading 스타일 상속)·번호/글머리(numbering.xml, 가나다·원문자 포함)·표(gridSpan→colspan, vMerge→rowspan, 중첩 표, 셀 음영)·그림(DrawingML blip, VML imagedata, EMF/WMF는 회색 상자+문구)·머리글/바닥글(first/default/even, `titlePg`)·각주(`epub:type="noteref"` + `aside epub:type="footnote"`)·런 서식(굵게·기울임·밑줄·취소선·위/아래 첨자·색·크기·형광). 문단마다 `id="p{n}"`(S-32 전환 위치용). `simplified=true`는 이북식 보기용(크기·색 제거). ZIP 해제 총량 512MB 상한(N-09) |
| `DocExtractor.kt` | DOC → XHTML. POI HWPF `Range`/`Table`로 본문·표 텍스트, 제목 스타일만 반영. 그림·서식 없음(F-31 범위) |
| `AgileDecryptor.kt` | ECMA-376 Agile 암호 해제 자체 구현(약 150줄, `javax.crypto` AES-CBC + `MessageDigest`만 사용). 틀린 암호/미지원 방식(Standard 2007, 인증서 방식) 구분. 결과 ZIP은 메모리에만 |
| `Cfb.kt` | 최소 OLE(CFB) 읽기(스트림 목록·읽기). POI 없이 시그니처 판별·암호 해제 가능 |
| `FormatSniffer.kt` | 시그니처 판별: RTF·PDF·HWP3·ZIP 내부(DOCX/EPUB/HWPX/XPS)·OLE 내부(암호 OOXML/DOC/HWP5) |

XML 해석은 설계서의 `XmlPullParser` 대신 `javax.xml` DOM을 썼다(안드로이드·JVM 모두 내장이라 JVM 시험에 추가 의존성 불필요). 대용량 DOCX에서 메모리가 문제되면 XmlPullParser로 바꾼다(구조는 같음).

## 2. 합격 기준 대비

| 기준(실험 ②) | 결과 | 판정 |
|---|---|---|
| DOCX 문단 순서·표 칸 구조·그림·머리글/바닥글 빠짐 0 | 공개 `word-structure.docx`: 문단 29개 누락 0·순서 어긋남 0(기준 PDF 텍스트 자동 대조), 4×4 병합 표 모든 행 논리 칸 4(가로·세로 3행·2×2 병합), 중첩 표 1, 그림 2/2, 머리글 2(first·default)·바닥글 2, 각주 1. 실무 `real-04.docx`(로컬): 문단 36개 누락 0·순서 0, 표·그림·머리글은 원본에 없음(기준 PDF도 그림 0) | **자동 대조 통과**. 단, 기준 PDF가 Word 출력본이 아니라 LibreOffice 출력본이고 육안 대조는 아직 안 함 |
| 10MB 이하 첫 화면 5초 이내 | 변환만 JVM 평균 23.7ms(42KB). 기기·MuPDF 조판 포함 측정은 안 함 | 미측정 |
| DOC 본문·표 텍스트 누락 0 | `word97-structure.doc`(LibreOffice로 만든 Word 97): DOCX 기준 문단 29개 중 누락 0, 표 2개 추출. 첫 호출 359ms(JVM, 클래스 로딩 포함) | **통과**(공개 파일 1종) |
| POI 추가로 APK 증가 6MB 이하 | release(R8 켬) APK **+588,821B(0.56MiB)** — `poi`+`poi-scratchpad` 5.5.1 + engine-office 전체, 진입점 4개 keep | **통과** |
| 암호 DOCX 열림 | `word-encrypted-agile.docx`(AES-256, SHA-512, spin 100000): 자체 구현 해제 결과가 원본 DOCX와 **바이트 일치**, 94ms(JVM). 틀린 암호는 `WrongPassword`. POI `EncryptionInfo`+`Decryptor.verifyPassword`도 poi 코어만으로 성공(5.5.1은 agile 클래스가 `poi` jar에 있음) | **통과** → S-42 암호 입력창 사용(M-08 대체 문구 불필요) |
| 시그니처 판별(.doc인데 RTF) | `real-03.doc`(실무, 실제 RTF) → RTF, 공개 `rtf-named-as.doc` → RTF, DOC/DOCX/암호 OOXML/PDF 구분 | **통과** |
| 손상 파일 | `word-corrupt.docx` → 예외(앱은 S-43 안내로 처리), 강제 종료 없음 | 통과(JVM) |

## 3. 안드로이드에서 POI

- 의존성: `org.apache.poi:poi` + `poi-scratchpad` 5.5.1만. 전이 의존성 commons-io·commons-codec·commons-collections4·commons-math3·log4j-api·SparseBitSet. `poi-ooxml`(xmlbeans)은 넣지 않음.
- AWT: HWPF 텍스트 경로는 `java.awt`를 실행하지 않는다. R8 경고만 `consumer-rules.pro`의 `-dontwarn`(java.awt·javax.imageio·log4j 등)으로 막음. HWPF 그림·HSLF 렌더 경로는 쓰지 말 것.
- Log4j: "could not find a logging provider" 경고만 나오고 동작 정상. 앱에서는 무시하거나 `log4j-api`의 SimpleLogger 설정 불필요.
- minSdk 26 desugaring 문제는 release 빌드(R8) 통과로 컴파일 단계까지 확인. **기기 실행 확인은 아직**(시험 APK에서 DOC 열기 필요).
- 암호 해제는 POI·자체 구현 둘 다 되므로, POI 의존 범위를 줄이려면 자체 구현 채택을 권함(DOC 경로와 독립, 0.0MB 추가).

## 4. 위험·남은 일

1. **기준 출력본**: 공개 시험 파일의 기준 PDF는 LibreOffice 출력. Word 출력본(QA)으로 바꿔 다시 대조해야 F-30 최종 판정 가능. real-04는 표·그림·머리글이 없어 구조 검증 범위가 좁다.
2. **조판 미검증**: 머리글/바닥글을 쪽마다 합성하는 MuPDF 경로(설계 3.4)와 5초 기준은 engine-mupdf 이후 기기에서 측정해야 함.
3. **떠 있는 그림·글상자(wps)**: 떠 있는 그림은 문단 안 블록 그림으로 내림(위치 무시). 글상자 안 글자는 `mc:Fallback`(VML)로만 읽음 → 빠질 수 있음. EMF 그림 시험 파일 없음.
4. **DOC 표 칸 수**: HWPF는 병합 셀을 물리 칸으로 돌려줘 행별 칸 수가 들쭉날쭉(예 [3,4,3,3]). 텍스트 누락은 없으나 칸 구조는 보존 안 됨(F-31 범위 밖).
5. **번호 매기기**: `w:lvlOverride`/`startOverride`·`w:numStyleLink` 미지원. 복잡한 다단계 번호에서 숫자가 달라질 수 있음.
6. **Standard(Office 2007) 암호·RC4 DOC 암호**: 미지원 → `Unsupported` → S-43 "이 암호 방식은 열 수 없어요." 시험 파일 없음.
7. **XML 파서**: DOM이라 수십 MB `document.xml`에서 메모리 부담. 대용량 시험 파일로 확인 필요.

## 5. 시험 파일

`docs/시험파일목록.md` Word 절 참고. 실무 파일은 `test-files/private/`(커밋 안 함). 로컬에서 `ASH_PRIVATE_TEST_FILES` 또는 `test-files/private/` 에 `real-04.docx`와 `real-04.ref.txt`(`pdftotext -layout real-04.ref.pdf`)가 있으면 `PrivateFilesTest`가 돌고, 없으면 건너뛴다.
