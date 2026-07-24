package com.shadowfit.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
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
