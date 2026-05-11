package aps.shared.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla o acesso aos canais por usuario e gerencia as permissoes.
 */
public final class ChannelAccessControl {

    public static final String[] ALL_CHANNELS = {
        "Equipe Alfa", "Equipe Beta", "Equipe Gama", "Central Operacional"
    };

    private static final Map<String, Set<String>> userChannelAccess = new ConcurrentHashMap<>();
    private static final Map<String, UserRole>    userRoles         = new ConcurrentHashMap<>();

    private ChannelAccessControl() {}

    public static void setUserRole(String username, UserRole role) {
        userRoles.put(username, role);
        if (role.isAdmin()) {
            userChannelAccess.put(username, new HashSet<>(Arrays.asList(ALL_CHANNELS)));
        }
    }

    public static UserRole getUserRole(String username) {
        return userRoles.getOrDefault(username, UserRole.MEMBER);
    }

    public static void grantChannelAccess(String username, String channelName) {
        userChannelAccess.computeIfAbsent(username, k -> new HashSet<>()).add(channelName);
    }

    public static void revokeChannelAccess(String username, String channelName) {
        Set<String> channels = userChannelAccess.get(username);
        if (channels != null) channels.remove(channelName);
    }

    public static boolean hasChannelAccess(String username, String channelName) {
        if (getUserRole(username).isAdmin()) return true;
        Set<String> channels = userChannelAccess.get(username);
        return channels != null && channels.contains(channelName);
    }

    public static Set<String> getUserAccessibleChannels(String username) {
        if (getUserRole(username).isAdmin()) return new HashSet<>(Arrays.asList(ALL_CHANNELS));
        return userChannelAccess.getOrDefault(username, new HashSet<>());
    }

    public static void removeUser(String username) {
        userRoles.remove(username);
        userChannelAccess.remove(username);
    }

    public static void clear() {
        userRoles.clear();
        userChannelAccess.clear();
    }
}