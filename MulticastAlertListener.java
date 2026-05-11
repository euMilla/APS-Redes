package aps.client.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.Consumer;

import aps.shared.net.Ports;

public final class MulticastAlertListener implements AutoCloseable {

    private final Consumer<String> alertConsumer;
    private volatile boolean running;
    private Thread thread;

    public MulticastAlertListener(Consumer<String> alertConsumer) {
        this.alertConsumer = alertConsumer;
    }

    public void start() {
        if (running) return;
        running = true;
        thread  = new Thread(this::listen, "multicast-alert-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // -------------------------------------------------------------------------

    private void listen() {
        try (MulticastSocket socket = new MulticastSocket(Ports.MULTICAST_PORT)) {
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);
            InetAddress group = InetAddress.getByName(Ports.MULTICAST_GROUP);
            NetworkInterface nic = selectNetworkInterface();
            if (nic != null) {
                socket.joinGroup(new InetSocketAddress(group, Ports.MULTICAST_PORT), nic);
            } else {
                joinGroupCompat(socket, group);
            }
            byte[] buffer = new byte[4096];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    alertConsumer.accept(parsePayload(payload));
                } catch (SocketTimeoutException ignored) {
                    // Permite sair da thread sem bloquear.
                }
            }
        } catch (IOException exception) {
            if (running) {
                alertConsumer.accept("Canal multicast indisponivel: " + exception.getMessage());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void joinGroupCompat(MulticastSocket socket, InetAddress group) throws IOException {
        socket.joinGroup(group);
    }

    private static NetworkInterface selectNetworkInterface() throws IOException {
        for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (candidate.isUp() && candidate.supportsMulticast() && !candidate.isLoopback()) {
                return candidate;
            }
        }
        return null;
    }

    private static String parsePayload(String payload) {
        String[] parts = payload.split("\\|", 3);
        return (parts.length == 3 && "ALERT".equals(parts[0])) ? parts[2] : payload;
    }
}