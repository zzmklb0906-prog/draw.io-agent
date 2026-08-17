package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.identity.adapter.IIdentityRepository;
import cn.bugstack.ai.domain.identity.model.UserAccount;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdentityRepository implements IIdentityRepository {
    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("select * from app_user where username=?", this::map, username).stream().findFirst();
    }

    @Override public Optional<UserAccount> findById(UUID id) {
        return jdbc.query("select * from app_user where id=?", this::map, id).stream().findFirst();
    }

    @Override public void updateLastLogin(UUID userId, Instant at) {
        jdbc.update("update app_user set last_login_at=?,updated_at=now(),version=version+1 where id=?", Timestamp.from(at), userId);
    }

    @Override public void saveRefreshToken(UUID userId, String tokenHash, String jwtId, Instant expiresAt, String userAgent, String ipAddress) {
        jdbc.update("insert into auth_refresh_token(user_id,token_hash,jwt_id,expires_at,user_agent,ip_address) values (?,?,?,?,?,?)",
                userId, tokenHash, jwtId, Timestamp.from(expiresAt), userAgent, ipAddress);
    }

    @Override public Optional<UserAccount> findByRefreshTokenHash(String tokenHash, Instant now) {
        return jdbc.query("select u.* from app_user u join auth_refresh_token t on t.user_id=u.id " +
                        "where t.token_hash=? and t.revoked_at is null and t.expires_at>?", this::map,
                tokenHash, Timestamp.from(now)).stream().findFirst();
    }

    @Override public void revokeRefreshToken(String tokenHash, Instant revokedAt) {
        jdbc.update("update auth_refresh_token set revoked_at=? where token_hash=? and revoked_at is null", Timestamp.from(revokedAt), tokenHash);
    }

    private UserAccount map(ResultSet rs, int row) throws SQLException {
        List<String> roles = JSON.parseArray(rs.getString("roles"), String.class);
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("display_name"), rs.getString("status"), roles);
    }
}
