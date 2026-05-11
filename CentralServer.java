package aps.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import aps.server.data.ServerDatabase;
import aps.server.data.UserAccessRecord;
import aps.shared.config.AppConfig;
import aps.shared.model.FileMetadata;
import aps.shared.model.MessageType;
import aps.shared.model.NetworkMessage;
import aps.shared.model.UserProfile;
import aps.shared.net.Ports;
import aps.shared.logging.SystemLogger;
import aps.shared.security.PasswordHasher;

/**
 * Servidor central de chat TCP.
 *
 * <p>Correccoes e melhorias em relacao a versao anterior:
 * <ul>
 *   <li>{@code conversationLog} limitado a {@link #MAX_LOG_SIZE} mensagens — sem crescimento ilimitado.</li>
 *   <li>IA responde todas as mensagens de chat com uma resposta curta e contextual.</li>
 *   <li>Mensagem de boas-vindas da IA so ocorre na primeira entrada do usuario no canal.</li>
 *   <li>Metodo {@link #respondWithAi} nao bloqueia a thread de escrita do servidor.</li>
 * </ul>
 */
public final class CentralServer {

    /** Limite de mensagens mantidas em memoria no log global. */
    private static final int MAX_LOG_SIZE = 2_000;

    // =========================================================================
    // Campos
    // =========================================================================

    private final int  chatPort;
    private final int  filePort;
    private final Path storageDir;

    private final ExecutorService clientWorkers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "chat-client-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** Pool dedicado a respostas da IA para nao bloquear o writer do servidor. */
    private final ExecutorService aiWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ia-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, ClientHandler>  clients      = new ConcurrentHashMap<>();
    private final Map<String, UserAccess>     accessByUser = new ConcurrentHashMap<>();

    /** Log global com limite de tamanho. */
    private final CopyOnWriteArrayList<NetworkMessage> conversationLog = new CopyOnWriteArrayList<>();

    private final ServerDatabase database;
    private final FileTransferServer fileTransferServer;
    private final List<String> channels = List.of("Equipe Alfa", "Equipe Beta", "Equipe Gama", "Central Operacional");

    private volatile String adminPasswordHash;
    private volatile String memberPasswordHash;

    private final long virtualJoinedAt = Instant.now().toEpochMilli();
    private final List<VirtualMember> virtualMembers = List.of(
            new VirtualMember("assistente-rede",  "Assistente de Rede", "Suporte",      "Disponivel"),
            new VirtualMember("analista-noc",     "Analista NOC",       "Monitoramento", "Disponivel"),
            new VirtualMember("supervisor-campo", "Supervisor Campo",   "Supervisor",   "Em ronda")
    );

    private volatile boolean running;
    private ServerSocket serverSocket;

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    public CentralServer(int chatPort, int filePort, Path storageDir) {
        this.chatPort   = chatPort;
        this.filePort   = filePort;
        this.storageDir = storageDir;
        SystemLogger.configure(storageDir);
        this.database   = ServerDatabase.open(storageDir);
        loadAuthState();
        loadAccessState();
        this.fileTransferServer = new FileTransferServer(filePort, storageDir, database, this::publishFileNotice);
    }

    public void start() throws IOException {
        fileTransferServer.startAsync();
        running = true;
        try {
            ServerSocket socket = new ServerSocket(chatPort, 50, InetAddress.getByName("0.0.0.0"));
            serverSocket = socket;
            logServer("Central TCP ativa na porta " + chatPort + ". Arquivos na porta " + filePort + ".");
            while (running) {
                Socket clientSocket = socket.accept();
                clientWorkers.execute(new ClientHandler(clientSocket, this));
            }
        } catch (SocketException exception) {
            if (running) throw exception;
        }
    }

    public void shutdown() {
        running = false;
        clients.values().forEach(ClientHandler::close);
        clients.clear();
        clientWorkers.shutdownNow();
        aiWorker.shutdownNow();
        fileTransferServer.stop();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        SystemLogger.close();
    }

    // =========================================================================
    // Registros de clientes
    // =========================================================================

    boolean registerClient(ClientHandler handler) throws IOException {
        clients.put(handler.getClientKey(), handler);
        sendAccessState(handler, "Conectado ao servidor. Escolha um canal para iniciar.", "");
        logServer("Cliente conectado: " + handler.getDisplayName());
        database.audit("LOGIN", handler.getUsername(), handler.getDisplayName() + " conectado como " + (handler.isAdmin() ? "admin" : "membro"));
        publishAdminRoster();
        return true;
    }

