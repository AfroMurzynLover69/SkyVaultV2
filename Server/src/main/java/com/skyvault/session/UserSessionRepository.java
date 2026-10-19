package com.skyvault.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Modifying
    @Transactional
    @Query("delete from UserSession s where s.expiresAt < :now")
    int deleteExpired(OffsetDateTime now);

    @Modifying
    @Transactional
    @Query("delete from UserSession s where s.userId = :userId")
    int deleteByUserId(Long userId);
}
