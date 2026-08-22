package com.shadowfit.global.observability;

/**
 * 호출자가 이미 포기한 요청이라 작업을 시작하지 않았다는 신호 (#206 결함 B).
 *
 * <p><b>실패가 아니라 취소다.</b> 이걸 «에러» 로 다루면 서킷브레이커·알림이 우리 잘못을
 * 세게 되는데, 실제로 일어난 일은 «상대가 안 기다리기로 했다» 뿐이다. 그래서 호출 경계에서
 * {@code INTERNAL} 이 아니라 {@code CANCELLED} 로 번역한다.
 *
 * <p>서비스 계층이 던지고 gRPC 계층이 잡는다. 서비스가 {@code io.grpc} 타입을 던지지 않도록
 * 이 예외를 사이에 둔다 — {@link CallCancellation} 의 주석 참고.
 */
public class CallAbandonedException extends RuntimeException {
    public CallAbandonedException(String work) {
        super("호출자가 포기해서 시작하지 않았다: " + work);
    }
}