    boolean prepareSession(ClientHandler handler, NetworkMessage login) throws IOException {
        String session = login.getAttributes().getOrDefault("session", "member");
        String provided = login.getAttributes().getOrDefault(
                "password",
                login.getAttributes().getOrDefault("adminPassword", "")
        ).trim();

        if ("admin".equalsIgnoreCase(session)) {
            if (!passwordMatches(adminPasswordHash, provided)) {
                database.audit("AUTH_FAIL", handler.getUsername(), "Senha de admin invalida para " + handler.getDisplayName());
                handler.send(NetworkMessage.error("Senha de admin invalida."));
                handler.close();
                return false;
            }
            handler.configureAccess(true, "Administrador", channels);
            return true;
        }

        if (!passwordMatches(memberPasswordHash, provided)) {
            database.audit("AUTH_FAIL", handler.getUsername(), "Senha de membro invalida para " + handler.getDisplayName());
            handler.send(NetworkMessage.error("Senha de membro invalida."));
            handler.close();
            return false;
        }
        UserAccess access = accessByUser.getOrDefault(handler.getUsername(), UserAccess.defaultMember());
        handler.configureAccess(false, access.role(), access.channels());
        return true;
    }

    void unregisterClient(ClientHandler handler) {
        if (handler == null || handler.getUsername() == null || handler.getUsername().isBlank()) return;
        ClientHandler removed = clients.remove(handler.getClientKey());
        if (removed != null) {
            if (!handler.getRoom().isBlank()) {
                broadcastToRoom(handler.getRoom(), NetworkMessage.builder(MessageType.USER_LEFT)
                        .from("ALERTA")
                        .text(handler.getDisplayName() + " saiu de " + handler.getRoom() + ".")
                        .attribute("room", handler.getRoom())
                        .build());
                publishUserList(handler.getRoom());
            }
            logServer("Cliente desconectado: " + handler.getDisplayName());
            database.audit("LOGOUT", handler.getUsername(), handler.getDisplayName() + " desconectado.");
            publishAdminRoster();
        }
    }

    // =========================================================================
    // Logica de canais e acessos
    // =========================================================================

    void changeRoom(ClientHandler handler, String newRoom) throws IOException {
        if (!channels.contains(newRoom)) {
            handler.send(NetworkMessage.error("Canal nao cadastrado: " + newRoom));
            return;
        }
        if (!handler.canAccessRoom(newRoom)) {
            handler.send(NetworkMessage.error("Voce nao tem acesso ao canal " + newRoom + "."));
            sendAccessState(handler, "Acesso negado ao canal " + newRoom + ".", "");
            return;
        }

        String previousRoom = handler.getRoom();
        if (!previousRoom.isBlank() && !previousRoom.equals(newRoom)) {
            broadcastToRoom(previousRoom, NetworkMessage.builder(MessageType.USER_LEFT)
                    .from("ALERTA")
                    .text(handler.getDisplayName() + " saiu de " + previousRoom + ".")
                    .attribute("room", previousRoom)
                    .build());
            handler.setRoom("");
            publishUserList(previousRoom);
        }

        handler.setRoom(newRoom);
        sendAccessState(handler, "Canal ativo: " + newRoom, newRoom);
        broadcastToRoom(newRoom, NetworkMessage.builder(MessageType.USER_JOINED)
                .from("ALERTA")
                .text(handler.getDisplayName() + " entrou em " + newRoom + ".")
                .attribute("room", newRoom)
                .build());
        publishUserList(newRoom);
        sendAiWelcome(handler, newRoom);
        publishAdminRoster();
    }

