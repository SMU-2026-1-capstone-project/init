package com.shadowfit.repository;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<Session,Long> {
    List<Session> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<Session> findAllByStatus(Status status);
}
