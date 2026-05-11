package aps.shared.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public final class UserProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 2026050603L;

    private final String username;
    private final String displayName;
    private final String room;
    private final long joinedAt;
    private final byte[] avatar;
    private final boolean admin;
    private final String role;
    private final List<String> allowedRooms;
    private final String presence;

    public UserProfile(String username, String displayName, String room, long joinedAt, byte[] avatar) {
        this(username, displayName, room, joinedAt, avatar, false, "Membro", List.of());
    }

    public UserProfile(String username, String displayName, String room, long joinedAt, byte[] avatar,
                       boolean admin, String role, List<String> allowedRooms) {
        this(username, displayName, room, joinedAt, avatar, admin, role, allowedRooms, "Online");
    }

    public UserProfile(String username, String displayName, String room, long joinedAt, byte[] avatar,
                       boolean admin, String role, List<String> allowedRooms, String presence) {
        this.username     = username == null ? "" : username;
        this.displayName  = (displayName == null || displayName.isBlank()) ? this.username : displayName;
        this.room         = (room == null || room.isBlank()) ? "Sem canal" : room;
        this.joinedAt     = joinedAt;
        this.avatar       = avatar == null ? new byte[0] : avatar.clone();
        this.admin        = admin;
        this.role         = (role == null || role.isBlank()) ? (admin ? "Administrador" : "Membro") : role;
        this.allowedRooms = allowedRooms == null ? List.of() : List.copyOf(allowedRooms);
        this.presence     = (presence == null || presence.isBlank()) ? "Online" : presence;
    }

    public String       getUsername()     { return username; }
    public String       getDisplayName()  { return displayName; }
    public String       getRoom()         { return room; }
    public long         getJoinedAt()     { return joinedAt; }
    public byte[]       getAvatar()       { return avatar.clone(); }
    public boolean      isAdmin()         { return admin; }
    public String       getRole()         { return role; }
    public List<String> getAllowedRooms() { return allowedRooms; }
    public String       getPresence()     { return presence; }
}