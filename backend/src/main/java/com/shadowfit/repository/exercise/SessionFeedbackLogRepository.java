package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.exercise.SessionFeedbackLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionFeedbackLogRepository extends JpaRepository<SessionFeedbackLog, Long> {

    List<SessionFeedbackLog> findBySessionIdOrderByOccurredAtAsc(Long sessionId);

    @Query("SELECT l.feedbackType AS type, COUNT(l) AS count " +
           "FROM SessionFeedbackLog l WHERE l.session.id = :sessionId GROUP BY l.feedbackType")
    List<FeedbackTypeCount> countByTypeForSession(@Param("sessionId") Long sessionId);

    interface FeedbackTypeCount {
        FeedbackType getType();
        Long getCount();
    }
}