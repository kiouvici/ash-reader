# inbox — 대화창 ↔ 노트북 연락함

이 가지(`inbox`)는 main 에 병합하지 않는 연락용 PR 의 가지입니다. 규칙은 main 의 `CLAUDE.md` '대화창 ↔ 노트북 소통'.

| 폴더 | 쓰는 쪽 | 내용 |
|---|---|---|
| `to-code/` | 대화창 | 노트북에게 주는 작업 지시 (날짜-번호-제목.md) |
| `to-chat/` | 노트북 | 결과·질문·중간 보고 (날짜-제목.md) |
| `done/` | 노트북 | 끝난 작업 지시를 옮겨 두는 곳 |

- 노트북 작업 폴더: `C:\Users\Ahn SH\dev\ash-reader-inbox` (`git worktree add ..\ash-reader-inbox inbox`)
- 커밋 메시지 끝에 `[skip ci]`. 큰 파일은 Releases, 개인 문서·키는 금지(공개 저장소).

## 알림 (2026-10-24 시험 결과)
- inbox 커밋만으로는 대화창에 알림이 가지 않는다([skip ci] 라 검사 알림도 없음).
- **PR #41 댓글은 알림이 바로 간다**(알림 시험 2 확인). 그래서 inbox 에 커밋·push 한 뒤 반드시 `gh pr comment 41 -b "<보낸 파일 이름> — 한 줄 요약"` 을 단다.
