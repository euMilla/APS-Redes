package aps.client.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import aps.shared.model.FileMetadata;
import aps.shared.model.MessageType;
import aps.shared.model.NetworkMessage;

/**
 * Gerencia a conexao TCP com o servidor.
 *
 * <p>Todas as operacoes de I/O ficam fora da thread JavaFX. O controller
 * recebe callbacks via {@link javafx.application.Platform#runLater}.
 *
 * <p>Melhorias em relacao a versao anterior:
 * <ul>
 *   <li>Backoff exponencial na reconexao (1 s, 2 s, 4 s, 8 s, 16 s).</li>
 *   <li>Prefixo de reconexao notificado ao usuario.</li>
 *   <li>Timeout de conexao configuravel.</li>
 * </ul>
 */
public final class NetworkClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long MAX_UPLOAD_SIZE = 100L * 1024L * 1024L;
    private static final List<String> ALLOWED_UPLOAD_EXTENSIONS = List.of(".txt", ".pdf", ".doc", ".docx", ".csv", ".log", ".md");

    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "client-connection"));
    private final ExecutorService writerExecutor     = Executors.newSingleThreadExecutor(r -> daemon(r, "client-writer"));
    private final ExecutorService transferExecutor   = Executors.newFixedThreadPool(2, r -> daemon(r, "client-transfer"));

    private final List<Consumer<NetworkMessage>>     messageListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Boolean, String>>  stateListeners   = new CopyOnWriteArrayList<>();
    private final Object writerLock = new Object();

    private final AtomicBoolean connected      = new AtomicBoolean(false);
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);

    private volatile Socket           socket;
    private volatile ObjectOutputStream output;
    private volatile ObjectInputStream  input;
    private volatile String  username;
    private volatile String  displayName;
    private final String     clientId = UUID.randomUUID().toString();
    private volatile String  room;
    private volatile boolean adminSession;
    private volatile String  sessionPassword = "";
    private volatile byte[]  avatar          = new byte[0];
    private volatile String  host;
    private volatile int     chatPort;
    private volatile int     filePort;

    // =========================================================================
    // API publica
    // =========================================================================

    public CompletableFuture<Void> connect(String displayName, String host, int chatPort, int filePort,
                                            byte[] avatar, boolean adminSession, String sessionPassword) {
        this.displayName     = sanitizeDisplayName(displayName);
        this.username        = sanitizeUsername(this.displayName);
        this.room            = "";
        this.adminSession    = adminSession;
        this.sessionPassword = sessionPassword == null ? "" : sessionPassword;
        this.avatar          = avatar == null ? new byte[0] : avatar.clone();
        this.host            = Objects.requireNonNull(host, "host");
        this.chatPort        = chatPort;
        this.filePort        = filePort;
        intentionalClose.set(false);
        return CompletableFuture.runAsync(() -> openConnection(false), connectionExecutor);
    }

    public CompletableFuture<Void> sendChat(String text) {
        return send(NetworkMessage.chat(displayName, text));
    }

    public CompletableFuture<Void> sendImage(byte[] pngBytes) {
        return send(NetworkMessage.image(displayName, "Foto instantanea enviada do local de fiscalizacao.", pngBytes));
    }

    public CompletableFuture<Void> updatePresence(String presence) {
        return send(NetworkMessage.builder(MessageType.PRESENCE_UPDATE)
                .from(displayName)
                .attribute("presence", presence == null ? "Online" : presence)
                .build());
    }

    public CompletableFuture<Void> sendCriticalAlert(String text) {
        String alertId = "alert-" + System.currentTimeMillis() + "-" + clientId.substring(0, Math.min(8, clientId.length()));
        return send(NetworkMessage.builder(MessageType.ALERT)
                .from(displayName)
                .text(text)
                .attribute("critical", "true")
                .attribute("alertId", alertId)
                .attribute("room", room == null ? "" : room)
                .build());
    }

    public CompletableFuture<Void> confirmCriticalAlert(String alertId) {
        return send(NetworkMessage.builder(MessageType.ALERT_ACK)
                .from(displayName)
                .attribute("alertId", alertId == null ? "" : alertId)
                .attribute("room", room == null ? "" : room)
                .build());
    }

    public CompletableFuture<Void> joinRoom(String room) {
        String nextRoom = sanitizeRoom(room);
        return send(NetworkMessage.builder(MessageType.JOIN_ROOM)
                .from(displayName).text(nextRoom).attribute("room", nextRoom).build())
                .thenRun(() -> this.room = nextRoom);
    }

    public CompletableFuture<Void> updateRoleAccess(String targetUsername, String role, List<String> channels) {
        String channelPayload = channels == null ? "" : String.join("|", channels);
        return send(NetworkMessage.builder(MessageType.ROLE_UPDATE)
                .from(displayName)
                .attribute("target",   targetUsername == null ? "" : targetUsername)
                .attribute("role",     role == null ? "" : role)
                .attribute("channels", channelPayload)
                .build());
    }

    public CompletableFuture<Void> updatePasswords(String memberPassword, String adminPassword) {
        return send(NetworkMessage.builder(MessageType.AUTH_UPDATE)
                .from(displayName)
                .attribute("memberPassword", memberPassword == null ? "" : memberPassword)
                .attribute("adminPassword",  adminPassword  == null ? "" : adminPassword)
                .build());
    }

    public CompletableFuture<Void> send(NetworkMessage message) {
        return CompletableFuture.runAsync(() -> {
            if (!connected.get() || output == null) {
                throw new IllegalStateException("Cliente offline.");
            }
            try {
                synchronized (writerLock) {
                    output.writeObject(message);
                    output.flush();
                    output.reset();
                }
            } catch (IOException exception) {
                closeCurrentSocket();
                throw new IllegalStateException("Falha ao enviar dados TCP: " + exception.getMessage(), exception);
            }
        }, writerExecutor);
    }

    public CompletableFuture<String> uploadFile(Path path, TransferProgress progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (room == null || room.isBlank()) {
                    throw new IOException("Escolha um canal antes de enviar relatorios.");
                }
                validateUpload(path);
                long   size     = Files.size(path);
                String fileName = path.getFileName().toString();
                try (Socket fileSocket = newTransferSocket();
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));
                     DataInputStream  in  = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()));
                     InputStream fileIn   = Files.newInputStream(path)) {
                    out.writeUTF("UPLOAD");
                    out.writeUTF(displayName);
                    out.writeUTF(room);
                    out.writeUTF(fileName);
                    out.writeLong(size);
                    copyWithProgress(fileIn, out, size, progress);
                    out.flush();
                    String status = in.readUTF();
                    if (!"OK".equals(status)) throw new IOException(in.readUTF());
                    return in.readUTF();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Upload falhou: " + exception.getMessage(), exception);
            }
        }, transferExecutor);
    }

    public CompletableFuture<List<FileMetadata>> listRemoteFiles() {
        return listRemoteFilesForRoom(room);
    }

    public CompletableFuture<List<FileMetadata>> listRemoteFilesForRoom(String targetRoom) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket fileSocket = newTransferSocket();
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));
                 DataInputStream  in  = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()))) {
                String nextRoom = sanitizeRoom(targetRoom);
                if (nextRoom.isBlank()) return List.of();

                out.writeUTF("LIST");
                out.writeUTF(nextRoom);
                out.flush();

                String status = in.readUTF();
                if (!"OK".equals(status)) throw new IOException(in.readUTF());

                int count = in.readInt();
                List<FileMetadata> metadata = new ArrayList<>(count);
                for (int i = 0; i < count; i++) metadata.add(readMetadata(in));
                return metadata;
            } catch (IOException exception) {
                throw new IllegalStateException("Listagem falhou: " + exception.getMessage(), exception);
            }
        }, transferExecutor);
    }

    public CompletableFuture<Path> downloadFile(FileMetadata metadata, Path directory, TransferProgress progress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket fileSocket = newTransferSocket();
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));
                 DataInputStream  in  = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()))) {
                out.writeUTF("DOWNLOAD");
                out.writeUTF(metadata.getId());
                out.flush();
                String status = in.readUTF();
                if (!"OK".equals(status)) throw new IOException(in.readUTF());
                String fileName = sanitizeFileName(in.readUTF());
                long   size     = in.readLong();
                Files.createDirectories(directory);
                Path target = uniquePath(directory.resolve(fileName));
                try (OutputStream fileOut = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    copyWithProgress(in, fileOut, size, progress);
                }
                return target;
            } catch (IOException exception) {
                throw new IllegalStateException("Download falhou: " + exception.getMessage(), exception);
            }
        }, transferExecutor);
    }

    public void addMessageListener(Consumer<NetworkMessage> listener)    { messageListeners.add(listener); }
    public void addStateListener(BiConsumer<Boolean, String> listener)   { stateListeners.add(listener); }
    public boolean isConnected()  { return connected.get(); }
    public String  getUsername()  { return username; }
    public String  getClientId()  { return clientId; }
    public String  getDisplayName() { return displayName; }
    public String  getRoom()      { return room; }

    public void setConfirmedRoom(String confirmedRoom) {
        if (confirmedRoom == null || confirmedRoom.isBlank()) {
            this.room = "";
        } else {
            this.room = sanitizeRoom(confirmedRoom);
        }
    }

    public void disconnect() {
        intentionalClose.set(true);
        connected.set(false);
        closeCurrentSocket();
        notifyState(false, "Offline");
    }

    public void shutdown() {
        disconnect();
        connectionExecutor.shutdownNow();
        writerExecutor.shutdownNow();
        transferExecutor.shutdownNow();
    }

    // =========================================================================
    // Internos
    // =========================================================================

    private synchronized void openConnection(boolean reconnect) {
        closeCurrentSocket();
        try {
            Socket             nextSocket = new Socket();
            nextSocket.connect(new InetSocketAddress(host, chatPort), CONNECT_TIMEOUT_MS);
            nextSocket.setKeepAlive(true);
            ObjectOutputStream nextOutput = new ObjectOutputStream(new BufferedOutputStream(nextSocket.getOutputStream()));
            nextOutput.flush();
            ObjectInputStream  nextInput  = new ObjectInputStream(new BufferedInputStream(nextSocket.getInputStream()));

            socket = nextSocket;
            output = nextOutput;
            input  = nextInput;
            connected.set(true);

            writeDirect(NetworkMessage.builder(MessageType.LOGIN)
                    .from(displayName)
                    .payload(avatar)
                    .attribute("clientId",      clientId)
                    .attribute("username",      username)
                    .attribute("session",       adminSession ? "admin" : "member")
                    .attribute("password",      sessionPassword)
                    .attribute("adminPassword", sessionPassword)
                    .build());

            if (room != null && !room.isBlank()) {
                writeDirect(NetworkMessage.builder(MessageType.JOIN_ROOM)
                        .from(displayName).text(room).attribute("room", room).build());
            }
            notifyState(true, reconnect ? "Reconectado" : "Online");
            startReaderThread();
        } catch (IOException exception) {
            connected.set(false);
            closeCurrentSocket();
            notifyState(false, "Offline");
            throw new IllegalStateException("Nao foi possivel conectar em " + host + ":" + chatPort, exception);
        }
    }

    private void startReaderThread() {
        Thread reader = new Thread(this::readLoop, "client-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            while (connected.get()) {
                Object obj = input.readObject();
                if (obj instanceof NetworkMessage message) notifyMessage(message);
            }
        } catch (EOFException | SocketException ignored) {
            // Servidor encerrou conexao ou rede caiu.
        } catch (IOException | ClassNotFoundException exception) {
            notifyMessage(NetworkMessage.error("Falha de leitura TCP: " + exception.getMessage()));
        } finally {
            boolean wasConnected = connected.getAndSet(false);
            closeCurrentSocket();
            if (wasConnected) notifyState(false, "Offline");
            if (!intentionalClose.get()) scheduleReconnect();
        }
    }

    /**
     * Reconexao com backoff exponencial: 1 s, 2 s, 4 s, 8 s, 16 s.
     */
    private void scheduleReconnect() {
        connectionExecutor.execute(() -> {
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS && !intentionalClose.get(); attempt++) {
                long delayMs = (1L << (attempt - 1)) * 1_000L; // 1, 2, 4, 8, 16 segundos
                notifyState(false, "Reconectando em " + (delayMs / 1000) + "s (" + attempt + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                try {
                    Thread.sleep(delayMs);
                    openConnection(true);
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ex) {
                    notifyState(false, "Tentativa " + attempt + "/" + MAX_RECONNECT_ATTEMPTS + " falhou");
                }
            }
            if (!intentionalClose.get()) {
                notifyState(false, "Sem conexao — reconexao encerrada");
            }
        });
    }

    private Socket newTransferSocket() throws IOException {
        Socket transferSocket = new Socket();
        transferSocket.connect(new InetSocketAddress(host, filePort), CONNECT_TIMEOUT_MS);
        transferSocket.setSoTimeout(30_000);
        return transferSocket;
    }

    private void writeDirect(NetworkMessage message) throws IOException {
        synchronized (writerLock) {
            output.writeObject(message);
            output.flush();
            output.reset();
        }
    }

    private void notifyMessage(NetworkMessage message) {
        for (Consumer<NetworkMessage> l : messageListeners) l.accept(message);
    }

    private void notifyState(boolean online, String detail) {
        for (BiConsumer<Boolean, String> l : stateListeners) l.accept(online, detail);
    }

    private void closeCurrentSocket() {
        try {
            Socket current = socket;
            if (current != null) current.close();
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Utilidades estaticas
    // =========================================================================

    private static FileMetadata readMetadata(DataInputStream in) throws IOException {
        return new FileMetadata(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong());
    }

    private static void validateUpload(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Arquivo nao encontrado.");
        }
        long size = Files.size(path);
        if (size <= 0) {
            throw new IOException("Arquivo vazio nao pode ser enviado.");
        }
        if (size > MAX_UPLOAD_SIZE) {
            throw new IOException("Arquivo acima do limite de 100 MB.");
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_UPLOAD_EXTENSIONS.stream().anyMatch(name::endsWith);
        if (!allowed) {
            throw new IOException("Extensao invalida. Use txt, pdf, doc, docx, csv, log ou md.");
        }
    }

    private static void copyWithProgress(InputStream in, OutputStream out, long total, TransferProgress progress) throws IOException {
        byte[] buf = new byte[8192];
        long   transferred = 0L;
        if (progress != null) progress.onProgress(0L, total);
        while (transferred < total) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, total - transferred));
            if (read == -1) throw new IOException("Stream encerrado antes do fim.");
            out.write(buf, 0, read);
            transferred += read;
            if (progress != null) progress.onProgress(transferred, total);
        }
    }

    private static Path uniquePath(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot) : "";
        Path parent = target.getParent();
        int counter = 1;
        Path candidate;
        do { candidate = parent.resolve(base + "-" + counter++ + ext); }
        while (Files.exists(candidate));
        return candidate;
    }

    private static String sanitizeFileName(String raw) {
        String name = (raw == null || raw.isBlank()) ? "download.bin" : raw;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sanitizeUsername(String raw) {
        String clean = raw == null ? "" : raw.trim().replaceAll("[^\\p{Alnum}_ .-]", "");
        return clean.isBlank() ? "inspetor" : clean.toLowerCase();
    }

    private static String sanitizeDisplayName(String raw) {
        String clean = raw == null ? "" : raw.trim().replaceAll("[\\t\\n\\r]", " ");
        return clean.isBlank() ? "Inspetor" : clean.substring(0, Math.min(clean.length(), 40));
    }

    private static String sanitizeRoom(String raw) {
        String clean = raw == null ? "" : raw.trim().replaceAll("[\\t\\n\\r]", " ");
        return clean.isBlank() ? "Equipe Alfa" : clean.substring(0, Math.min(clean.length(), 40));
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
