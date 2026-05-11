package aps.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

import aps.shared.config.AppConfig;
import aps.shared.logging.SystemLogger;
import aps.shared.net.Ports;

public final class CentralServerApp {

    private CentralServerApp() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.current();
        int chatPort = intArg(args, "--chatPort", config.getInt("APS_CHAT_PORT", Ports.DEFAULT_CHAT_PORT));
        int filePort = intArg(args, "--filePort", Ports.DEFAULT_FILE_PORT);
        Path storageDir = Path.of(stringArg(args, "--storage", config.get("APS_STORAGE_DIR", "server-storage")));

        CentralServer server = new CentralServer(chatPort, filePort, storageDir);
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception exception) {
                SystemLogger.error("Servidor encerrado por falha inesperada.", exception);
                exception.printStackTrace();
            }
        }, "central-server-main");
        serverThread.start();

        printHelp();
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isBlank()) {
                    continue;
                }
                if (line.equalsIgnoreCase("/quit")) {
                    server.shutdown();
                    break;
                }
                if (line.equalsIgnoreCase("/users")) {
                    System.out.println("Usuarios: " + String.join(", ", server.getConnectedUsers()));
                    continue;
                }
                if (line.equalsIgnoreCase("/help")) {
                    printHelp();
                    continue;
                }
                if (line.startsWith("/alert ")) {
                    sendMulticast(server, line.substring("/alert ".length()).trim());
                    continue;
                }
                if (line.startsWith("/critical ")) {
                    String message = line.substring("/critical ".length()).trim();
                    if (message.isBlank()) {
                        System.out.println("Uso: /critical mensagem critica");
                        continue;
                    }
                    sendMulticast(server, "CRITICO: " + message);
                    server.sendSystemMessage("ALERTA CRITICO: " + message);
                    continue;
                }
                server.sendSystemMessage(line);
            }
        }
    }

    private static void sendMulticast(CentralServer server, String message) {
        try {
            server.sendMulticastAlert(message);
        } catch (Exception exception) {
            System.out.println("Falha multicast: " + exception.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("""
                Comandos da Central:
                  /alert mensagem                         envia alerta multicast UDP
                  /critical mensagem                       alerta critico via multicast + TCP
                  /users                                  lista inspetores conectados
                  /quit                                   encerra o servidor
                  texto livre                             envia alerta central via TCP
                """);
    }

    private static int intArg(String[] args, String name, int fallback) {
        String value = stringArg(args, name, Integer.toString(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stringArg(String[] args, String name, String fallback) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(name + "="))
                .map(arg -> arg.substring((name + "=").length()))
                .findFirst()
                .orElse(fallback);
    }
}
