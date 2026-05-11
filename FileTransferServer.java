package aps.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import aps.server.data.ReportRecord;
import aps.server.data.ServerDatabase;
import aps.shared.logging.SystemLogger;
import aps.shared.model.FileMetadata;

final class FileTransferServer {

    private static final long MAX_FILE_SIZE = 100L * 1024L * 1024L; // 100 MB
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".txt", ".pdf", ".doc", ".docx", ".csv", ".log", ".md");

    private final int    port;
    private final Path   storageDir;
    private final Path   indexPath;
    private final ServerDatabase database;
    private final Consumer<FileMetadata> uploadListener;
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "file-transfer-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();

    private volatile boolean     running;
    private          ServerSocket serverSocket;

    FileTransferServer(int port, Path storageDir, ServerDatabase database, Consumer<FileMetadata> uploadListener) {
        this.port           = port;
        this.storageDir     = storageDir;
        this.indexPath      = storageDir.resolve("files-index.tsv");
        this.database       = database;
        this.uploadListener = uploadListener;
    }

    void startAsync() throws IOException {
        Files.createDirectories(storageDir);
        loadIndex();
        serverSocket = new ServerSocket(port);
        running      = true;
        Thread acceptor = new Thread(this::acceptLoop, "file-transfer-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running = false;
        workers.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    List<FileMetadata> snapshotMetadata() {
        return files.values().stream()
                .map(StoredFile::metadata)
                .sorted(Comparator.comparingLong(FileMetadata::getUploadedAt).reversed())
                .toList();
    }

    // -------------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(30_000);
                workers.execute(() -> handle(socket));
            } catch (SocketException ex) {
                if (running) SystemLogger.warn("Falha no socket de arquivos: " + ex.getMessage());
            } catch (IOException ex) {
                if (running) SystemLogger.error("Falha ao aceitar conexao de arquivo.", ex);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            String command = in.readUTF().trim().toUpperCase();
            switch (command) {
                case "UPLOAD"   -> handleUpload(in, out);
                case "LIST"     -> handleList(in, out);
                case "DOWNLOAD" -> handleDownload(in, out);
                default         -> writeError(out, "Comando de arquivo desconhecido: " + command);
            }
        } catch (IOException ex) {
            SystemLogger.warn("Falha na transferencia de arquivo: " + ex.getMessage());
        }
    }

    private void handleUpload(DataInputStream in, DataOutputStream out) throws IOException {
        String owner        = sanitizeIndexValue(in.readUTF());
        String room         = sanitizeRoom(in.readUTF());
        String originalName = sanitizeFileName(in.readUTF());
        long   size         = in.readLong();

        if (size <= 0) {
            writeError(out, "Arquivo vazio nao pode ser enviado.");
            return;
        }
        if (size > MAX_FILE_SIZE) {
            writeError(out, "Arquivo recusado. Limite: 100 MB.");
            return;
        }
        if (!hasAllowedExtension(originalName)) {
            writeError(out, "Extensao invalida. Use txt, pdf, doc, docx, csv, log ou md.");
            return;
        }
        if (hasDuplicateName(room, originalName)) {
            writeError(out, "Ja existe um relatorio com esse nome neste canal.");
            return;
        }

        String id         = UUID.randomUUID().toString();
        String storedName = id + "-" + originalName;
        Path   target     = storageDir.resolve(storedName);

        try (OutputStream fileOut = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            copyExact(in, fileOut, size);
        } catch (IOException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }

        long         uploadedAt = Instant.now().toEpochMilli();
        FileMetadata metadata   = new FileMetadata(id, originalName, owner, room, size, uploadedAt);
        StoredFile   stored     = new StoredFile(metadata, storedName);
        files.put(id, stored);
        appendIndex(stored);
        database.saveReport(metadata, storedName);
        database.audit("UPLOAD", owner, "Relatorio recebido: " + originalName + " em " + room + " (" + size + " bytes)");

        out.writeUTF("OK");
        out.writeUTF(id);
        out.flush();
        uploadListener.accept(metadata);
    }

    private void handleList(DataInputStream in, DataOutputStream out) throws IOException {
        String room = sanitizeRoom(in.readUTF());
        List<FileMetadata> snapshot = files.values().stream()
                .map(StoredFile::metadata)
                .filter(m -> m.getRoom().equals(room))
                .sorted(Comparator.comparingLong(FileMetadata::getUploadedAt).reversed())
                .toList();
        out.writeUTF("OK");
        out.writeInt(snapshot.size());
        for (FileMetadata m : snapshot) writeMetadata(out, m);
        out.flush();
    }

    private void handleDownload(DataInputStream in, DataOutputStream out) throws IOException {
        String     id     = in.readUTF();
        StoredFile stored = files.get(id);
        if (stored == null) { writeError(out, "Arquivo nao encontrado."); return; }

        Path filePath = storageDir.resolve(stored.storedName());
        if (!Files.exists(filePath)) { writeError(out, "Arquivo indexado, mas nao localizado no disco."); return; }

        out.writeUTF("OK");
        out.writeUTF(stored.metadata().getFileName());
        out.writeLong(stored.metadata().getSize());
        try (InputStream fileIn = Files.newInputStream(filePath)) {
            fileIn.transferTo(out);
        }
        out.flush();
    }

    private void loadIndex() throws IOException {
        loadLocalIndex();
        for (ReportRecord record : database.loadReports()) {
            Path storedPath = storageDir.resolve(record.storedName());
            if (Files.exists(storedPath)) {
                files.putIfAbsent(record.metadata().getId(), new StoredFile(record.metadata(), record.storedName()));
            }
        }
    }

    private void loadLocalIndex() throws IOException {
        if (!Files.exists(indexPath)) return;
        List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 7);
            if (parts.length != 6 && parts.length != 7) continue;
            FileMetadata metadata;
            String storedName;
            try {
                if (parts.length == 7) {
                    metadata   = new FileMetadata(parts[0], parts[5], parts[1], parts[2], Long.parseLong(parts[3]), Long.parseLong(parts[4]));
                    storedName = parts[6];
                } else {
                    metadata   = new FileMetadata(parts[0], parts[4], parts[1], "Equipe Alfa", Long.parseLong(parts[2]), Long.parseLong(parts[3]));
                    storedName = parts[5];
                }
            } catch (NumberFormatException exception) {
                continue;
            }
            Path storedPath = storageDir.resolve(storedName);
            if (Files.exists(storedPath)) files.put(metadata.getId(), new StoredFile(metadata, storedName));
        }
    }

    private synchronized void appendIndex(StoredFile stored) throws IOException {
        FileMetadata m = stored.metadata();
        String line = String.join("\t",
                sanitizeIndexValue(m.getId()),
                sanitizeIndexValue(m.getOwner()),
                sanitizeIndexValue(m.getRoom()),
                Long.toString(m.getSize()),
                Long.toString(m.getUploadedAt()),
                sanitizeIndexValue(m.getFileName()),
                sanitizeIndexValue(stored.storedName())
        ) + System.lineSeparator();
        Files.writeString(indexPath, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // =========================================================================
    // Utilidades estaticas
    // =========================================================================

    private static void writeMetadata(DataOutputStream out, FileMetadata m) throws IOException {
        out.writeUTF(m.getId());
        out.writeUTF(m.getFileName());
        out.writeUTF(m.getOwner());
        out.writeUTF(m.getRoom());
        out.writeLong(m.getSize());
        out.writeLong(m.getUploadedAt());
    }

    private static void writeError(DataOutputStream out, String text) throws IOException {
        out.writeUTF("ERR");
        out.writeUTF(text);
        out.flush();
    }

    private static void copyExact(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf  = new byte[8192];
        long   rem  = size;
        while (rem > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (read == -1) throw new IOException("Stream encerrado antes do fim do arquivo.");
            out.write(buf, 0, read);
            rem -= read;
        }
    }

    private static String sanitizeFileName(String raw) {
        String name = (raw == null || raw.isBlank()) ? "relatorio.bin" : raw;
        name = Path.of(name).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").replace('\t', '_');
        return name.isBlank() ? "relatorio.bin" : name;
    }

    private boolean hasDuplicateName(String room, String fileName) {
        return files.values().stream()
                .map(StoredFile::metadata)
                .anyMatch(metadata -> metadata.getRoom().equalsIgnoreCase(room)
                        && metadata.getFileName().equalsIgnoreCase(fileName));
    }

    private static boolean hasAllowedExtension(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String sanitizeIndexValue(String raw) {
        return raw == null ? "" : raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String sanitizeRoom(String raw) {
        String room = sanitizeIndexValue(raw).trim();
        return room.isBlank() ? "Equipe Alfa" : room.substring(0, Math.min(room.length(), 40));
    }

    private record StoredFile(FileMetadata metadata, String storedName) {}
}
