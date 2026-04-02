package com.shadowfit.repository;

import com.shadowfit.model.member.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    void deleteByUserId(String userId);
    void deleteByToken(String refreshToken);
}
