package com.shadowfit.global.observability;

import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * correlation id(요청 상관관계 식별자)의 발급·MDC 적재·스레드 경계 전파를 담당하는 유틸.
 *
 * <p>[왜 필요한가] 세션 하나가 끝나는 경로에 스레드가 4~5개 걸쳐 있다 — 톰캣 요청 스레드,
 * {@code @Async} 워커, gRPC 이벤트 루프, grpc-server 워커, 스케줄러. 여기에 프로세스 경계
 * (Spring ↔ FastAPI)까지 있어서, 로그에 세션 ID만 있으면 "어느 흐름이 이 로그를 남겼는지"를
 * 재구성할 수 없다. cid를 MDC에 넣고 로그 패턴({@code logback-spring.xml})에서 {@code %X{cid}}로
 * 뽑으면 기존 {@code log.info(...)} 호출을 한 줄도 고치지 않고 전체 로그가 상관관계를 갖는다.
 *
 * <p>[cid vs sessionId] 둘 다 MDC에 넣는다. cid는 <b>요청 1건</b>(짧은 수명), sessionId는
 * <b>운동 세션 1건</b>(긴 수명, 요청 수십 건). 그래서 "cid는 다른데 sessionId가 같다" = 서로 다른
 * 두 흐름이 같은 레코드에서 만났다 = 스케줄러↔콜백 경쟁이 실제로 일어난 순간의 증거가 된다.
 *
 * <p>[주의] MDC는 내부적으로 ThreadLocal이라 스레드가 바뀌면 그냥 사라진다. 경계를 넘길 때는
 * 반드시 {@link #wrap(Runnable)}(제출 시점 캡처 → 실행 시점 복원)이나
 * {@link #preserving(StreamObserver)}를 거쳐야 한다.
 */
public final class CorrelationIds {

    /** 로그 패턴에서 {@code %X{cid}} 로 참조하는 MDC 키. */
    public static final String MDC_KEY = "cid";

    /** 로그 패턴에서 {@code %X{sessionId}} 로 참조하는 MDC 키. */
    public static final String SESSION_MDC_KEY = "sessionId";

    /** HTTP 진입점에서 수용/반환하는 헤더명. */
    public static final String HTTP_HEADER = "X-Request-Id";

    /** gRPC metadata 키 — gRPC 규약상 반드시 소문자여야 한다. */
    public static final Metadata.Key<String> GRPC_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * 외부에서 들어온 id를 그대로 MDC에 넣으면 개행 문자를 섞어 가짜 로그 줄을 만들어내는
     * 로그 인젝션이 가능하다. 화이트리스트 문자·길이를 벗어나면 버리고 새로 발급한다.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private CorrelationIds() {
    }

    /** 로그에서 눈으로 짚기 좋은 길이(12 hex)로 자른 랜덤 id. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 스케줄러처럼 <b>들어오는 요청이 없는</b> 흐름이 스스로 발급하는 id.
     * 접두사로 어느 스케줄러의 몇 번째 tick인지 로그에서 바로 구분된다.
     */
    public static String newTaskId(String prefix) {
        return prefix + "-" + newId();
    }

    /** 외부 입력을 검증한다. 부적합하면 {@code null} — 호출측이 새로 발급하면 된다. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 현재 스레드의 cid. 없으면 {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 주어진 cid를 현재 스레드 MDC에 설정한다. {@code close()} 시 이전 값으로 복원. */
    public static Scope withCorrelationId(String cid) {
        return put(MDC_KEY, cid);
    }

    /** 요청 원점이 없는 흐름(스케줄러 tick 등)이 새 cid를 발급해 설정한다. */
    public static Scope startTask(String prefix) {
        return put(MDC_KEY, newTaskId(prefix));
    }

    /** 운동 세션 식별자를 MDC에 얹는다. cid와 함께 찍혀 "같은 세션, 다른 흐름"을 드러낸다. */
    public static Scope withSession(Long sessionId) {
        return put(SESSION_MDC_KEY, sessionId == null ? null : String.valueOf(sessionId));
    }

    /**
     * {@code @Async}·executor 경계용 데코레이터. <b>제출 시점(부모 스레드)에 MDC를 캡처</b>하고
     * <b>실행 시점(워커 스레드)에 복원</b>한다 — 이 타이밍을 뒤집으면(워커 안에서 캡처) 항상
     * 비어 있다. 워커 스레드는 풀에서 재사용되므로 실행 후 원래 상태로 되돌려야 다음 작업에
     * 이전 요청의 cid가 새어나가지 않는다.
     */
    public static Runnable wrap(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContextMap(captured);
            try {
                runnable.run();
            } finally {
                setContextMap(previous);
            }
        };
    }

    /**
     * 비동기 gRPC 스텁의 콜백(onNext/onError/onCompleted)은 <b>gRPC 이벤트 루프 스레드</b>에서
     * 실행돼 호출자의 MDC가 없다. 그 결과 정작 가장 중요한 {@code onError} 실패 로그에 cid가
     * 안 붙는다 — 호출 시점 MDC를 캡처해 콜백 실행 동안만 복원해주는 데코레이터.
     */
    public static <T> StreamObserver<T> preserving(StreamObserver<T> delegate) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return new StreamObserver<T>() {
            @Override
            public void onNext(T value) {
                restored(() -> delegate.onNext(value));
            }

            @Override
            public void onError(Throwable t) {
                restored(() -> delegate.onError(t));
            }

            @Override
            public void onCompleted() {
                restored(delegate::onCompleted);
            }

            private void restored(Runnable body) {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                setContextMap(captured);
                try {
                    body.run();
                } finally {
                    setContextMap(previous);
                }
            }
        };
    }

    private static Scope put(String key, String value) {
        String previous = MDC.get(key);
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
        return () -> {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        };
    }

    private static void setContextMap(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * try-with-resources로 MDC 정리를 강제하기 위한 핸들. {@code close()}가 체크 예외를 던지지
     * 않도록 좁혀서, 사용처에서 catch를 강요받지 않게 한다.
     */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