    void updateUserAccess(ClientHandler author, NetworkMessage request) throws IOException {
        if (!author.isAdmin()) {
            author.send(NetworkMessage.error("Apenas admins podem alterar cargos e acessos."));
            return;
        }
        String targetUsername = request.getAttributes().getOrDefault("target", "").trim().toLowerCase();
        if (targetUsername.isBlank()) {
            author.send(NetworkMessage.error("Selecione um membro para atualizar."));
            return;
        }
        ClientHandler target = findConnectedMember(targetUsername);
        if (target != null && target.isAdmin()) {
            author.send(NetworkMessage.error("Admins nao precisam de permissao de canal."));
            return;
        }
        String       role             = sanitizeRole(request.getAttributes().getOrDefault("role", "Membro"));
        List<String> selectedChannels = parseChannels(request.getAttributes().getOrDefault("channels", ""));
        if (selectedChannels.isEmpty()) {
            selectedChannels = List.of("Equipe Alfa");
        }
        accessByUser.put(targetUsername, new UserAccess(role, selectedChannels));
        database.saveUserAccess(targetUsername, role, selectedChannels);
        if (target != null) applyAccessToConnectedMember(target, role, selectedChannels);
        author.send(NetworkMessage.system("Cargo atualizado: " + targetUsername + " -> " + role));
        database.audit("PERMISSAO", author.getUsername(), "Atualizou " + targetUsername + " para " + role + " / " + String.join(", ", selectedChannels));
        publishAdminRoster();
    }

    void updatePasswords(ClientHandler author, NetworkMessage request) throws IOException {
        if (!author.isAdmin()) {
            author.send(NetworkMessage.error("Apenas administradores podem alterar senhas do sistema."));
            database.audit("AUTH_DENIED", author.getUsername(), "Tentativa de alterar senha sem perfil admin.");
            return;
        }

        String rawMember = request.getAttributes().getOrDefault("memberPassword", "");
        String rawAdmin  = request.getAttributes().getOrDefault("adminPassword", "");

        String nextMember = sanitizePassword(rawMember);
        String nextAdmin  = sanitizePassword(rawAdmin);

        boolean hasMemberChange = rawMember != null && !rawMember.trim().isBlank();
        boolean hasAdminChange  = rawAdmin  != null && !rawAdmin.trim().isBlank();

        if (!hasMemberChange && !hasAdminChange) {
            author.send(NetworkMessage.error("Preencha pelo menos uma nova senha."));
            return;
        }

        if (hasMemberChange && !isStrongPassword(nextMember)) {
            author.send(NetworkMessage.error("A senha de membro precisa ter pelo menos 8 caracteres e conter letras e numeros."));
            return;
        }

        if (hasAdminChange && !isStrongPassword(nextAdmin)) {
            author.send(NetworkMessage.error("A senha de admin precisa ter pelo menos 8 caracteres e conter letras e numeros."));
            return;
        }

        if (hasMemberChange) {
            memberPasswordHash = PasswordHasher.hash(nextMember);
            database.savePasswordHash("memberPassword", memberPasswordHash);
        }
        if (hasAdminChange) {
            adminPasswordHash = PasswordHasher.hash(nextAdmin);
            database.savePasswordHash("adminPassword", adminPasswordHash);
        }

        if (hasMemberChange || hasAdminChange) {
            String msg = hasMemberChange && hasAdminChange ? "Ambas as senhas atualizadas no servidor." :
                         hasMemberChange ? "Senha de membro atualizada no servidor." :
                         "Senha de admin atualizada no servidor.";
            author.send(authStateMessage(msg, author.isAdmin()));
            String logMsg = author.isAdmin() ? "pelo admin " : "pelo membro ";
            logServer("Senhas atualizadas " + logMsg + author.getDisplayName() + ".");
            database.audit("SENHA", author.getUsername(), msg);
        }
    }

    void updatePresence(ClientHandler author, String presence) {
        author.setPresence(presence);
        database.audit("PRESENCA", author.getUsername(), author.getDisplayName() + " alterou status para " + author.getPresence());
        if (!author.getRoom().isBlank()) {
            publishUserList(author.getRoom());
        }
        publishAdminRoster();
    }

    void broadcastCriticalAlert(ClientHandler author, NetworkMessage request) {
        String text = request.getText() == null ? "" : request.getText().trim();
        if (text.isBlank()) {
            try {
                author.send(NetworkMessage.error("Escreva a mensagem do alerta critico."));
            } catch (IOException exception) {
                author.close();
            }
            return;
        }
        String alertId = request.getAttributes().getOrDefault("alertId", "");
        if (alertId.isBlank()) {
            alertId = "alert-" + Instant.now().toEpochMilli() + "-" + Math.abs(author.getClientKey().hashCode());
        }
        String scope = author.getRoom().isBlank() ? "rede inteira" : author.getRoom();
        NetworkMessage alert = NetworkMessage.builder(MessageType.ALERT)
                .from("ALERTA CRITICO")
                .text(author.getDisplayName() + " emitiu alerta para " + scope + ": " + text)
                .attribute("critical", "true")
                .attribute("alertId", alertId)
                .attribute("room", author.getRoom())
                .attribute("author", author.getDisplayName())
                .build();
        broadcast(alert);
        database.audit("ALERTA_CRITICO", author.getUsername(), text + " | id=" + alertId);
        logServer("Alerta critico emitido por " + author.getDisplayName() + ": " + text);
    }

