# inbox — 대화창 ↔ 노트북 연락함

이 가지(`inbox`)는 main 에 병합하지 않는 연락용 PR 의 가지입니다. 규칙은 main 의 `CLAUDE.md` '대화창 ↔ 노트북 소통'.

| 폴더 | 쓰는 쪽 | 내용 |
|---|---|---|
| `to-code/` | 대화창 | 노트북에게 주는 작업 지시 (날짜-번호-제목.md) |
| `to-chat/` | 노트북 | 결과·질문·중간 보고 (날짜-제목.md) |
| `done/` | 노트북 | 끝난 작업 지시를 옮겨 두는 곳 |

- 노트북 작업 폴더: `C:\Users\Ahn SH\dev\ash-reader-inbox` (`git worktree add ..\ash-reader-inbox inbox`)
- 커밋 메시지 끝에 `[skip ci]`. 큰 파일은 Releases, 개인 문서·키는 금지(공개 저장소).
