-- 운동 부위 카테고리를 고정 enum(LOWER/BACK/UPPER/CORE/FULL)에서 관리 가능한 테이블로 승격한다
-- (BE-04, 관리자 카테고리 CRUD). exercises 는 현재 3행(스쿼트·런지·플랭크)뿐이라 백필은
-- enum 이름 그대로 시드한 뒤 매칭하는 것으로 충분하다.

CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name (name)
);

-- 기존 enum 5개 값을 그대로 시드한다. id 순서는 enum 선언 순서(LOWER,BACK,UPPER,CORE,FULL)와
-- 맞춰 1~5 — exercises.category 백필 매칭에서 이름으로 조인하므로 순서 자체는 의미 없지만,
-- 읽는 사람이 enum 과 대조하기 쉽도록 맞춘다.
INSERT INTO categories (name) VALUES ('LOWER'), ('BACK'), ('UPPER'), ('CORE'), ('FULL');

ALTER TABLE exercises ADD COLUMN category_id BIGINT NULL AFTER category;

UPDATE exercises e
    JOIN categories c ON c.name = e.category
    SET e.category_id = c.id;

-- 백필이 전부 매칭됐는지 여기서 확인하지 않는다(SQL 마이그레이션은 assert 를 못 던진다) —
-- 대신 다음 줄의 NOT NULL 전환이 실패하면 매칭 안 된 행이 있다는 뜻으로 마이그레이션이 멈춘다.
ALTER TABLE exercises MODIFY COLUMN category_id BIGINT NOT NULL;

ALTER TABLE exercises
    ADD CONSTRAINT fk_exercises_category FOREIGN KEY (category_id) REFERENCES categories(id);

-- 카테고리 삭제는 AdminCategoryService 가 "사용 중이면 막는다"로 애플리케이션에서 방어한다
-- (409 CATEGORY_IN_USE) — 그런데도 FK 를 RESTRICT(MySQL 기본)로 남겨 DB 레벨에서도 이중으로
-- 막는다. 서비스 검증과 FK 제약이 같은 정책을 두 번 말하는 것은 의도다.

ALTER TABLE exercises DROP COLUMN category;