    void receiveAlertAck(ClientHandler author, NetworkMessage request) {
        String alertId = request.getAttributes().getOrDefault("alertId", "").trim();
        NetworkMessage ack = NetworkMessage.builder(MessageType.SYSTEM)
                .from("CONFIRMACAO")
                .text(author.getDisplayName() + " confirmou recebimento do alerta" + (alertId.isBlank() ? "." : " " + alertId + "."))
                .attribute("alertAck", "true")
                .attribute("alertId", alertId)
                .attribute("room", author.getRoom())
                .build();
        broadcast(ack);
        database.audit("CONFIRMACAO_ALERTA", author.getUsername(), "Confirmou alerta " + alertId);
    }

    // =========================================================================
    // IA do canal
    // =========================================================================

    /**
     * Responde com a IA de forma assincrona para qualquer mensagem enviada no canal.
     */
    void respondWithAi(String room, String author, String text) {
        if (room == null || room.isBlank() || text == null || text.isBlank()) return;

        aiWorker.execute(() -> {
            NetworkMessage reply = NetworkMessage.builder(MessageType.CHAT)
                    .from("Assistente de Rede")
                    .text(aiReply(room, author, text))
                    .attribute("username", "assistente-rede")
                    .attribute("room",     room)
                    .attribute("role",     "Suporte")
                    .build();
            broadcastToRoom(room, reply);
            logServer("IA respondeu em " + room + " para " + author + ".");
        });
    }

    // =========================================================================
    // Broadcast / envio
    // =========================================================================

    public void broadcast(NetworkMessage message) {
        appendLog(message);
        clients.forEach((key, handler) -> {
            try { handler.send(message); }
            catch (IOException ex) { handler.close(); unregisterClient(handler); }
        });
    }

    public void broadcastToRoom(String room, NetworkMessage message) {
        appendLog(message);
        clients.forEach((key, handler) -> {
            if (!handler.getRoom().equals(room)) return;
            try { handler.send(message); }
            catch (IOException ex) { handler.close(); unregisterClient(handler); }
        });
    }

    public void sendSystemMessage(String text) {
        broadcast(NetworkMessage.builder(MessageType.ALERT)
                .from("CONSOLE").text(text).attribute("source", "console").build());
    }

    public void sendMulticastAlert(String text) throws IOException {
        String payload = "ALERT|" + Instant.now().toEpochMilli() + "|" + text;
        byte[] bytes   = payload.getBytes(StandardCharsets.UTF_8);
        InetAddress group = InetAddress.getByName(Ports.MULTICAST_GROUP);
        try (MulticastSocket ms = new MulticastSocket()) {
            ms.setTimeToLive(8);
            ms.send(new DatagramPacket(bytes, bytes.length, group, Ports.MULTICAST_PORT));
        }
        logServer("Multicast enviado: " + text);
    }

    // =========================================================================
    // Consultas publicas
    // =========================================================================

    public List<String> getConnectedUsers() {
        List<String> users = new ArrayList<>(clients.values().stream()
                .map(h -> h.getRoom() + " / " + h.getDisplayName())
                .sorted().toList());
        virtualProfilesForAdmin().stream()
                .map(p -> p.getRoom() + " / " + p.getDisplayName())
                .forEach(users::add);
        users.sort(String::compareToIgnoreCase);
        return users;
    }

    public List<NetworkMessage> snapshotLog() {
        return List.copyOf(conversationLog);
    }

    void logServer(String text) {
        System.out.println("[" + Instant.now() + "] " + text);
        SystemLogger.info(text);
    }

    // =========================================================================
    // Privados
    // =========================================================================

    /** Adiciona ao log e descarta os mais antigos se o limite foi atingido. */
    private void appendLog(NetworkMessage message) {
        conversationLog.add(message);
        while (conversationLog.size() > MAX_LOG_SIZE) {
            conversationLog.remove(0);
        }
    }

