package gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeartBeat {
    private static final Logger logger = LoggerFactory.getLogger(HeartBeat.class);
    private final List<Integer> serverPorts;
    private final String serverType; // "http", "tcp", or "udp"
    private final Gateway gateway;

    public HeartBeat(List<Integer> serverPorts, String serverType, Gateway gateway) {
        this.serverPorts = new CopyOnWriteArrayList<>(serverPorts); // Thread-safe list
        this.serverType = serverType;
        this.gateway = gateway;
    }

    public void check() {
        for (int port : serverPorts) {
            try {
                if ("http".equalsIgnoreCase(serverType)) {
                    checkHttpServer(port);
                } else if ("tcp".equalsIgnoreCase(serverType)) {
                    checkTcpServer(port);
                } else if ("udp".equalsIgnoreCase(serverType)) {
                    checkUdpServer(port);
                }
            } catch (IOException e) {
                logger.error("Falha ao verificar o servidor " + serverType.toUpperCase() + " na porta " + port + ": " + e.getMessage());
                gateway.removerServidor(serverType, port); // Remove o servidor inativo
            }
        }
    }

    private void checkHttpServer(int port) throws IOException {
        URL url = new URL("http://localhost:" + port + "/heartbeat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(500);  // Timeout de 500ms
        conn.setReadTimeout(500);     // Timeout de leitura

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            logger.info("Servidor HTTP na porta " + port + " está ativo.");
        } else {
            throw new IOException("Resposta HTTP inválida: " + responseCode);
        }
    }

    private void checkTcpServer(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);  // Timeout de conexão
            socket.setSoTimeout(500);  // Timeout de leitura

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println("ping");
            String resposta = in.readLine();
            if ("pong".equalsIgnoreCase(resposta)) {
                logger.info("Servidor TCP na porta " + port + " está ativo.");
            } else {
                throw new IOException("Resposta inesperada do servidor TCP: " + resposta);
            }
        }
    }

    private void checkUdpServer(int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] buffer = "ping".getBytes(StandardCharsets.UTF_8);
            InetAddress address = InetAddress.getByName("localhost");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
            socket.send(packet);

            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.setSoTimeout(500);  // Timeout de 500ms para o heartbeat
            socket.receive(responsePacket);  // Receber a resposta

            String resposta = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
            if ("pong".equalsIgnoreCase(resposta)) {
                logger.info("Servidor UDP na porta " + port + " está ativo.");
            } else {
                throw new IOException("Resposta inesperada do servidor UDP: " + resposta);
            }
        }
    }
}
