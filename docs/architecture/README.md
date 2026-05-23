# Architecture 문서 가이드

이 디렉터리는 ShadowFit 의 **Spring ↔ FastAPI 결합 구조**를 4가지 각도로 다룹니다. 같은 사실을 다른 결로 정리해놓아서 목적에 맞는 문서로 들어가세요.

## 읽는 순서 (목적별)

### 처음 합류했다 / "지금 어떻게 결합돼 있나"
→ **[ai-backend-integration.md](./ai-backend-integration.md)** (현황 스냅샷)
- 통신 프로토콜·인증·세션 라이프사이클·동시성·인프라
- 변경 영향 매트릭스·알려진 약점
- 마지막 업데이트 기준의 **사실만**, 변화 과정 없음

### 어떤 일이 있었나 / "왜 지금 이렇게 됐나"
→ **[ai-backend-changelog.md](./ai-backend-changelog.md)** (시간순 줄거리)
- REST → gRPC 전환 → 실통합 → 신뢰성 강화 3단계
- 결합 표면이 줄어든 트렌드 관찰
- 결합 요소별 변경 시점 매트릭스

### 커밋이 정확히 뭘 바꿨나 / "proto가 어떻게 진화했나"
→ **[ai-backend-commit-details.md](./ai-backend-commit-details.md)** (커밋 단위 구체)
- 커밋별 **proto / Spring / AI / Infra** 4칸 분리
- 메시지 필드 추가·제거 단위 변경
- 결합 표면 파일 라이프사이클 (도입 → 삭제)

### 시점별로 어떤 파일을 만졌나 / "5월에 뭐 했나"
→ **[ai-backend-monthly-log.md](./ai-backend-monthly-log.md)** (월별 작업 로그)
- 2026-03 → 2026-04 → 2026-05 소단원 분할
- 파일 단위 작업 + 분석 한 줄
- 월별 결합 표면 변화량 표

### 앞으로 어떻게 가야 하나 / "결합 방식을 바꿔야 하나"
→ **[../decisions/ai-backend-coupling.md](../decisions/ai-backend-coupling.md)** (분기점·트레이드오프)
- 콜백 신뢰성 / proto 동기화 / `exercise_type` / 세션 상태 / 통신 프로토콜
- 선택지별 장단점 + 사용자 결정 대기

---

## 문서 간 관계도

```
                      decisions/
                      ai-backend-coupling.md   ← 미래 (분기점, 결정 대기)
                          ▲
                          │ "어떻게 바꿀까"
                          │
   ai-backend-integration.md   ← 현재 (사실)
        ▲
        │ "지금까지의 경로"
        │
   ai-backend-changelog.md         ← 줄거리
        ▲
        │ 더 구체
        │
   ai-backend-commit-details.md    ← 커밋 단위
   ai-backend-monthly-log.md       ← 시점별 (commit-details 와 다른 컷)
```

`commit-details` 와 `monthly-log` 는 같은 데이터를 다른 컷으로 정리한 자매 문서.
- **commit-details**: 기능 그룹 ("그룹 1 통신 기반 구축" 등) → 그 안에 커밋들
- **monthly-log**: 시간 그룹 ("2026-04 중반" 등) → 그 안에 커밋들

같은 커밋이 두 문서에 다른 맥락으로 등장. 한 쪽 보기 어려우면 다른 쪽으로.

---

## 관련 결정 문서

| 문서 | 상태 | 다루는 분기 |
|------|------|---------|
| [../decisions/ai-backend-coupling.md](../decisions/ai-backend-coupling.md) | **OPEN** (사용자 결정 대기) | 콜백 신뢰성·proto 동기화·세션 상태 외 3개 분기 |

새 분기점이 생기면 같은 디렉터리에 새 결정 문서를 추가합니다 ([`feedback-decision-doc`](../../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_decision_doc.md) 메모리 정책).

---

## 관련 일반 docs

결합 자체보다 더 좁은 범위는 docs 본체에 있음:
- [`../05-database-design.md`](../05-database-design.md) — DB 스키마 (결합 흔적: `exercise_references`, `version`, TTS 컬럼)
- [`../07-api-design.md`](../07-api-design.md) — REST API (결합 흔적: `/exercises/sessions/{id}/stop` deprecate 정책)
- [`../13-docker-setup.md`](../13-docker-setup.md) — Docker 구성 (결합 흔적: AI 포트 `expose` 정책)
- [`../15-session-timeout-guide.md`](../15-session-timeout-guide.md) — 타임아웃·동시성·멱등성 (Spring 측 콜백 신뢰성)

## 아카이브
- [`../16-archive-2026-05.md`](../16-archive-2026-05.md) — 2026-05 PR implementation summary 스냅샷
