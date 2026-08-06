package com.shadowfit.global.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 진입점({@link JPAQueryFactory}) 빈 등록.
 *
 * <p>QueryDSL 은 외부 라이브러리라 {@code @Component} 를 붙일 수 없으므로 설정 클래스에서
 * {@code @Bean} 으로 등록한다. 리포지토리마다 {@code new JPAQueryFactory(em)} 을 반복하면
 * EntityManager 주입과 생성이 매번 붙고, 나중에 생성 방식을 바꿀 때 전부 고쳐야 한다.
 *
 * <p><b>싱글턴이 EntityManager 를 들고 있어도 안전한 이유</b> — 주입되는 EntityManager 는
 * 실제 구현체가 아니라 프록시다. 호출 시점마다 <b>그 스레드의 트랜잭션에 묶인 진짜
 * EntityManager</b> 로 위임하므로, 이 빈이 싱글턴이어도 동시 요청끼리 영속성 컨텍스트가
 * 섞이지 않는다. 이 프록시 한 겹이 없으면 EntityManager 는 스레드 안전하지 않다.
 *
 * <p>적용 범위는 {@code querydsl-adoption.md} §4-1 — 동적 조건이 있는 관리자 목록/검색과
 * 그 총건수 COUNT 가 대상이고, 조건 고정 집계(대시보드 위젯)와 단건 조회는 기존 방식을 쓴다.
 */
@Configuration
public class QuerydslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
