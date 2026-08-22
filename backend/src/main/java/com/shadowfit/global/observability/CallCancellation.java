package com.shadowfit.global.observability;

import io.grpc.Context;

/**
 * 호출자가 이미 포기했는지 보는 창구 (#206 결함 B).
 *
 * <p>gRPC 의 deadline 은 «호출 타임아웃» 이 아니라 <b>호출 사슬을 따라 전파되는 예산</b>이다.
 * 클라이언트가 포기하면 서버 쪽 {@code Context} 가 취소되는데, 이 저장소는 그것을 어느
 * 핸들러에서도 보지 않았다 — 아무도 안 받을 응답을 만들려고 커넥션·트랜잭션·CPU 를 계속 썼다.
 * 부하가 걸릴수록 손해가 커지는 방향이다(느려서 포기당했는데, 포기당한 작업이 자원을 더 먹는다).
 *
 * <p><b>왜 서비스가 {@code io.grpc.Context} 를 직접 안 보는가.</b> {@code PoseDataService} ·
 * {@code SessionService} 는 gRPC 전용이 아니다 — 스케줄러와 REST 컨트롤러도 같은 메서드를
 * 부른다. 거기서 {@code io.grpc} 를 import 하면 «이 서비스는 gRPC 것» 이라는 잘못된 신호가
 * 남고, 나중에 전송 계층이 바뀌면 서비스까지 따라 바뀐다. 창구를 하나 두면 그 결합이
 * {@code global/observability} 안에서 끝난다 — {@link CorrelationIds} 와 같은 자리·같은 이유다.
 *
 * <p><b>gRPC 밖에서 부르면 항상 «안 포기했다» 다.</b> {@code Context.ROOT} 는 취소되지 않으므로
 * 스케줄러·REST 경로에서는 이 검사가 아무 일도 하지 않는다. 호출부에 조건을 달 필요가 없다.
 *
 * <p>🔴 <b>이건 «중단» 이 아니라 «시작 안 함» 이다.</b> 이미 시작된 INSERT 를 중간에 끊지
 * 않는다 — 그러려면 부분 저장을 어떻게 다룰지부터 정해야 하고, 그건 멱등 설계와 같이 봐야 한다
 * (#206 조치 후보 B-2·B-3). 여기서 막는 것은 <b>«아직 안 쓴 작업을 새로 시작하는 것»</b> 뿐이라
 * 배치가 통째로 살거나 통째로 안 들어간다. 부분 저장이 생길 여지가 없다.
 */
public final class CallCancellation {

    private CallCancellation() {
    }

    /** 호출자가 이미 포기했는가. gRPC 밖에서는 항상 {@code false}. */
    public static boolean isAbandoned() {
        return Context.current().isCancelled();
    }

    /**
     * 되돌릴 수 없는 작업(배치 INSERT, 상태 전이)을 <b>시작하기 직전에</b> 부른다.
     *
     * @param work 로그·예외 메시지에 남길 작업 이름
     * @throws CallAbandonedException 호출자가 이미 포기했을 때
     */
    public static void abortIfAbandoned(String work) {
        if (isAbandoned()) {
            throw new CallAbandonedException(work);
        }
    }
}
