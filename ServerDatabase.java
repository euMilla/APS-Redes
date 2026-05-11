package aps.server.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import aps.shared.config.AppConfig;
import aps.shared.logging.SystemLogger;
import aps.shared.model.FileMetadata;
import aps.shared.security.PasswordHasher;

public final class ServerDatabase {

    private final Path storageDir;
    private final Path authFile;
    private final Path accessFile;
    private final Path auditFile;
    private final boolean mysqlAvailable;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private ServerDatabase(Path storageDir, boolean mysqlAvailable, String jdbcUrl, String user, String password) {
        this.storageDir = storageDir;
        this.authFile = storageDir.resolve("auth.properties");
        this.accessFile = storageDir.resolve("user-access.properties");
        this.auditFile = storageDir.resolve("logs").resolve("audit.log");
        this.mysqlAvailable = mysqlAvailable;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public static ServerDatabase open(Path storageDir) {
        AppConfig config = AppConfig.current();
        String jdbcUrl = config.get("DB_URL", "jdbc:mysql://localhost:3306/aps_redes?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        String user = config.get("DB_USER", "root");
        String password = config.get("DB_PASSWORD", "");
        boolean enabled = config.getBoolean("DB_ENABLED", false);
        if (!enabled) {
            return new ServerDatabase(storageDir, false, jdbcUrl, user, password);
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            ServerDatabase database = new ServerDatabase(storageDir, true, jdbcUrl, user, password);
            database.initializeMySql();
            database.audit("DATABASE", "sistema", "MySQL conectado em " + jdbcUrl);
            return database;
        } catch (Exception exception) {
            ServerDatabase fallback = new ServerDatabase(storageDir, false, jdbcUrl, user, password);
            fallback.audit("DATABASE", "sistema", "MySQL indisponivel. Usando armazenamento local: " + exception.getMessage());
            SystemLogger.warn("MySQL indisponivel. Usando armazenamento local: " + exception.getMessage());
            return fallback;
        }
    }

    public boolean isMysqlAvailable() {
        return mysqlAvailable;
    }

    public String loadPasswordHash(String key, String defaultPlainPassword) {
        if (mysqlAvailable) {
            String stored = queryPasswordHash(key);
            if (stored != null && !stored.isBlank()) {
                return migrateLegacyHashIfNeeded(key, stored);
            }
            String hash = PasswordHasher.hash(defaultPlainPassword);
            savePasswordHash(key, hash);
            return hash;
        }

        Properties props = readProperties(authFile);
        String stored = props.getProperty(key, "");
        if (stored.isBlank()) {
            stored = defaultPlainPassword;
        }
        String normalized = migrateLegacyHashIfNeeded(key, stored);
        props.setProperty(key, normalized);
        writeProperties(authFile, props, "APS Redes password hashes");
        return normalized;
    }

    public void savePasswordHash(String key, String hash) {
        if (mysqlAvailable) {
            executeUpdate("""
                    INSERT INTO aps_auth(key_name, password_hash, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), updated_at = CURRENT_TIMESTAMP
                    """, key, hash);
            return;
        }
        Properties props = readProperties(authFile);
        props.setProperty(key, hash);
        writeProperties(authFile, props, "APS Redes password hashes");
    }

    public Map<String, UserAccessRecord> loadUserAccess() {
        Map<String, UserAccessRecord> result = new LinkedHashMap<>();
        if (mysqlAvailable) {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT username, role_name, allowed_channels FROM aps_user_access")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UserAccessRecord record = new UserAccessRecord(
                                rs.getString("username"),
                                rs.getString("role_name"),
                                parseChannels(rs.getString("allowed_channels")));
                        result.put(record.username(), record);
                    }
                }
            } catch (SQLException exception) {
                audit("DATABASE", "sistema", "Falha ao carregar acessos: " + exception.getMessage());
            }
        }

        if (result.isEmpty()) {
            Properties props = readProperties(accessFile);
            for (String username : props.stringPropertyNames()) {
                String[] parts = props.getProperty(username, "Membro|Equipe Alfa").split("\\|", 2);
                String role = parts.length > 0 ? parts[0] : "Membro";
                List<String> channels = parts.length > 1 ? parseChannels(parts[1]) : List.of("Equipe Alfa");
                result.put(username, new UserAccessRecord(username, role, channels));
            }
        }
        if (result.isEmpty()) {
            seedDemoAccess(result);
        }
        return result;
    }

    public void saveUserAccess(String username, String role, List<String> channels) {
        String channelPayload = String.join("|", channels == null ? List.of() : channels);
        if (mysqlAvailable) {
            executeUpdate("""
                    INSERT INTO aps_user_access(username, role_name, allowed_channels, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE role_name = VALUES(role_name),
                                            allowed_channels = VALUES(allowed_channels),
                                            updated_at = CURRENT_TIMESTAMP
                    """, username, role, channelPayload);
        }

        Properties props = readProperties(accessFile);
        props.setProperty(username, role + "|" + channelPayload);
        writeProperties(accessFile, props, "APS Redes user access");
    }

    public List<ReportRecord> loadReports() {
        if (!mysqlAvailable) {
            return List.of();
        }
        List<ReportRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, file_name, owner_name, room_name, file_size, uploaded_at_epoch, stored_name
                     FROM aps_reports
                     ORDER BY uploaded_at_epoch DESC
                     """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    FileMetadata metadata = new FileMetadata(
                            rs.getString("id"),
                            rs.getString("file_name"),
                            rs.getString("owner_name"),
                            rs.getString("room_name"),
                            rs.getLong("file_size"),
                            rs.getLong("uploaded_at_epoch"));
                    records.add(new ReportRecord(metadata, rs.getString("stored_name")));
                }
            }
        } catch (SQLException exception) {
            audit("DATABASE", "sistema", "Falha ao carregar relatorios: " + exception.getMessage());
        }
        return records;
    }

    public void saveReport(FileMetadata metadata, String storedName) {
        if (!mysqlAvailable) {
            return;
        }
        executeUpdate("""
                INSERT INTO aps_reports(id, file_name, owner_name, room_name, file_size, uploaded_at_epoch, stored_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE file_name = VALUES(file_name),
                                        owner_name = VALUES(owner_name),
                                        room_name = VALUES(room_name),
                                        file_size = VALUES(file_size),
                                        uploaded_at_epoch = VALUES(uploaded_at_epoch),
                                        stored_name = VALUES(stored_name)
                """,
                metadata.getId(), metadata.getFileName(), metadata.getOwner(), metadata.getRoom(),
                Long.toString(metadata.getSize()), Long.toString(metadata.getUploadedAt()), storedName);
    }

    public void audit(String eventType, String username, String message) {
        String line = Instant.now() + " | " + safe(eventType) + " | " + safe(username) + " | " + safe(message);
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(auditFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Auditoria em arquivo nao pode derrubar o servidor.
        }

        if (mysqlAvailable) {
            try {
                executeUpdate("""
                        INSERT INTO aps_audit_logs(event_type, username, message, created_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """, eventType, username, message);
            } catch (RuntimeException ignored) {
                // Se o banco caiu no meio da execucao, o arquivo local ainda registra o evento.
            }
        }
    }

    private void initializeMySql() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aps_auth (
                        key_name VARCHAR(40) PRIMARY KEY,
                        password_hash VARCHAR(255) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aps_user_access (
                        username VARCHAR(80) PRIMARY KEY,
                        role_name VARCHAR(40) NOT NULL DEFAULT 'Membro',
                        allowed_channels VARCHAR(255) NOT NULL DEFAULT 'Equipe Alfa',
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aps_reports (
                        id VARCHAR(80) PRIMARY KEY,
                        file_name VARCHAR(255) NOT NULL,
                        owner_name VARCHAR(120) NOT NULL,
                        room_name VARCHAR(80) NOT NULL,
                        file_size BIGINT NOT NULL,
                        uploaded_at_epoch BIGINT NOT NULL,
                        stored_name VARCHAR(320) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_report_room_name (room_name, file_name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aps_audit_logs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_type VARCHAR(60) NOT NULL,
                        username VARCHAR(120) NOT NULL,
                        message TEXT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    INSERT IGNORE INTO aps_user_access(username, role_name, allowed_channels)
                    VALUES
                      ('membro-demo', 'Membro', 'Equipe Alfa'),
                      ('inspetor-demo', 'Fiscal', 'Equipe Alfa|Equipe Beta'),
                      ('coordenador-demo', 'Coordenador', 'Equipe Alfa|Equipe Beta|Equipe Gama|Central Operacional')
                    """);
        }
    }

    private String queryPasswordHash(String key) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT password_hash FROM aps_auth WHERE key_name = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException exception) {
            audit("DATABASE", "sistema", "Falha ao ler senha " + key + ": " + exception.getMessage());
            return "";
        }
    }

    private String migrateLegacyHashIfNeeded(String key, String stored) {
        if (PasswordHasher.isHash(stored)) {
            return stored;
        }
        String migrated = PasswordHasher.hash(stored);
        savePasswordHash(key, migrated);
        return migrated;
    }

    private void executeUpdate(String sql, String... values) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Falha SQL: " + exception.getMessage(), exception);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private static List<String> parseChannels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static Properties readProperties(Path path) {
        Properties props = new Properties();
        if (!Files.exists(path)) {
            return props;
        }
        try (var input = Files.newInputStream(path)) {
            props.load(input);
        } catch (IOException ignored) {
            props.clear();
        }
        return props;
    }

    private static void writeProperties(Path path, Properties props, String comment) {
        try {
            Files.createDirectories(path.getParent());
            try (var output = Files.newOutputStream(path)) {
                props.store(output, comment);
            }
        } catch (IOException ignored) {
            // Persistencia local e melhor esforco quando MySQL nao esta ativo.
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void seedDemoAccess(Map<String, UserAccessRecord> result) {
        result.put("membro-demo", new UserAccessRecord("membro-demo", "Membro", List.of("Equipe Alfa")));
        result.put("inspetor-demo", new UserAccessRecord("inspetor-demo", "Fiscal", List.of("Equipe Alfa", "Equipe Beta")));
        result.put("coordenador-demo", new UserAccessRecord("coordenador-demo", "Coordenador",
                List.of("Equipe Alfa", "Equipe Beta", "Equipe Gama", "Central Operacional")));
    }
}
