package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    // GroupEventService.publish()의 시퀀스 채번용 — 이 그룹 행을 잠근 채로 next_seq를
    // 읽고 증가시켜야 동시 publish 사이의 경합을 막을 수 있다 (Group.allocateNextSeq() 참고).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Group g where g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);
}