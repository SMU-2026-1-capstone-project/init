---
name: verify
description: Build/launch/drive recipe for manually verifying backend API changes end-to-end via real HTTP requests.
---

# Verify recipe — shadowfit backend

## Build & launch
- Docker Desktop must be running first (`docker info` to check; on Windows, launch `Docker Desktop.exe` and poll `docker info` until it succeeds, ~30-90s).
- `docker compose build shadowfit-backend` — **always rebuild before verifying**, even if the container is already "Running". `docker compose up -d` alone reuses the cached image and will silently run stale code (bit us once: a container built 5h earlier didn't have the same-day security fixes, producing false negatives).
- `docker compose up -d --force-recreate shadowfit-backend` after building, then poll `curl http://localhost:9090/actuator/health` until `"status":"UP"`.
  - **Port 9090, not 8080** — actuator moved to a separate management port (2026-08-08, `application.yml` `[6]`) so `/actuator/prometheus` isn't reachable from the outside. The app's own API stays on 8080. Dev compose maps 9090 to the host purely so this polling works; `docker-compose.prod.yml` deliberately does not.
- Observability stack (Prometheus + Grafana) is **off by default** and lives behind a compose profile — `docker compose --profile obs up -d`. Grafana at `http://localhost:3000` (admin/admin), Prometheus at `http://localhost:9091`. Leave it off while running load tests: this box is 2 physical cores and the stack contaminates the measurement.
- Only `mysql` + `shadowfit-backend` are needed for auth/authorization checks — `shadowfit-ai` isn't required unless the flow under test actually calls into AI (gRPC to FastAPI).

## Auth flow
- Signup: `POST /member/signup` with `{username, email, password, sex, role}`.
  - `sex` enum: `MALE | FEMALE | NONE` (`model/member/Sex.java`). An earlier version of this file warned of a `FEAMALE` typo — **that typo is gone**; don't report it.
  - `role` enum: `USER | ADMIN`, but **signup ignores whatever you send.** `MemberService.java` deliberately omits `.role(...)` from the builder so `Member`'s `@Builder.Default(UserRole.USER)` wins — the absence of that line *is* the fix (issue #138, `decisions/admin-role-provisioning.md`). Sending `"role":"ADMIN"` gives you a `USER` account.
    - ⚠️ An earlier version of this file described self-assignable ADMIN as a live privilege-escalation finding. **It is fixed — do not report it.** Verify against the code before flagging anything from this file.
    - **To get an ADMIN token**, promote by hand — there is no promotion API or seed (`admin-role-provisioning.md` §3-ㄱ; manual SQL is the documented bootstrap until stage 2 lands):

      ```bash
      docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit \
        -e "UPDATE users SET role='ADMIN' WHERE email='<your-test-account>';"
      ```

      Re-login afterwards — the role is baked into the issued token.
  - **Bad request bodies return `ErrorResponseDto`, not a bare error.** `GlobalExceptionHandler` covers `MethodArgumentNotValidException` (`@Valid` failures, with per-field messages), `MethodArgumentTypeMismatchException` (bad `@RequestParam`, lists allowed enum values), `NoResourceFoundException` (404), `AccessDeniedException` (403), and `HttpMessageNotReadableException` (malformed/missing JSON — #180, commit `6a3a736`).
    - ⚠️ The `HttpMessageNotReadableException` handler landed on `origin/main` **after** some working branches forked. On a branch that predates it, a malformed body still 500s — that is branch lag, **not a new finding**. Check with `git merge-base --is-ancestor 6a3a736 HEAD` before reporting it.
- Login: `POST /member/login` with `{email, password}` → `{accessToken, refreshToken, role}`. Pass `accessToken` as `Authorization: Bearer <token>`.
- Dev fixtures (`mysql/dev-seed.sql`) provide one user `test@test.com` (id=1, password unknown/hashed) with sessions `601-619` — usable as an "other user's resource" target for IDOR checks without needing that account's password (e.g. hit `/reports/session/601` as a different logged-in user and expect 403).
  - ⚠️ **These are no longer seeded automatically.** Since Flyway was introduced (issue #115) the container's initdb mount is gone; Flyway applies schema + master data on backend boot, but deliberately **not** the fixtures — they must never reach a deployed environment. Load them yourself when you need them:

    ```bash
    docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < mysql/dev-seed.sql
    ```

    ⚠️ That script TRUNCATEs users/sessions/reports first — it resets dev data. If `/reports/session/601` 404s, the fixtures simply aren't loaded.

### What Flyway actually seeds — and what it doesn't

- **`exercises` is never seeded.** `INSERT INTO exercises` exists in **no** migration and no file under `mysql/` (grep-verified 2026-08-11). `V2__seed_master_data.sql` seeds feedback templates and fixture-ish rows, not the exercise catalogue. A **fresh** environment therefore comes up with an empty `exercises` table, and anything that starts a session fails on it. This is a known open defect — `tasks/32-deferred-items.md` P2. Don't re-report it as new; do expect it to bite on a clean volume.
- **The dev box hides it.** `flyway.baseline-version: 2` stamps V1+V2 as already-applied on a pre-existing DB instead of running them (`application.yml` `[flyway]`, deliberate — running V2 would double-insert). So this box's `flyway_schema_history` shows only `2 | existing schema before flyway` and `3 | ...`, and its `exercises` rows are **pre-Flyway leftovers in the `mysql_data` volume, not migration output**.
- **Consequence for verifying:** a green run here does not prove a fresh environment works. To exercise the real path, use a clean volume — and expect to insert an `exercises` row yourself until P2 is fixed.

  ```bash
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit \
    -e "SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;"
  ```

## Gotchas
- Hitting a **removed** route without an auth header returns 401 (Spring Security's filter chain intercepts before route resolution — a 401 does NOT prove the mapping is gone). To prove a route was actually deleted, retry the same request **with a valid bearer token** and expect 404.
- DB is a Docker volume (`mysql_data`) — persists across `--force-recreate` of the backend container, so seed data and any accounts you create survive backend rebuilds. Clean up test accounts you create (`DELETE /member/{email}` as that user) when done.
- `docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "..."` works for direct DB spot-checks (e.g. confirming a row wasn't mutated by a blocked request).