    private void publishFileNotice(FileMetadata metadata) {
        NetworkMessage notice = NetworkMessage.builder(MessageType.FILE_NOTICE)
                .from("ARQUIVOS")
                .text(metadata.getOwner() + " enviou " + metadata.getFileName())
                .fileId(metadata.getId())
                .fileName(metadata.getFileName())
                .fileSize(metadata.getSize())
                .attribute("owner", metadata.getOwner())
                .attribute("room",  metadata.getRoom())
                .build();
        database.audit("RELATORIO", metadata.getOwner(), "Relatorio publicado: " + metadata.getFileName() + " em " + metadata.getRoom());
        // Envia para todos no canal
        broadcastToRoom(metadata.getRoom(), notice);
        // Envia também para admins (que não estão necessariamente no canal)
        clients.values().stream().filter(ClientHandler::isAdmin).forEach(handler -> {
            try { 
                if (!handler.getRoom().equals(metadata.getRoom())) {
                    handler.send(notice);
                }
            }
            catch (IOException ex) { handler.close(); unregisterClient(handler); }
        });
    }

    private String aiReply(String room, String author, String text) {
        String norm = text.toLowerCase();
        String name = (author == null || author.isBlank()) ? "usuario" : author;
        if ((norm.contains("quantos") || norm.contains("qtd") || norm.contains("total"))
                && (norm.contains("relatorio") || norm.contains("arquivo"))) {
            boolean today = norm.contains("hoje") || norm.contains("dia");
            long roomCount = today ? reportCountToday(room) : reportCount(room);
            long totalCount = today ? reportCountToday("") : reportCount("");
            String period = today ? " hoje" : "";
            if (room == null || room.isBlank()) {
                return "Foram feitos " + totalCount + plural(totalCount, " relatorio", " relatorios") + period + " no servidor.";
            }
            return "Foram feitos " + roomCount + plural(roomCount, " relatorio", " relatorios") + period
                    + " em " + room + ". No total do servidor: " + totalCount + ".";
        }
        if ((norm.contains("ultimo") || norm.contains("recente")) && (norm.contains("relatorio") || norm.contains("arquivo"))) {
            return latestReportReply(room);
        }
        if (norm.contains("usuario") || norm.contains("online") || norm.contains("equipe")) {
            long users = clients.values().stream().filter(client -> client.getRoom().equals(room)).count();
            return "Agora ha " + users + plural(users, " usuario online", " usuarios online") + " em " + room + ".";
        }
        if (norm.contains("ocorrencia") || norm.contains("poluicao") || norm.contains("tiete") || norm.contains("trecho")) {
            return "Registre ocorrencias com industria, trecho do Rio Tiete, tipo e gravidade. Use alerta critico quando houver risco imediato.";
        }
        if (norm.contains("rascunho") || norm.contains("validado") || norm.contains("workflow")) {
            return "O workflow recomendado e: rascunho local, envio TCP do relatorio e validacao pelo administrador com hash SHA-256.";
        }
        if (norm.contains("alerta critico") || norm.contains("confirmacao") || norm.contains("confirmar")) {
            return "Alertas criticos sao enviados para toda a rede e cada cliente confirma recebimento ao abrir o aviso.";
        }
        if (norm.contains("mapa") || norm.contains("salesopolis") || norm.contains("grande sao paulo")) {
            return "O mapa por trechos ajuda a mostrar da nascente em Salesopolis ate a Grande Sao Paulo onde ha mais ocorrencias registradas.";
        }
        if (norm.contains("status") || norm.contains("situacao") || norm.contains("painel")) {
            return "Status de " + room + ": " + reportCountToday(room) + plural(reportCountToday(room), " relatorio", " relatorios")
                    + " hoje, " + clients.values().stream().filter(client -> client.getRoom().equals(room)).count()
                    + " usuarios online e canal operacional.";
        }
        if (norm.contains("ola") || norm.contains("oi") || norm.contains("bom dia")
                || norm.contains("boa tarde") || norm.contains("boa noite"))
            return "Ola, " + name + ". Estou acompanhando o canal.";
        if (norm.contains("relatorio") || norm.contains("upload") || norm.contains("arquivo"))
            return "Relatorios ficam vinculados ao canal atual. Envie, atualize a lista e o admin acompanha tudo no painel.";
        if (norm.contains("canal") || norm.contains("acesso") || norm.contains("cargo"))
            return "Canais e cargos ficam na lateral. Admin libera acesso na aba Cargos.";
        if (norm.contains("alerta") || norm.contains("historico") || norm.contains("log"))
            return "Alertas aparecem no Historico. No servidor use /alert ou /critical.";
        if (norm.contains("senha") || norm.contains("login"))
            return "Use membro123 para membro ou admin123 para admin, a menos que o admin tenha trocado.";
        if (norm.contains("webcam") || norm.contains("foto") || norm.contains("camera"))
            return "Clique em Webcam no chat para capturar e enviar uma foto do local de vistoria.";
        if (norm.contains("ajuda") || norm.contains("help") || norm.contains("comandos"))
            return "Posso responder sobre total de relatorios, relatorios de hoje, ultimo relatorio, usuarios online, canais, historico, cargos e login.";
        return "Entendi, " + name + ". Estou acompanhando e posso apoiar com proximos passos, relatorios, canais ou status da operacao.";
    }

