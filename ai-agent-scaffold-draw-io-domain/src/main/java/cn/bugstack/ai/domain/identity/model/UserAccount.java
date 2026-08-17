package cn.bugstack.ai.domain.identity.model;

import java.util.List;
import java.util.UUID;

public record UserAccount(UUID id, String username, String passwordHash, String displayName,
                          String status, List<String> roles) {
    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
