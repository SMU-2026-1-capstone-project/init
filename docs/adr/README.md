# ADR (Architecture Decision Record)

작성일: 2026-09-01
범위: **이 디렉터리 시점 이후로 작성하는 새 결정만.** 기존 [`../decisions/`](../decisions/) 100여 개 문서는 리포맷하지 않는다 — 사용자 확정(2026-09-01).

## `docs/decisions/`와 무엇이 다른가

이 프로젝트에는 이미 `docs/decisions/`라는 결정 기록 체계가 있고, 잘 작동하고 있다. ADR을 별도로 두는 이유는 그걸 대체하기 위해서가 아니다 — **레이어가 다르다.**

| | `docs/decisions/` | `docs/adr/` |
|---|---|---|
| 다루는 것 | 트레이드오프 분석·실측 실험·미결정 상태 포함 전 과정 | **이미 끝난** 결정 하나의 최종 요약 |
| 길이 | 길다 (실험 라운드·재검증·반박까지 누적) | 짧다 (한 페이지) |
| 상태 | 미결정/결정 대기 상태로도 존재 가능 | **결정된 것만** 씀 — 미결정 사안은 여전히 `decisions/`에 |
| 갱신 | 같은 파일에 §를 계속 추가 | 결정이 뒤집히면 새 ADR을 만들고 이전 것을 "Superseded"로 표시(Nygard 방식) |

즉 `docs/decisions/xxx.md`가 결론까지 도달하면, 그 결론만 뽑아 `docs/adr/000N-yyy.md`로 한 장 남긴다. ADR의 Context/Decision 섹션에서 근거가 된 `decisions/xxx.md`를 링크한다 — 내용을 복사하지 않는다.

## 번호·파일명 규칙

- `docs/adr/0001-<kebab-title>.md` 부터 시작 (0000은 템플릿 전용, 실제 ADR 번호로 안 씀)
- 번호는 순증 전용 — 삭제된 결정도 번호를 재사용하지 않고 상태를 `Superseded by ADR-000N`으로 남긴다
- 제목은 "무엇을 할지"가 아니라 "무엇을 결정했는지"를 담는다 (예: `0001-use-outbox-for-session-completion.md`)

## 상태(Status) 값

- `Proposed` — 제안됐지만 아직 확정 전 (가급적 이 상태로 병합하지 않는다 — 확정 후 작성 원칙, 아래 참고)
- `Accepted` — 채택, 현재 유효
- `Superseded by ADR-000N` — 후속 ADR로 대체됨
- `Deprecated` — 대체 없이 폐기

## 작성 시점

[[feedback_user_decides_not_claude]] 원칙을 그대로 따른다 — **사용자 confirm 이후에만** `Accepted`로 작성한다. 분기점 분석 자체는 이 디렉터리가 아니라 그대로 `docs/decisions/`에 문서로 올리고([[feedback_decision_doc]]), 결정이 난 뒤 이 디렉터리에 요약 한 장을 추가하는 순서다.

템플릿: [`0000-template.md`](./0000-template.md)
