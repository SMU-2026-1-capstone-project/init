package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.ExerciseReference;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseReferenceRepository extends JpaRepository<ExerciseReference, Long> {

    // 이 조회는 saveReferencePoses(쓰기 경로)에서 재사용되지 않고 gRPC 전송용 읽기 전용 경로에서만
    // 쓰이므로, exercises의 findByIdCached와 달리 findByExerciseId 자체에 바로 캐시를 붙여도 안전.
    @Cacheable(cacheNames = "exerciseReferences", key = "#exerciseId")
    List<ExerciseReference> findByExerciseId(Long exerciseId);

    /**
     * 이 종목에 기준 좌표가 하나라도 있는가 — 관리자 분석 활성화 가드
     * ({@code AdminExerciseService.updateAnalysisSupport}).
     *
     * <p><b>일부러 캐시를 안 붙였다.</b> 위 {@code findByExerciseId} 는 gRPC 전송용 읽기 경로라
     * 캐시가 맞지만, 이쪽은 "방금 추출한 기준 좌표가 들어왔는가"를 판정하는 자리다. 캐시된 값을
     * 보면 추출 직후 활성화가 근거 없이 거부될 수 있다.
     */
    boolean existsByExerciseId(Long exerciseId);

    /**
     * 이 종목의 기존 기준 좌표를 전부 지운다 — 재추출이 <b>누적되지 않게</b> ({@code #220}).
     *
     * <p><b>왜 필요한가.</b> AI 는 이 표의 모든 행을 순서대로 읽어 <b>하나의 각도 시퀀스</b>로
     * 만든다({@code exercise_servicer._parse_reference_poses}). 그런데 그 코드는
     * {@code timestamp_sec} 을 아예 안 읽고 <b>순서만</b> 쓴다. 그래서 기존 행을 두고 새로
     * 넣으면 rep 두 벌이 <b>이어붙어 한 벌처럼</b> 읽힌다 — 2026-08-16 실측에서 37행이
     * 74행이 되며 {@code timestamp_sec} 이 0.00~4.56 을 두 번 훑었다.
     *
     * <p><b>«여러 정답지» 와 모순되지 않는다.</b> 제품 모델은 운동 1:N <b>스타일</b>이지만
     * «1스타일 1영상»이 같이 결정돼 있다
     * ({@code docs/decisions/reference-style-and-caching.md} §1). 즉 여러 벌은 <b>스타일
     * 간</b>에 여럿이지 한 스타일 안에서가 아니다. 스타일 식별자가 도입되면 이 삭제의
     * <b>범위만</b> {@code exerciseId} → {@code referenceId} 로 좁아진다.
     *
     * <p>파생 삭제라 엔티티를 로드해 지운다. 한 스타일의 기준은 수십 행이라 문제가 안 되고,
     * 벌크 삭제로 바꾸면 영속성 컨텍스트와 어긋날 위험이 생긴다.
     *
     * @return 지운 행 수. 로그에 «기존 몇 개를 교체했는지» 를 남기려고 받는다 — 조용한 교체는
     *         나중에 «왜 정답지가 바뀌었나» 를 못 되짚게 만든다.
     */
    long deleteByExerciseId(Long exerciseId);
}