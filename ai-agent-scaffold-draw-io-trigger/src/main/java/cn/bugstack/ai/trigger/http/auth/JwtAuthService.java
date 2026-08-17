package cn.bugstack.ai.trigger.http.auth;

import cn.bugstack.ai.domain.identity.adapter.IIdentityRepository;
import cn.bugstack.ai.domain.identity.model.UserAccount;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import cn.bugstack.ai.types.exception.AppException;

@Service
public class JwtAuthService {
    public record Tokens(String username, String displayName, String accessToken, long accessExpiresAt,
                         String refreshToken, long refreshExpiresAt) {}
    public record Principal(UUID id, String username, List<String> roles, String jwtId, long expiresAt) {}

    private final IIdentityRepository repository;
    private final StringRedisTemplate redis;
    private final Algorithm algorithm;
    private final long accessSeconds;
    private final long refreshSeconds;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public JwtAuthService(IIdentityRepository repository, StringRedisTemplate redis,
                          @Value("${ai.agent.auth.jwt-secret}") String secret,
                          @Value("${ai.agent.auth.access-token-seconds:1800}") long accessSeconds,
                          @Value("${ai.agent.auth.refresh-token-seconds:2592000}") long refreshSeconds) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("ai.agent.auth.jwt-secret 至少需要 32 个字符");
        this.repository = repository;
        this.redis = redis;
        this.algorithm = Algorithm.HMAC256(secret);
        this.accessSeconds = Math.max(300, accessSeconds);
        this.refreshSeconds = Math.max(3600, refreshSeconds);
    }

    public Optional<Tokens> login(String username, String password, String userAgent, String ip) {
        String normalized=username==null?"":username.trim().toLowerCase();String rateKey="auth:login-fail:"+hash(normalized+"|"+ip);
        String failures=redis.opsForValue().get(rateKey);if(failures!=null&&Integer.parseInt(failures)>=5)throw new AppException("AUTH_RATE_LIMIT","登录失败次数过多，请 15 分钟后重试");
        Optional<Tokens> result=repository.findByUsername(username).filter(UserAccount::active)
                .filter(user -> passwordEncoder.matches(password == null ? "" : password, user.passwordHash()))
                .map(user -> { repository.updateLastLogin(user.id(), Instant.now()); return issue(user, userAgent, ip); });
        if(result.isPresent())redis.delete(rateKey);else{Long count=redis.opsForValue().increment(rateKey);if(count!=null&&count==1)redis.expire(rateKey,Duration.ofMinutes(15));}
        return result;
    }

    public Optional<Tokens> refresh(String rawToken, String userAgent, String ip) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = hash(rawToken);
        return repository.findByRefreshTokenHash(hash, Instant.now()).filter(UserAccount::active).map(user -> {
            repository.revokeRefreshToken(hash, Instant.now());
            return issue(user, userAgent, ip);
        });
    }

    public Optional<Principal> authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return Optional.empty();
        try {
            DecodedJWT jwt = JWT.require(algorithm).withIssuer("agent-platform").withClaim("typ", "access").build().verify(accessToken);
            if (Boolean.TRUE.equals(redis.hasKey("auth:deny:" + jwt.getId()))) return Optional.empty();
            List<String> roles=jwt.getClaim("roles").asList(String.class);return Optional.of(new Principal(UUID.fromString(jwt.getSubject()), jwt.getClaim("username").asString(),roles==null?List.of():List.copyOf(roles),
                    jwt.getId(), jwt.getExpiresAtAsInstant().toEpochMilli()));
        } catch (Exception ignored) { return Optional.empty(); }
    }

    public void logout(String accessToken, String refreshToken) {
        authenticate(accessToken).ifPresent(p -> {
            long ttl = Math.max(1, p.expiresAt() - System.currentTimeMillis());
            redis.opsForValue().set("auth:deny:" + p.jwtId(), "1", Duration.ofMillis(ttl));
        });
        if (refreshToken != null && !refreshToken.isBlank()) repository.revokeRefreshToken(hash(refreshToken), Instant.now());
    }

    private Tokens issue(UserAccount user, String userAgent, String ip) {
        Instant now = Instant.now();
        Instant accessExpiry = now.plusSeconds(accessSeconds);
        String jwtId = UUID.randomUUID().toString();
        String access = JWT.create().withIssuer("agent-platform").withSubject(user.id().toString())
                .withJWTId(jwtId).withClaim("typ", "access").withClaim("username", user.username())
                .withClaim("roles", user.roles()).withIssuedAt(now).withExpiresAt(accessExpiry).sign(algorithm);
        byte[] bytes = new byte[48]; random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant refreshExpiry = now.plusSeconds(refreshSeconds);
        repository.saveRefreshToken(user.id(), hash(refresh), UUID.randomUUID().toString(), refreshExpiry, userAgent, ip);
        return new Tokens(user.username(), user.displayName(), access, accessExpiry.toEpochMilli(), refresh, refreshExpiry.toEpochMilli());
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
