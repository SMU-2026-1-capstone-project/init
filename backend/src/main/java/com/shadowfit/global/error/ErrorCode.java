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

    // --- Auth ---
    UNAUTHORIZED(401, "A001", "로그인이 필요한 서비스입니다."),
    ACCESS_DENIED(403, "A002", "해당 리소스에 대한 접근 권한이 없습니다."),
    TOKEN_EXPIRED(401, "A003", "인증 토큰이 만료되었습니다."),
    INVALID_TOKEN(401, "A004", "잘못된 인증 토큰입니다."),
    LOGIN_INPUT_INVALID(401, "A005", "비밀번호가 틀렸습니다."),

    // --- User & Persona ---
    USER_NOT_FOUND(404, "U001", "존재하지 않는 사용자입니다."),
    INVALID_PERSONA_TYPE(400, "U002", "유효하지 않은 페르소나 설정입니다."),
    USERID_DUPLICATION(400, "U003", "이미 가입된 사용자입니다."),

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
    // AI 서버가 죽었거나(서킷 OPEN) gRPC 가 실패해 재부착을 못 한 경우. 세션은 IN_PROGRESS 로 그대로
    // 둔다 — 재부착은 복구 동작이라, 일시적 장애로 세션을 FAILED 처리해버리면 되살릴 수 있었던 rep 을
    // 이 기능이 스스로 없애는 꼴이 된다. 클라는 잠시 후 같은 요청을 재시도하면 된다(멱등).
    SESSION_REATTACH_UNAVAILABLE(503, "W009", "분석 서버에 연결할 수 없어 이어하기를 실패했습니다. 잠시 후 다시 시도해 주세요."),

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
    REPORT_NOT_FOUND(404,"R001","리포트를 찾을 수 없습니다");

    private final int status;
    private final String code;
    private final String message;

    ErrorCode(final int status, final String code, final String message) {
        this.status = status;
        this.message = message;
        this.code = code;
    }
}
