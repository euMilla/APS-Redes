package aps.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import aps.client.net.NetworkClient;
import aps.server.CentralServer;
import aps.shared.model.FileMetadata;
import aps.shared.model.MessageType;

class ClientServerIntegrationTest {

    @TempDir
    Path storageDir;

    private CentralServer server;
    private Thread serverThread;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach
    void configureLocalMode() {
        System.setProperty("DB_ENABLED", "false");
        System.setProperty("APS_MEMBER_PASSWORD", "membro123");
        System.setProperty("APS_ADMIN_PASSWORD", "admin123");
    }

    @AfterEach
    void shutdownServer() throws Exception {
        if (server != null) {
            server.shutdown();
        }
        if (serverThread != null) {
            serverThread.join(1_000);
        }
        System.clearProperty("DB_ENABLED");
        System.clearProperty("APS_MEMBER_PASSWORD");
        System.clearProperty("APS_ADMIN_PASSWORD");
    }

    @Test
    void memberCanLoginJoinChannelAndUploadReport() throws Exception {
        int chatPort = freePort();
        int filePort = freePort();
        startServer(chatPort, filePort);

        NetworkClient client = new NetworkClient();
        CountDownLatch loginAccepted = new CountDownLatch(1);
        CountDownLatch roomAccepted = new CountDownLatch(1);
        client.addMessageListener(message -> {
            if (message.getType() == MessageType.LOGIN_ACCEPTED) {
                loginAccepted.countDown();
                if ("Equipe Alfa".equals(message.getAttributes().get("room"))) {
                    roomAccepted.countDown();
                }
            }
        });

        try {
            connectWithRetry(client, chatPort, filePort);
            assertTrue(loginAccepted.await(5, TimeUnit.SECONDS), "Login de membro nao foi aceito.");

            client.joinRoom("Equipe Alfa").get(5, TimeUnit.SECONDS);
            assertTrue(roomAccepted.await(5, TimeUnit.SECONDS), "Entrada no canal nao foi confirmada.");

            Path report = storageDir.resolve("relatorio-teste.txt");
            Files.writeString(report, "Relatorio de teste", StandardCharsets.UTF_8);

            String fileId = client.uploadFile(report, (sent, total) -> { }).get(5, TimeUnit.SECONDS);
            assertFalse(fileId.isBlank());

            List<FileMetadata> files = client.listRemoteFilesForRoom("Equipe Alfa").get(5, TimeUnit.SECONDS);
            assertTrue(files.stream().anyMatch(file -> fileId.equals(file.getId())
                    && "relatorio-teste.txt".equals(file.getFileName())));
        } finally {
            client.shutdown();
        }

        assertNull(serverFailure.get(), () -> "Falha no servidor: " + serverFailure.get());
    }

    private void startServer(int chatPort, int filePort) {
        server = new CentralServer(chatPort, filePort, storageDir);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (SocketException ignored) {
                // Encerramento normal durante shutdown.
            } catch (Throwable throwable) {
                serverFailure.set(throwable);
            }
        }, "aps-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void connectWithRetry(NetworkClient client, int chatPort, int filePort) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                client.connect("Membro Teste", "127.0.0.1", chatPort, filePort, new byte[0], false, "membro123")
                        .get(2, TimeUnit.SECONDS);
                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(100);
            }
        }
        throw lastFailure;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
