package com.shadowfit.model.exercise;

import com.shadowfit.model.report.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 운동 부위 카테고리 (BE-04). {@code ExerciseCategory} enum(LOWER/BACK/UPPER/CORE/FULL)이
 * 관리자가 만들고 지울 수 있는 테이블로 승격된 것 — V11 마이그레이션이 기존 5개 값을 그대로
 * 시드했고, {@code exercises.category_id} 가 이 테이블을 참조한다.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** 이름만 바꾼다 — 중복 검사는 서비스 몫이다(엔티티는 자기 데이터의 일관성만 책임진다). */
    public void rename(String name) {
        this.name = name;
    }
}
