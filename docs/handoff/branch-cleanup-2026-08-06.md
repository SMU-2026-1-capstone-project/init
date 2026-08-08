# 브랜치 정리 복원 목록 — 2026-08-06

삭제한 브랜치와 그 시점의 커밋 해시. 되살리려면 `git branch <이름> <해시>` 후 push.
판정 근거: origin/main 에 완전히 머지됨(`git branch -r --merged`), 또는 죽은 브랜치.

> ✅ **2026-08-08 재확인 — 복원 경로가 아직 살아 있다.** 표본 6개(`cef5162`·`8ba93b0`·`eebf852`·`c98d405`·`025a014`·`96b8dca`)를 `git cat-file -e <hash>^{commit}` 으로 확인해 전부 도달 가능했다. 즉 이 표는 지금도 유효한 복원 지시서다.
>
> ⚠️ **다만 영구 보장은 아니다.** 이 해시들이 살아 있는 이유는 대부분 **origin/main 에 머지돼 있어서**(도달 가능) 이고, 죽은 브랜치 쪽은 **로컬 reflog·객체가 아직 GC 되지 않은 것**에 의존한다. `git gc --prune` 이 돌거나 저장소를 새로 클론하면 **후자는 사라진다.** 되살릴 생각이 있는 브랜치가 있으면 지금 태그를 걸어두는 것이 안전하다.
>
> 📌 표본 검사이고 31개 전수는 아니다.

## origin (원격)

| 브랜치 | 커밋 |
|---|---|
| `chore/coderabbit-review-schema` | `cef5162` |
| `ci/ai-server-tests` | `1d5762b` |
| `ci/backend-test-workflow` | `bd37e25` |
| `docs/frontend-session-lifecycle` | `3b4ce3b` |
| `docs/outbox-recheck` | `02d50b2` |
| `docs/outbox-review-followup` | `1c3a4a1` |
| `docs/outbox-sync-implementation` | `8ba93b0` |
| `docs/plan-sync-0803` | `721c525` |
| `docs/planning-and-querydsl` | `27c6a12` |
| `docs/session-liveness` | `f9a06e9` |
| `docs/withdrawal-active-session` | `4c24917` |
| `feat/active-session-query` | `d123f12` |
| `feat/admin-indexes` | `b18028e` |
| `feat/exercise-analysis-guard` | `52d8b7f` |
| `feat/observability-correlation-id` | `81f6c55` |
| `feat/outbox` | `eebf852` |
| `feat/rep-cleanup` | `2abf49b` |
| `feat/session-idle-timeout` | `31ffe25` |
| `feat/session-reattach` | `c98d405` |
| `feat/withdrawal-active-session-guard` | `2cf096e` |
| `feat/worst-rep-resolution` | `6d5d419` |
| `fix/ai-stop-success-flag` | `90120fc` |
| `fix/false-rep-bottom-dwell` | `4d420dc` |
| `fix/rep-frame-buffer-cap` | `0247ff6` |
| `fix/reports-updated-at` | `1979e26` |
| `fix/session-stats-and-tx-boundary` | `ca17ec0` |
| `fix/set-summary-unify` | `5cabbd9` |
| `fix/sync-rate-null-average` | `0914082` |
| `fix/timeout-notifies-ai` | `025a014` |
| `test/reattach-timeout-race` | `d440cae` |
| `ai-server` | `96b8dca` |
| `docs/plan-sync-and-integration-candidates` | `fe0a2d0` |

## 로컬

| 브랜치 | 커밋 |
|---|---|
| `Dev` | `0d89668` |
| `ci/ai-server-tests` | `1d5762b` |
| `ci/backend-test-workflow` | `bd37e25` |
| `docs/plan-sync-0803` | `721c525` |
| `docs/planning-and-querydsl` | `27c6a12` |
| `docs/session-liveness` | `f9a06e9` |
| `docs/withdrawal-active-session` | `4c24917` |
| `feat/active-session-query` | `d123f12` |
| `feat/admin-indexes` | `b18028e` |
| `feat/exercise-analysis-guard` | `52d8b7f` |
| `feat/observability-correlation-id` | `81f6c55` |
| `feat/rep-cleanup` | `2abf49b` |
| `feat/session-idle-timeout` | `31ffe25` |
| `feat/session-reattach` | `c98d405` |
| `feat/withdrawal-active-session-guard` | `2cf096e` |
| `feat/worst-rep-resolution` | `6d5d419` |
| `fix/false-rep-bottom-dwell` | `e7e04b7` |
| `fix/rep-frame-buffer-cap` | `35c8d1e` |
| `fix/session-stats-and-tx-boundary` | `ca17ec0` |
| `fix/set-summary-unify` | `5cabbd9` |
| `fix/sync-rate-null-average` | `0914082` |
| `fix/timeout-notifies-ai` | `025a014` |
| `test/reattach-timeout-race` | `d440cae` |