    private long reportCount(String room) {
        return reportsFor(room).size();
    }

    private long reportCountToday(String room) {
        LocalDate today = LocalDate.now();
        return reportsFor(room).stream()
                .filter(metadata -> Instant.ofEpochMilli(metadata.getUploadedAt())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .equals(today))
                .count();
    }

    private List<FileMetadata> reportsFor(String room) {
        String targetRoom = room == null ? "" : room.trim();
        return fileTransferServer.snapshotMetadata().stream()
                .filter(metadata -> targetRoom.isBlank() || metadata.getRoom().equalsIgnoreCase(targetRoom))
                .toList();
    }

    private String latestReportReply(String room) {
        return reportsFor(room).stream()
                .max(Comparator.comparingLong(FileMetadata::getUploadedAt))
                .map(metadata -> "Ultimo relatorio em " + metadata.getRoom() + ": " + metadata.getFileName()
                        + ", enviado por " + metadata.getOwner() + ".")
                .orElse("Ainda nao encontrei relatorios salvos para esse escopo.");
    }

    private static String plural(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    private void sendAiWelcome(ClientHandler handler, String room) throws IOException {
        NetworkMessage welcome = NetworkMessage.builder(MessageType.CHAT)
                .from("Assistente de Rede")
                .text("Painel pronto para " + room + ". Chame por 'ia' se precisar de ajuda.")
                .attribute("username", "assistente-rede")
                .attribute("room",     room)
                .attribute("role",     "Suporte")
                .build();
        handler.send(welcome);
    }

    private void publishUserList(String room) {
        List<UserProfile> users = new ArrayList<>();
        users.addAll(clients.values().stream()
                .filter(h -> h.getRoom().equals(room))
                .map(ClientHandler::toUserProfile).toList());
        users.addAll(virtualProfilesForRoom(room));
        users.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));

