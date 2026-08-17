package cn.bugstack.ai.domain.identity.adapter;

import cn.bugstack.ai.domain.identity.model.UserAccount;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IIdentityRepository {
    Optional<UserAccount> findByUsername(String username);
    Optional<UserAccount> findById(UUID id);
    void updateLastLogin(UUID userId, Instant at);
    void saveRefreshToken(UUID userId, String tokenHash, String jwtId, Instant expiresAt,
                          String userAgent, String ipAddress);
    Optional<UserAccount> findByRefreshTokenHash(String tokenHash, Instant now);
    void revokeRefreshToken(String tokenHash, Instant revokedAt);
}
