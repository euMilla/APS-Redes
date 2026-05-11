package aps.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Locale;

import aps.shared.model.MessageType;
import aps.shared.model.NetworkMessage;
import aps.shared.model.UserProfile;

/**
 * Representa um cliente conectado e gerencia seu ciclo de leitura TCP.
 */
final class ClientHandler implements Runnable {

    private final Socket        socket;
    private final CentralServer server;
    private final String        clientKey;

    private ObjectOutputStream output;
    private ObjectInputStream  input;
    private String   username;
    private String   displayName;
    private String   clientId;
    private String   room   = "";
    private boolean  admin;
    private String   role   = "Membro";
    private String   presence = "Online";
    private List<String> allowedRooms = List.of();
    private byte[]   avatar = new byte[0];
    private long     joinedAt;
    private volatile boolean running = true;

    ClientHandler(Socket socket, CentralServer server) {
        this.socket    = socket;
        this.server    = server;
        this.clientKey = socket.getRemoteSocketAddress() + "-" + System.nanoTime();
    }

    @Override
    public void run() {
        try (socket) {
            socket.setKeepAlive(true);
            output = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.flush();
            input  = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

            Object first = input.readObject();
            if (!(first instanceof NetworkMessage login) || login.getType() != MessageType.LOGIN) {
                send(NetworkMessage.error("Primeira mensagem precisa ser LOGIN."));
                return;
            }

            displayName = normalizeDisplayName(login.getFrom());
            username    = normalizeUsername(login.getAttributes().getOrDefault("username", displayName));
            clientId    = normalizeClientId(login.getAttributes().getOrDefault("clientId", clientKey));
            avatar      = login.getPayload();
            joinedAt    = System.currentTimeMillis();

            if (!server.prepareSession(this, login)) return;
            if (!server.registerClient(this))        return;

            while (running) {
                Object obj = input.readObject();
                if (obj instanceof NetworkMessage message) handle(message);
            }
        } catch (EOFException | SocketException ignored) {
            // Cliente encerrou a conexao ou perdeu rede.
        } catch (IOException | ClassNotFoundException exception) {
            server.logServer("Falha no cliente " + username + ": " + exception.getMessage());
        } finally {
            running = false;
            server.unregisterClient(this);
        }
    }

    synchronized void send(NetworkMessage message) throws IOException {
        if (output == null) return;
        output.writeObject(message);
        output.flush();
        output.reset();
    }

    void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
    }

    // =========================================================================
    // Despacho de mensagens
    // =========================================================================

    private void handle(NetworkMessage message) throws IOException {
        switch (message.getType()) {
            case JOIN_ROOM   -> server.changeRoom(this, normalizeRoom(message.getText()));
            case ROLE_UPDATE -> server.updateUserAccess(this, message);
            case AUTH_UPDATE -> server.updatePasswords(this, message);
            case PRESENCE_UPDATE -> server.updatePresence(this, message.getAttributes().getOrDefault("presence", "Online"));
            case ALERT -> server.broadcastCriticalAlert(this, message);
            case ALERT_ACK -> server.receiveAlertAck(this, message);
            case CHAT -> {
                if (room.isBlank()) {
                    send(NetworkMessage.error("Escolha um canal antes de enviar mensagens."));
                } else {
                    NetworkMessage broadcast = withIdentity(NetworkMessage.chat(displayName, message.getText()));
                    server.broadcastToRoom(room, broadcast);
                    server.respondWithAi(room, displayName, message.getText());
                }
            }
            case IMAGE -> {
                if (room.isBlank()) {
                    send(NetworkMessage.error("Escolha um canal antes de enviar imagens."));
                } else {
                    server.broadcastToRoom(room, withIdentity(NetworkMessage.image(displayName, message.getText(), message.getPayload())));
                }
            }
            case PING -> send(NetworkMessage.builder(MessageType.PONG).from("SISTEMA").build());
            default   -> send(NetworkMessage.error("Tipo de mensagem nao permitido: " + message.getType()));
        }
    }

    // =========================================================================
    // Getters / mutadores internos
    // =========================================================================

    String  getClientKey()   { return clientKey; }
    String  getUsername()    { return username; }
    String  getDisplayName() { return displayName; }
    String  getRoom()        { return room; }
    boolean isAdmin()        { return admin; }
    String  getRole()        { return role; }
    String  getPresence()    { return presence; }
    List<String> getAllowedRooms() { return allowedRooms; }

    boolean canAccessRoom(String room) {
        return admin || allowedRooms.contains(normalizeRoom(room));
    }

    void configureAccess(boolean admin, String role, List<String> allowedRooms) {
        this.admin        = admin;
        this.role         = (role == null || role.isBlank()) ? (admin ? "Administrador" : "Membro") : role;
        this.allowedRooms = allowedRooms == null ? List.of() : List.copyOf(allowedRooms);
    }

    void setRoom(String room) {
        this.room     = (room == null || room.isBlank()) ? "" : normalizeRoom(room);
        this.joinedAt = System.currentTimeMillis();
    }

    void setPresence(String presence) {
        String value = presence == null ? "" : presence.trim();
        if (!value.equalsIgnoreCase("Online") && !value.equalsIgnoreCase("Ausente") && !value.equalsIgnoreCase("Offline")) {
            value = "Online";
        }
        this.presence = value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    UserProfile toUserProfile() {
        return new UserProfile(username, displayName, room, joinedAt, avatar, admin, role, allowedRooms, presence);
    }

    // =========================================================================
    // Utilitarios privados
    // =========================================================================

    private NetworkMessage withIdentity(NetworkMessage message) {
        return NetworkMessage.builder(message.getType())
                .from(message.getFrom())
                .to(message.getTo())
                .text(message.getText())
                .fileId(message.getFileId())
                .fileName(message.getFileName())
                .mimeType(message.getMimeType())
                .fileSize(message.getFileSize())
                .payload(message.getPayload())
                .attribute("username", username)
                .attribute("clientId", clientId)
                .attribute("room",     room)
                .attribute("role",     role)
                .attribute("session",  admin ? "admin" : "member")
                .build();
    }

    private static String normalizeDisplayName(String raw) {
        String n = (raw == null) ? "" : raw.trim().replaceAll("[\\t\\n\\r]", " ");
        if (n.isBlank()) n = "Inspetor";
        return n.substring(0, Math.min(n.length(), 40));
    }

    private static String normalizeUsername(String raw) {
        String n = (raw == null) ? "" : raw.trim().replaceAll("[^\\p{Alnum}_ .-]", "");
        if (n.isBlank()) n = "inspetor";
        return n.substring(0, Math.min(n.length(), 32)).toLowerCase(Locale.ROOT);
    }

    private static String normalizeClientId(String raw) {
        String n = (raw == null) ? "" : raw.trim().replaceAll("[^A-Za-z0-9._-]", "");
        if (n.isBlank()) n = "cliente";
        return n.substring(0, Math.min(n.length(), 80));
    }

    private static String normalizeRoom(String raw) {
        String n = (raw == null) ? "" : raw.trim().replaceAll("[\\t\\n\\r]", " ");
        if (n.isBlank()) n = "Equipe Alfa";
        return n.substring(0, Math.min(n.length(), 40));
    }
}
