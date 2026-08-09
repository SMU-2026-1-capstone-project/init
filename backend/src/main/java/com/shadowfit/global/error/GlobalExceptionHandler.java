package com.shadowfit.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * BusinessException 의 ErrorCode 를 그대로 HTTP status 로 매핑 (BE-14/15 의 403/404 케이스 정합).
 * @Valid 실패·그 외 미처리 예외도 동일한 ErrorResponseDto 형식으로 통일 (api-improvement-opportunities.md §3-①).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDto> handleBusinessException(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("BusinessException: {} ({})", code.getCode(), code.getMessage());
        return buildResponse(code);
    }

    /**
     * ⚠️ 2026-07-24 추가: @PreAuthorize("hasRole(...)")가 던지는 AccessDeniedException은 MVC
     * 핸들러 호출(디스패처 서블릿) 도중 발생해서, SecurityConfig의 CustomAccessDeniedHandler
     * (필터체인 레벨 전용)까지 못 가고 여기 도착함. 이 핸들러가 없으면 아래
     * handleUnexpectedException(Exception.class)이 그냥 삼켜서 403 대신 500이 나가던 버그가
     * 있었음 — AdminAuthorizationIntegrationTest로 발견.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("AccessDeniedException: {}", e.getMessage());
        return buildResponse(ErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationException(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.INVALID_INPUT_VALUE;
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return buildResponse(code, message.isEmpty() ? code.getMessage() : message);
    }

    /**
     * ⚠️ 2026-08-08 추가: @RequestParam 의 타입 변환 실패(enum 에 없는 정렬 키, 숫자가 아닌 page)는
     * MethodArgumentTypeMismatchException 이라 위 MethodArgumentNotValidException 핸들러에 안 걸리고
     * handleUnexpectedException 으로 떨어져 400 대신 500 이 나가던 버그가 있었음
     * — AdminQueryParamBindingErrorTest 로 발견. 29~33행의 AccessDeniedException 건과 같은 형태다.
     *
     * <p>@ModelAttribute 쪽(검색조건 record 의 status·startedFrom)은 Spring 6.1 부터 바인딩 실패가
     * MethodArgumentNotValidException 으로 오므로 이미 400 이었다. 같은 테스트가 그 두 경로도
     * 함께 고정해 둔다 — 프레임워크가 바꾸면 알아채야 하는 지점이라서.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ErrorCode code = ErrorCode.INVALID_INPUT_VALUE;
        log.warn("Type mismatch on parameter '{}': {}", e.getName(), e.getValue());
        return buildResponse(code, buildTypeMismatchMessage(e));
    }

    /** enum 이면 허용값을 같이 알려준다 — 관리자 화면 개발 중 정렬 키를 맞추는 데 그게 실질적으로 필요하다. */
    private String buildTypeMismatchMessage(MethodArgumentTypeMismatchException e) {
        String base = "%s: '%s' 는 허용되지 않는 값입니다.".formatted(e.getName(), e.getValue());
        Class<?> required = e.getRequiredType();
        if (required != null && required.isEnum()) {
            String allowed = Arrays.stream(required.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return base + " 허용값: " + allowed;
        }
        return base;
    }

    /**
     * ⚠️ 2026-08-08 추가(이슈 #129): 매핑되지 않은 경로는 Spring 6.1+ 에서
     * NoResourceFoundException 으로 온다. 이 핸들러가 없으면 handleUnexpectedException 이 받아
     * <b>404 대신 500</b> 이 나가고, 매 요청마다 {@code log.error} 스택트레이스가 남았다.
     * 29~33행 AccessDeniedException(403→500)·53~61행 MethodArgumentTypeMismatchException(400→500)
     * 과 <b>같은 형태의 세 번째 사례</b>다.
     *
     * <p>계기는 관리 포트 분리(#6)다. {@code /actuator/health} 는 whitelist 에 남아 있어(9090
     * 헬스체크가 401 로 깨지지 않으려면 필요) 8080 요청이 시큐리티를 통과하는데 핸들러는 9090
     * 에만 있다 — 즉 <b>상시 경로</b>가 됐다. 외부 uptime 모니터가 8080 을 주기적으로 찌르면
     * 매 요청이 ERROR 로그가 되어 로그 자체가 오탐 채널이 된다.
     *
     * <p>WARN 으로 남기는 이유: 클라이언트 오타든 서버 라우팅 실수든 <b>서버 결함은 아니다.</b>
     * 다만 경로는 남겨야 "8080 을 찌르고 있다"를 알아챌 수 있다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleNoResourceFound(NoResourceFoundException e) {
        // getResourcePath() 는 앞 슬래시가 없다("no-such-path"). 로그를 경로로 grep 하려면 붙여야 한다.
        log.warn("No handler for {} /{}", e.getHttpMethod(), e.getResourcePath());
        return buildResponse(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * ⚠️ 2026-08-10 추가(이슈 #180): 요청 본문이 없거나 JSON 이 깨지면
     * {@code HttpMessageNotReadableException} 이 온다. 핸들러가 없으면 아래
     * {@code handleUnexpectedException} 이 받아 <b>400 대신 500</b> 이 나갔다.
     * 29~33행(403→500) · 53~61행(400→500) · 84~97행(404→500) 에 이은
     * <b>같은 형태의 네 번째 사례</b>다.
     *
     * <p><b>범위가 넓다</b> — 특정 컨트롤러가 아니라 <b>본문을 받는 모든 엔드포인트</b>가 해당된다.
     * 실측(2026-08-10)에서 본문 없음 · 깨진 JSON · 필드 타입 불일치 셋 다 500 이었고,
     * 그중 {@code POST /member/login} 은 whitelist 라 <b>인증 없이</b> 칠 수 있었다. 즉
     * 로그 볼륨을 외부에서 통제할 수 있는 상태였다 — 84~97행이 "로그가 오탐 채널이 된다"고
     * 적은 것과 같은 문제지만, 경로 오타로 <i>우연히</i> 발생하던 그쪽과 달리 이건 확정적이다.
     *
     * <p>🔴 <b>파서 메시지를 응답에 싣지 않는다.</b> 44행({@code @Valid} 실패)은 필드별 메시지를
     * 응답에 실어주는데 그건 <b>우리가 쓴 문구</b>라 안전하다. Jackson 의 파싱 오류 메시지에는
     * 클래스명·필드 경로가 섞일 수 있어 로그에만 남긴다.
     *
     * <p>WARN 인 이유: 클라이언트가 보낸 본문이 잘못된 것이지 서버 결함이 아니다(84~97행과 같은 판단).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMostSpecificCause().getMessage());
        return buildResponse(ErrorCode.INVALID_INPUT_VALUE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpectedException(Exception e) {
        log.error("Unhandled exception", e);
        return buildResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // CodeRabbit 지적 반영(2026-07-24): 4개 핸들러가 거의 동일한 ErrorResponseDto 생성 로직을
    // 반복하고 있어 공통 헬퍼로 추출.
    private ResponseEntity<ErrorResponseDto> buildResponse(ErrorCode code) {
        return buildResponse(code, code.getMessage());
    }

    private ResponseEntity<ErrorResponseDto> buildResponse(ErrorCode code, String message) {
        return ResponseEntity
                .status(code.getStatus())
                .body(ErrorResponseDto.builder()
                        .status(code.getStatus())
                        .message(message)
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}
