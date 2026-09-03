package com.shadowfit.global.error;


import lombok.Getter;

@Getter
public enum ErrorCode {


    // --- Common ---
    INVALID_INPUT_VALUE(400, "C001", "올바르지 않은 입력값입니다."),
    METHOD_NOT_ALLOWED(405, "C002", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(500, "C003", "서버 내부 오류가 발생했습니다."),
    INVALID_TYPE_VALUE(400, "C004", "입력값의 타입이 적절하지 않습니다."),
    HANDLE_ACCESS_DENIED(403, "C005", "접근이 거부되었습니다."),
    RESOURCE_NOT_FOUND(404, "C006", "요청한 경로를 찾을 수 없습니다."),
    FILE_TOO_LARGE(413, "C007", "파일 크기가 제한을 초과했습니다."),

    // --- Auth ---
    UNAUTHORIZED(401, "A001", "로그인이 필요한 서비스입니다."),
    ACCESS_DENIED(403, "A002", "해당 리소스에 대한 접근 권한이 없습니다."),
    TOKEN_EXPIRED(401, "A003", "인증 토큰이 만료되었습니다."),
    INVALID_TOKEN(401, "A004", "잘못된 인증 토큰입니다."),
    LOGIN_INPUT_INVALID(401, "A005", "비밀번호가 틀렸습니다."),
    // 폐기된 refresh token 이 도착했다 — 재시도 유예 밖이라 탈취로 본다 (이슈 #135).
    //
    // 문구에 «탈취» 라고 쓰지 않는 것은 의도다 — 낡은 기기가 살아 있을 때도 같은 코드가 나가고
    // (decisions/token-lifecycle.md §4-3), 서버는 그 둘을 구분하지 못한다.
    //
    // 🔴 **이 코드는 지금 클라에 도달하지 않는다.** ErrorResponseDto 가 status·message·timestamp
    // 만 싣고 code 를 안 싣는다. 즉 프론트는 A004(단순 무효)와 이걸 **message 문자열로만** 가를 수
    // 있다. 응답에 code 를 추가하는 것은 전 에러 응답의 계약 변경이라 이 작업 범위 밖으로 뒀다.
    REFRESH_TOKEN_REUSED(401, "A006", "만료된 로그인 정보입니다. 보안을 위해 다시 로그인해 주세요."),

    // --- User & Persona ---
    USER_NOT_FOUND(404, "U001", "존재하지 않는 사용자입니다."),
    INVALID_PERSONA_TYPE(400, "U002", "유효하지 않은 페르소나 설정입니다."),
    USERID_DUPLICATION(400, "U003", "이미 가입된 사용자입니다."),
    // 이슈 #195 — username 도 UNIQUE(V1__baseline.sql:28)인데 사전검사가 email 에만 있어
    // 겹친 닉네임으로 가입하면 400 이 아니라 500 이 나갔다.
    //
    // U003 을 재사용하지 않는 이유는 «코드» 가 아니라 «문구» 다. ErrorResponseDto 는 code 를
    // 싣지 않고(29~31행), 프론트는 message 를 그대로 사용자에게 띄운다
    // (frontend/app/(auth)/login.tsx:229-231). 즉 메시지가 유일한 식별자다 — 닉네임만 겹친
    // 신규 사용자에게 "이미 가입된 사용자입니다"가 나가면, 있지도 않은 자기 계정을 찾아
    // 로그인·비밀번호 찾기로 가게 된다.
    //
    // status 를 409 가 아니라 400 으로 두는 것은 바로 위 U003(email 중복)과 맞추기 위해서다.
    // 같은 폼의 두 필드가 다른 status 를 내는 쪽이 «충돌은 409» 라는 의미상의 정확함보다
    // 프론트에 해롭다. 옮기려면 U003 도 같이 옮겨야 하고 그건 계약 변경이다.
    USERNAME_DUPLICATION(400, "U004", "이미 사용 중인 닉네임입니다."),

    // --- Workout Session  ---
    EXERCISE_NOT_FOUND(404, "W001", "존재하지 않는 운동 종목입니다."),
    METADATA_NOT_FOUND(404, "W002", "운동 메타데이터(JSON/Video)를 찾을 수 없습니다."),
    SESSION_NOT_FOUND(404, "W003", "진행 중인 운동 세션을 찾을 수 없습니다."),
    S3_UPLOAD_ERROR(500, "W004", "파일 저장소(S3) 연결에 실패했습니다."),
    SESSION_ALREADY_IN_PROGRESS(409, "W005", "이미 진행 중인 운동 세션이 있습니다."),
    SESSION_DELETE_NOT_ALLOWED(409, "W006", "진행 중인 세션은 삭제할 수 없습니다."),
    // 종목 행은 있으나 ai-server에 분석기가 아직 없는 경우(런지·플랭크). 409가 아닌 400인 이유는
    // 세션 "상태" 충돌이 아니라 요청 자체가 처리 불가한 종목이기 때문.
    EXERCISE_NOT_SUPPORTED(400, "W007", "아직 분석을 지원하지 않는 운동입니다."),
    // 재부착 시도 시점에 이미 타임아웃 기준을 지난 세션(이슈 #59 2단계). 스케줄러가 아직 FAILED 로
    // 바꾸기 전이라 상태만 보면 IN_PROGRESS 로 보이는 틈이 있어, 상태와 별개로 시각을 확인한다.
    // 410 인 이유: 요청은 유효했고 세션도 실재했으나 이어붙일 수 있는 시간이 지났다.
    SESSION_REATTACH_EXPIRED(410, "W008", "재개할 수 있는 시간이 지난 세션입니다. 새로 시작해 주세요."),
    // 재부착을 지금은 못 하는 경우. 세션은 IN_PROGRESS 로 그대로 둔다 — 재부착은 복구 동작이라,
    // 일시적 장애로 세션을 FAILED 처리해버리면 되살릴 수 있었던 rep 을 이 기능이 스스로 없애는 꼴이
    // 된다. 클라는 잠시 후 같은 요청을 재시도하면 된다(멱등).
    //
    // 두 분기가 이 코드를 공유한다: ① AI 서버 연결 실패(서킷 OPEN / gRPC 에러) ② AI 가 재부착을
    // 거절(기준 좌표 복원 실패 등). 그래서 메시지에 원인을 단정하지 않는다 — "연결할 수 없어"라고
    // 쓰면 ②일 때 사용자에게 틀린 원인을 알려주게 된다(2026-07-31 통합 검증에서 발견).
    // 원인 구분은 서버 로그에 남는다.
    SESSION_REATTACH_UNAVAILABLE(503, "W009", "지금은 이어하기를 할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 운동이 실제로 진행 중인 회원의 탈퇴 시도. 세션 1건 삭제(W006)와 같은 방침을 탈퇴에도 적용한다
    // — 그 전까지는 "진행 중 세션 1건은 못 지우는데 그 세션을 가진 회원 전체는 지울 수 있는" 상태였다
    // (docs/decisions/withdrawal-with-active-session.md, 이슈 #87).
    //
    // "진행 중"의 판정은 상태값이 아니라 데이터 흐름으로 한다 — 아래 메시지가 "운동을 종료한 뒤"라고
    // 말할 수 있는 근거가 그것이다. 상태값으로 막으면 앱이 죽어 IN_PROGRESS 로 남은 세션 때문에
    // 운동 중이 아닌 사용자에게 이 메시지가 나가고, 그때 이 안내는 거짓말이 된다.
    WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION(409, "W010", "운동을 종료한 뒤 탈퇴할 수 있습니다."),
    // 관리자 운동 종목 삭제 거부. exercise_references·exercise_feedback_templates 는 FK 가
    // ON DELETE CASCADE 라 같이 지워지지만, exercise_sessions 만 CASCADE 가 없다
    // (V1__baseline.sql:83·278 vs :110). 즉 "이 종목으로 운동한 이력이 한 건이라도 있으면
    // 지울 수 없다"가 스키마가 이미 내린 결정이고, 이 코드는 그걸 500 대신 409 로 옮긴 것이다.
    //
    // 세션이 있는데도 지우려면 이력을 먼저 처리해야 하는데, 그건 "운동 종목 삭제"가 아니라
    // "회원 운동 기록 삭제"라 별개 결정이다. 그래서 여기서는 거부만 한다.
    EXERCISE_DELETE_NOT_ALLOWED(409, "W011", "운동 이력이 있는 종목은 삭제할 수 없습니다."),
    // analysis_supported 를 TRUE 로 켜려는데 기준 좌표(exercise_references)가 0건인 경우.
    // 기준 좌표는 분석의 실제 입력이다 — ExerciseAnalysisService 가 DB 에서 읽어 AnalyzeRequest
    // 에 실어 보내고(:215·:376), ai-server 는 그게 비면 "reference 각도 시퀀스가 비어 있음 —
    // sync_rate는 0으로 계산됨" 을 경고만 하고 **그대로 진행**한다(exercise_servicer.py:78-82).
    // 즉 켜두면 세션은 열리는데 결과가 전부 0 이 되므로, 켜기 전에 막는다.
    //
    // ⚠️ 이건 필요조건이지 충분조건이 아니다. W007 주석 참고.
    EXERCISE_ANALYSIS_ENABLE_BLOCKED(400, "W012", "기준 좌표가 없어 분석을 활성화할 수 없습니다."),
    // BE-04 — 카테고리 CRUD. 셋 다 EXERCISE_DELETE_NOT_ALLOWED(W011)와 같은 결이다: DB FK가
    // 이미 RESTRICT 로 막지만(V11__add_categories_table.sql), 그걸 500 대신 이 코드들로
    // 앞단에서 걸러 사용자에게 이유를 준다.
    CATEGORY_NOT_FOUND(404, "W013", "존재하지 않는 카테고리입니다."),
    CATEGORY_NAME_DUPLICATION(409, "W014", "이미 존재하는 카테고리 이름입니다."),
    CATEGORY_IN_USE(409, "W015", "사용 중인 카테고리는 삭제할 수 없습니다."),

    // --- 시도 제한 (이슈 #394) ---
    //
    // 429 를 쓰는 이유: 401/403 이 아니다. 자격증명이 틀린 것도, 권한이 없는 것도 아니라
    // **너무 잦다**는 뜻이고, 클라가 할 일이 «고쳐서 다시» 가 아니라 «기다렸다 다시» 다.
    // 둘 다 Retry-After 헤더를 같이 실어 보낸다 — 429 는 «언제» 를 못 주면 반쪽이다.
    TOO_MANY_LOGIN_ATTEMPTS(429, "A007", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    TOO_MANY_AUTH_REQUESTS(429, "A008", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // --- F10-1 Filtering Engine ---
    LOW_SYNC_RATE(400, "V001", "운동 싱크로율이 너무 낮아 기록되지 않았습니다."),
    INVALID_WORKOUT_DATA(400, "V002", "부정행위 또는 유효하지 않은 움직임이 감지되었습니다."),
    INSUFFICIENT_COUNT(400, "V003", "최소 운동 횟수를 채우지 못했습니다."),
    DATA_INTEGRITY_VIOLATION(422, "V004", "전달된 좌표 데이터가 손상되었거나 형식이 맞지 않습니다."),

    // --- AI & GPT Factory ---
    AI_FEEDBACK_FAILED(503, "AI001", "AI 피드백 생성 중 오류가 발생했습니다."),
    PROMPT_TEMPLATE_ERROR(500, "AI002", "GPT 프롬프트 생성 로직에 오류가 발생했습니다."),
    AI_QUOTA_EXCEEDED(429, "AI003", "AI 서비스 호출 할당량을 초과했습니다."),

    // --- Infrastructure & Cache  ---
    REDIS_CONNECTION_FAILURE(500, "I001", "캐시 서버 연결에 실패했습니다."),
    API_RESPONSE_TIMEOUT(504, "I002", "API 응답 시간이 초과되었습니다. (Threshold: 500ms)"),
    DATABASE_LOCK_FAILURE(500, "I003", "데이터베이스 트랜잭션 처리 중 오류가 발생했습니다."),

    //Report
    REPORT_NOT_FOUND(404,"R001","리포트를 찾을 수 없습니다"),

    // --- 트레이너 실시간 모니터링(SSE) ---
    NOT_ASSIGNED_TRAINER(403, "T001", "담당 사용자가 아닙니다."),

    // --- 운동 목표 (BE-06) ---
    GOAL_NOT_FOUND(404, "GL001", "존재하지 않는 목표입니다."),
    // goalType당 목표 1개 제약(GoalRepository.existsByMemberIdAndGoalType) — 위반 시 이 코드.
    GOAL_TYPE_DUPLICATION(409, "GL002", "이미 같은 종류의 목표가 있습니다."),

    // --- 다중사용자 실시간 동기화(그룹/파트너, WebSocket) ---
    GROUP_NOT_FOUND(404, "G001", "존재하지 않는 그룹입니다."),
    NOT_GROUP_MEMBER(403, "G002", "그룹 멤버만 접근할 수 있습니다."),
    ALREADY_GROUP_MEMBER(409, "G003", "이미 그룹에 가입되어 있습니다."),
    INVITATION_NOT_FOUND(404, "G004", "존재하지 않는 초대입니다."),
    INVITATION_ALREADY_RESPONDED(409, "G005", "이미 응답한 초대입니다."),
    INVITATION_ALREADY_PENDING(409, "G006", "이미 초대를 보냈습니다.");

    private final int status;
    private final String code;
    private final String message;

    ErrorCode(final int status, final String code, final String message) {
        this.status = status;
        this.message = message;
        this.code = code;
    }
}
