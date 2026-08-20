-- session_feedback_logs 의 멱등키를 «시각» 에서 «rep 번호» 로 옮긴다 (#193 착수 전 결정 ②)
--
-- 왜 바꾸나 — 의미 재정의를 키가 못 따라갔다
-- ---------------------------------------------------------------------------
-- 이 표는 원래 «device TTS 가 실제 발화한 시점» 을 남기는 **발화 로그**였다
-- (docs/05-database-design.md:195, REQUIREMENTS.md:74). 그 의미에서는 사건이 곧 시각이라
-- (session_id, occurred_at, feedback_type) 이 옳은 키였다.
--
-- 그런데 기록 대상이 «AI 가 판정한 이벤트» 로 재정의됐고(SessionFeedbackLog 첫 줄의
-- «분기 2-A 의미 재정의»), 판정은 rep 단위다(#193 결정 ①). 사건의 정체가
-- «3번째 rep 에서 무릎이 모였다» 인데 키는 옛 의미에 남아 있었다.
--
-- 시각 키는 두 방향으로 깨졌다:
--   ㄱ. 재전송이 «전송 시각» 으로 다시 찍으면 같은 사건이 매번 새 행이 된다 (없는 사건이 생긴다)
--   ㄴ. occurred_at 은 DATETIME(초 단위)이라 1초 안의 두 사건이 하나로 뭉개진다 (있는 사건이 사라진다)
--       — 그리고 ㄴ 은 테스트로 안 잡힌다. H2 는 엔티티에서 스키마를 만들어 소수점 초가 살아있다.
-- rep 번호에는 둘 다 없다. «3번째 rep» 은 그게 0.8초였든 4초였든 3번째 rep 이다.
--
-- 지금 하는 이유: 이 경로는 **호출 0건**이라(#193 — AI 에 ReportFeedbackBatch 호출자가 없다)
-- 운영 데이터가 0행이다. 키를 바꾸기에 이보다 싼 시점은 없다.

-- ① 컬럼 추가. 기존 행을 위해 DEFAULT 0 으로 넣고, 채운 뒤 ③에서 기본값을 뗀다.
ALTER TABLE session_feedback_logs
    ADD COLUMN rep_number INT NOT NULL DEFAULT 0 AFTER session_id;

-- ② 기존 행 채우기.
--
-- 🔴 여기서 채우는 값은 **지어낸 값이다.** 이 경로는 한 번도 실행된 적이 없어
--    (mysql/dev-seed.sql:94 의 개발용 시드 20행이 전부다) «진짜 rep 번호» 라는 것이 존재하지 않는다.
--    ③의 UNIQUE 를 걸 수 있도록 (session_id, feedback_type) 안에서 시간순 일련번호를 준다.
--    전부 0 으로 두면 같은 세션·같은 유형이 충돌해 ③이 실패한다.
UPDATE session_feedback_logs l
    JOIN (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY session_id, feedback_type
                                  ORDER BY occurred_at, id) AS rn
        FROM session_feedback_logs
    ) t ON t.id = l.id
SET l.rep_number = t.rn;

-- ③ 기본값 제거.
--
-- 🔴 DEFAULT 를 남기면 안 된다. proto3 스칼라는 «미설정» 과 0 을 구분하지 못해서, 보내는 쪽이
--    rep_number 를 안 채우면 0 이 온다. 기본값이 있으면 그 0 이 조용히 들어가고 그 세션의
--    모든 이벤트가 «rep 0» 에서 서로를 중복으로 지운다. FeedbackLogService 가 rep_number <= 0 을
--    거절하는 것이 1차 방어이고, 이 줄은 스키마 쪽 흔적을 지우는 것이다.
ALTER TABLE session_feedback_logs
    MODIFY COLUMN rep_number INT NOT NULL;

-- ④ 키 교체.
ALTER TABLE session_feedback_logs
    DROP INDEX uk_session_event,
    ADD UNIQUE KEY uk_session_rep (session_id, rep_number, feedback_type);

-- ⑤ occurred_at 은 컬럼으로 남는다 (키에서만 뺀다).
--    조회 API 가 시간순 정렬에 쓴다 — SessionFeedbackLogRepository.findBySessionIdOrderByOccurredAtAsc.
--
-- ⚠️ **인덱스를 되살리지 않는 것은 의도다.** 2026-07-24 에 idx_session_feedback(session_id,
--    occurred_at)을 지운 근거가 «uk_session_event 가 앞 2컬럼을 그대로 포함한다» 였는데(V1:291),
--    새 키는 occurred_at 을 두 번째로 갖지 않으므로 그 근거가 사라진다. 즉 위 정렬은 이제
--    filesort 다.
--
--    그래도 되살리지 않는 이유: 이 쿼리의 정렬 대상은 **한 세션의 이벤트**로 묶여 있다.
--    3세트 × 10rep 세션에서 문제가 절반의 rep 에 잡혀도 수십 행이고, 그만큼의 filesort 는
--    인덱스 하나를 더 유지하는 값보다 싸다. 같은 저울로 2026-07-24 에 인덱스를 뺐고
--    (10만 행 batch INSERT 7,894ms → 6,202ms, 약 −21%), 여기서 되살리면 그 개선을 되돌린다.
--
--    🔴 미측정: EXPLAIN 으로 확인하지 않았다. 위는 «세션당 행 수가 작다» 는 구조적 근거에
--    의한 판단이다. 세션당 이벤트가 수백 행이 되는 감지기가 나오면 다시 볼 것 —
--    측정 rig 은 loadtest/measure_redundant_index.sh 에 이미 있다.