        broadcastToRoom(room, NetworkMessage.builder(MessageType.USER_LIST)
                .from("SISTEMA")
                .text("Usuarios em " + room)
                .attribute("scope", "room")
                .attribute("room",  room)
                .users(users)
                .build());
    }

    private void applyAccessToConnectedMember(ClientHandler target, String role, List<String> selectedChannels) throws IOException {
        String previousRoom = target.getRoom();
        target.configureAccess(false, role, selectedChannels);
        if (!previousRoom.isBlank() && !target.canAccessRoom(previousRoom)) {
            broadcastToRoom(previousRoom, NetworkMessage.builder(MessageType.USER_LEFT)
                    .from("ALERTA")
                    .text(target.getDisplayName() + " perdeu acesso a " + previousRoom + ".")
                    .attribute("room", previousRoom)
                    .build());
            target.setRoom("");
            publishUserList(previousRoom);
        }
        sendAccessState(target, "Permissoes atualizadas pelo admin.", target.getRoom());
    }

    private void sendAccessState(ClientHandler handler, String text, String room) throws IOException {
        NetworkMessage.Builder builder = NetworkMessage.builder(MessageType.LOGIN_ACCEPTED)
                .from("SISTEMA")
                .text(text)
                .attribute("channels", String.join("|", channelsFor(handler)))
                .attribute("session",  handler.isAdmin() ? "admin" : "member")
                .attribute("role",     handler.getRole());
        if (handler.isAdmin()) {
            builder.attribute("canChangeAdminPassword", "true");
        }
        if (room != null && !room.isBlank()) builder.attribute("room", room);
        handler.send(builder.build());
    }

    private List<String> channelsFor(ClientHandler handler) {
        if (handler.isAdmin()) return channels;
        return handler.getAllowedRooms().stream().filter(channels::contains).distinct().toList();
    }

    private void publishAdminRoster() {
        List<UserProfile> users = new ArrayList<>(clients.values().stream()
                .map(ClientHandler::toUserProfile).toList());
        users.addAll(virtualProfilesForAdmin());
        users.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        NetworkMessage roster = NetworkMessage.builder(MessageType.USER_LIST)
                .from("SISTEMA").text("Perfis conectados").attribute("scope", "admin").users(users).build();
        clients.values().stream().filter(ClientHandler::isAdmin).forEach(handler -> {
            try { handler.send(roster); }
            catch (IOException ex) { handler.close(); unregisterClient(handler); }
        });
    }

    private ClientHandler findConnectedMember(String username) {
        return clients.values().stream()
                .filter(h -> h.getUsername().equalsIgnoreCase(username))
                .findFirst().orElse(null);
    }

    private List<UserProfile> virtualProfilesForRoom(String room) {
        return virtualMembers.stream()
                .map(m -> virtualProfile(m, room))
                .filter(p -> p.getAllowedRooms().contains(room))
                .toList();
    }

    private List<UserProfile> virtualProfilesForAdmin() {
        return virtualMembers.stream().map(m -> virtualProfile(m, "Todos os canais")).toList();
    }

    private UserProfile virtualProfile(VirtualMember member, String room) {
        UserAccess access = accessByUser.getOrDefault(member.username(), new UserAccess(member.role(), channels));
        return new UserProfile(member.username(), member.displayName(), room,
                virtualJoinedAt, new byte[0], false, access.role(), access.channels(), member.presence());
    }

    private List<String> parseChannels(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim).filter(channels::contains).distinct().toList();
    }

    // =========================================================================
    // Auth / persistencia
    // =========================================================================

    private void loadAuthState() {
        adminPasswordHash  = database.loadPasswordHash("adminPassword", systemPassword("APS_ADMIN_PASSWORD",  "admin123"));
        memberPasswordHash = database.loadPasswordHash("memberPassword", systemPassword("APS_MEMBER_PASSWORD", "membro123"));
    }

    private void loadAccessState() {
        for (UserAccessRecord record : database.loadUserAccess().values()) {
            accessByUser.put(record.username(), new UserAccess(record.role(), record.channels()));
        }
    }

    private NetworkMessage authStateMessage(String text, boolean includeAdminPassword) {
        NetworkMessage.Builder builder = NetworkMessage.builder(MessageType.AUTH_UPDATE)
                .from("SISTEMA").text(text);
        if (includeAdminPassword) {
            builder.attribute("canChangeAdminPassword", "true");
        }
        return builder.build();
    }

    // =========================================================================
    // Utilitarios estaticos
    // =========================================================================

    private static String systemPassword(String envKey, String fallback) {
        return AppConfig.current().get(envKey, fallback);
    }

    private static boolean passwordMatches(String expected, String provided) {
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return PasswordHasher.verify(provided, expected);
    }

    private static String sanitizeRole(String raw) {
        String role = (raw == null) ? "" : raw.trim().replaceAll("[\\t\\n\\r]", " ");
        if (role.isBlank()) role = "Membro";
        return role.substring(0, Math.min(role.length(), 32));
    }

    private static String sanitizePassword(String raw) {
        String pw = (raw == null) ? "" : raw.trim().replaceAll("[\\t\\n\\r]", "");
        return pw.substring(0, Math.min(pw.length(), 128));
    }

    private static boolean isStrongPassword(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }

    // =========================================================================
    // Records internos
    // =========================================================================

    record UserAccess(String role, List<String> channels) {
        UserAccess {
            role     = sanitizeRole(role);
            channels = (channels == null || channels.isEmpty()) ? List.of("Equipe Alfa") : List.copyOf(channels);
        }
        static UserAccess defaultMember() { return new UserAccess("Membro", List.of("Equipe Alfa")); }
    }

    private record VirtualMember(String username, String displayName, String role, String presence) {}
}
