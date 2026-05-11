package aps.server.data;

import java.util.List;

public record UserAccessRecord(String username, String role, List<String> channels) {
    public UserAccessRecord {
        username = username == null ? "" : username.trim().toLowerCase();
        role = role == null || role.isBlank() ? "Membro" : role.trim();
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
