package gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class Gateway {
    private static final Logger logger = LoggerFactory.getLogger(Gateway.class);
    private static final int PORTA_GATEWAY_HTTP = 9000;
    private static final int PORTA_GATEWAY_TCP = 9001;
    private static final int PORTA_GATEWAY_UDP = 9002;

    private ExecutorService executorServiceHTTP;
    private ExecutorService executorServiceTCP;
    private ExecutorService executorServiceUDP;

    // Mapas para armazenar os servidores registrados dinamicamente
    private final List<Integer> httpHandlerPorts = new ArrayList<>();
    private final List<Integer> tcpHandlerPorts = new ArrayList<>();
    private final List<Integer> udpHandlerPorts = new ArrayList<>();

    public List<Integer> getHttpHandlerPorts() {
        return httpHandlerPorts;
    }

    public List<Integer> getTcpHandlerPorts() {
        return tcpHandlerPorts;
    }

    public List<Integer> getUdpHandlerPorts() {
        return udpHandlerPorts;
    }

    // Índices para Round Robin
    private int roundRobinHTTP = 0;
    private int roundRobinTCP = 0;
    private int roundRobinUDP = 0;

    public static void main(String[] args) {
        System.out.println("Iniciando Gateway...");
        Gateway gateway = new Gateway();
        gateway.iniciar();
    }

    public void iniciar() {
        try {
            // Inicializar servidor HTTP
            executorServiceHTTP = Executors.newFixedThreadPool(10);
            HttpServer serverHTTP = HttpServer.create(new InetSocketAddress(PORTA_GATEWAY_HTTP), 0);
            serverHTTP.createContext("/cadastrarItem", new GatewayHttpHandler(this));
            serverHTTP.createContext("/registrarLance", new GatewayHttpHandler(this));
            serverHTTP.createContext("/registerServer", new RegisterServerHandler(this)); // NOVO CONTEXTO DE REGISTRO
            serverHTTP.setExecutor(executorServiceHTTP);
            serverHTTP.start();
            logger.info("Gateway HTTP iniciado na porta {}", PORTA_GATEWAY_HTTP);

            // Inicializar servidor TCP
            executorServiceTCP = Executors.newFixedThreadPool(10);
            new Thread(this::iniciarServidorTCP).start();

            // Inicializar servidor UDP
            executorServiceUDP = Executors.newFixedThreadPool(10);
            new Thread(this::iniciarServidorUDP).start();

        } catch (IOException e) {
            logger.error("Erro ao iniciar o Gateway: {}", e.getMessage(), e);
        }
    }

    // Método para inicializar o servidor TCP
    private void iniciarServidorTCP() {
        try (ServerSocket serverSocket = new ServerSocket(PORTA_GATEWAY_TCP)) {
            logger.info("Servidor TCP iniciado na porta {}", PORTA_GATEWAY_TCP);
            while (true) {
                Socket clienteSocket = serverSocket.accept();
                executorServiceTCP.submit(new GatewayTCPHandler(clienteSocket, this));
            }
        } catch (IOException e) {
            logger.error("Erro no servidor TCP: {}", e.getMessage(), e);
        }
    }

    // Método para inicializar o servidor UDP
    private void iniciarServidorUDP() {
        try (DatagramSocket serverUDPSocket = new DatagramSocket(PORTA_GATEWAY_UDP)) {
            logger.info("Servidor UDP iniciado na porta {}", PORTA_GATEWAY_UDP);
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                serverUDPSocket.receive(packet);
                executorServiceUDP.submit(new GatewayUDPHandler(packet, serverUDPSocket, this));
            }
        } catch (IOException e) {
            logger.error("Erro no servidor UDP: {}", e.getMessage(), e);
        }
    }

    // Roteamento via HTTP
    public synchronized int getNextHTTPHandlerPort() {
        if (httpHandlerPorts.isEmpty()) {
            throw new IllegalStateException("Nenhum servidor HTTP disponível.");
        }
        int port = httpHandlerPorts.get(roundRobinHTTP);
        roundRobinHTTP = (roundRobinHTTP + 1) % httpHandlerPorts.size();
        return port;
    }

    // Roteamento via TCP
    public synchronized int getNextTCPHandlerPort() {
        if (tcpHandlerPorts.isEmpty()) {
            throw new IllegalStateException("Nenhum servidor TCP disponível.");
        }
        int port = tcpHandlerPorts.get(roundRobinTCP);
        roundRobinTCP = (roundRobinTCP + 1) % tcpHandlerPorts.size();
        return port;
    }

    // Roteamento via UDP
    public synchronized int getNextUDPHandlerPort() {
        if (udpHandlerPorts.isEmpty()) {
            throw new IllegalStateException("Nenhum servidor UDP disponível.");
        }
        int port = udpHandlerPorts.get(roundRobinUDP);
        roundRobinUDP = (roundRobinUDP + 1) % udpHandlerPorts.size();
        return port;
    }

    // Novo handler para registrar servidores dinamicamente
    static class RegisterServerHandler implements HttpHandler {
        private final Gateway gateway;

        public RegisterServerHandler(Gateway gateway) {
            this.gateway = gateway;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                // Formato esperado: tipo;porta (Ex: "http;8080")
                String[] partes = body.split(";");
                if (partes.length == 2) {
                    String tipo = partes[0];
                    int porta = Integer.parseInt(partes[1]);

                    if ("http".equalsIgnoreCase(tipo)) {
                        gateway.httpHandlerPorts.add(porta);
                        logger.info("Servidor HTTP registrado na porta {}", porta);
                    } else if ("tcp".equalsIgnoreCase(tipo)) {
                        gateway.tcpHandlerPorts.add(porta);
                        logger.info("Servidor TCP registrado na porta {}", porta);
                    } else if ("udp".equalsIgnoreCase(tipo)) {
                        gateway.udpHandlerPorts.add(porta);
                        logger.info("Servidor UDP registrado na porta {}", porta);
                    }

                    String resposta = "Servidor " + tipo.toUpperCase() + " registrado com sucesso na porta " + porta;
                    exchange.sendResponseHeaders(200, resposta.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(resposta.getBytes());
                    os.close();
                } else {
                    exchange.sendResponseHeaders(400, "Formato inválido".getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write("Formato inválido. Use: tipo;porta".getBytes());
                    os.close();
                }
            } else {
                exchange.sendResponseHeaders(405, "Método não permitido".getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write("Método não permitido".getBytes());
                os.close();
            }
        }
    }

    // Classe responsável por lidar com requisições HTTP no Gateway e redirecioná-las para servidores internos HTTP
    static class GatewayHttpHandler implements HttpHandler {
        private final Gateway gateway;

        public GatewayHttpHandler(Gateway gateway) {
            this.gateway = gateway;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String metodo = exchange.getRequestMethod();
            String caminho = exchange.getRequestURI().getPath();

            if ("POST".equalsIgnoreCase(metodo)) {
                InputStream is = exchange.getRequestBody();
                String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                String comando = body;

                try {
                    if ("/cadastrarItem".equalsIgnoreCase(caminho) || "/registrarLance".equalsIgnoreCase(caminho)) {
                        String respostaServidorInterno = gateway.enviarParaServidorInternoHTTP(gateway.getNextHTTPHandlerPort(), comando, caminho);

                        exchange.sendResponseHeaders(200, respostaServidorInterno.getBytes().length);
                        OutputStream os = exchange.getResponseBody();
                        os.write(respostaServidorInterno.getBytes());
                        os.close();
                    } else {
                        exchange.sendResponseHeaders(404, 0);
                        exchange.close();
                    }
                } catch (IllegalStateException | IOException e) {
                    // Return an error response when no internal server is available
                    String errorMessage = "Erro: Nenhum servidor HTTP disponível.";
                    logger.error(errorMessage);
                    exchange.sendResponseHeaders(500, errorMessage.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorMessage.getBytes(StandardCharsets.UTF_8));
                    os.close();
                }
            } else {
                exchange.sendResponseHeaders(405, "Método não permitido".getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write("Método não permitido".getBytes());
                os.close();
            }
        }
    }



 // Handler para requisições TCP
    static class GatewayTCPHandler implements Runnable {
        private final Socket socket;
        private final Gateway gateway;

        public GatewayTCPHandler(Socket socket, Gateway gateway) {
            this.socket = socket;
            this.gateway = gateway;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                String body = in.readLine();

                if (body != null && !body.isEmpty()) {
                    String comando;
                    if (body.startsWith("cadastrarItem")) {
                        comando = body;
                        logger.info("Recebido via TCP: " + comando);
                    } else if (body.startsWith("registrarLance")) {
                        comando = body;
                        logger.info("Recebido via TCP: " + comando);
                    } else {
                        out.write("Comando inválido.\n");
                        out.flush();
                        logger.warn("Comando TCP inválido: " + body);
                        return;
                    }

                    logger.info("Chamando enviarParaServidorInternoTCP com o comando: " + comando);

                    try {
                        // Call to send the request to the internal TCP server
                        String respostaServidorInterno = gateway.enviarParaServidorInternoTCP(comando);

                        // Log the response from the internal server
                        logger.info("Resposta do servidor interno TCP: " + respostaServidorInterno);

                        // Send response back to the client
                        out.write(respostaServidorInterno + "\n");
                        out.flush();
                    } catch (IllegalStateException e) {
                        // Handle case where no TCP server is available
                        logger.error("Erro: " + e.getMessage());
                        out.write("Erro: Nenhum servidor TCP disponível.\n");
                        out.flush();
                    } catch (IOException e) {
                        // Handle communication errors with the internal server
                        logger.error("Erro ao comunicar com o servidor TCP interno: " + e.getMessage(), e);
                        out.write("Erro ao comunicar com o servidor interno TCP.\n");
                        out.flush();
                    }

                } else {
                    logger.warn("Nenhum dado recebido via TCP.");
                }
            } catch (IOException e) {
                logger.error("Erro ao processar a requisição TCP: " + e.getMessage(), e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.error("Erro ao fechar socket TCP: " + e.getMessage(), e);
                }
            }
        }
    }


    // Handler para requisições UDP
    static class GatewayUDPHandler implements Runnable {
        private final DatagramPacket packet;
        private final DatagramSocket serverUDPSocket;
        private final Gateway gateway;

        public GatewayUDPHandler(DatagramPacket packet, DatagramSocket serverUDPSocket, Gateway gateway) {
            this.packet = packet;
            this.serverUDPSocket = serverUDPSocket;
            this.gateway = gateway;
        }

        @Override
        public void run() {
            try {
                String mensagem = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                logger.info("Recebido via UDP: " + mensagem);

                String comando;
                if (mensagem.startsWith("cadastrarItem") || mensagem.startsWith("registrarLance")) {
                    comando = mensagem;
                } else {
                    byte[] respostaInvalida = "Comando inválido.".getBytes(StandardCharsets.UTF_8);
                    DatagramPacket responsePacket = new DatagramPacket(respostaInvalida, respostaInvalida.length, packet.getAddress(), packet.getPort());
                    serverUDPSocket.send(responsePacket);
                    return;
                }

                try {
                    String respostaServidorInterno = gateway.enviarParaServidorInternoUDP(comando);

                    byte[] buffer = respostaServidorInterno.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length, packet.getAddress(), packet.getPort());
                    serverUDPSocket.send(responsePacket);
                } catch (IllegalStateException | IOException e) {
                    // Handle the case where no UDP server is available or communication fails
                    String errorMessage = "Erro: Nenhum servidor UDP disponível.";
                    byte[] responseBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket responsePacket = new DatagramPacket(responseBytes, responseBytes.length, packet.getAddress(), packet.getPort());
                    serverUDPSocket.send(responsePacket);
                    logger.error(errorMessage);
                }

            } catch (IOException e) {
                logger.error("Erro ao processar requisição UDP: " + e.getMessage(), e);
            }
        }
    }


    // Enviar dados via HTTP para o servidor interno
    private String enviarParaServidorInternoHTTP(int porta, String dados, String endpoint) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://localhost:" + porta + endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = dados.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("Falha ao se comunicar com o servidor HTTP interno. Código de resposta: " + responseCode);
            }

            InputStream responseStream = new BufferedInputStream(conn.getInputStream());
            return new BufferedReader(new InputStreamReader(responseStream)).lines().collect(Collectors.joining("\n"));

        } catch (IOException e) {
            logger.error("Erro ao comunicar com o servidor HTTP interno: " + e.getMessage());
            throw new IOException("Erro: Nenhum servidor HTTP disponível.");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

 // Enviar dados via TCP para o servidor interno
    private String enviarParaServidorInternoTCP(String dados) {
        int porta = getNextTCPHandlerPort();  // Certifique-se de que o TCPHandler está registrado corretamente
        logger.info("Tentando enviar dados para o servidor TCP na porta: " + porta);
        
        try (Socket socket = new Socket("localhost", porta)) {
            socket.setSoTimeout(5000);  // Adiciona um timeout de 5 segundos

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(dados);  // Enviar os dados para o servidor TCP
            logger.info("Dados enviados para o servidor TCP na porta " + porta + ": " + dados);

            String resposta = in.readLine();  // Ler a resposta do servidor TCP
            if (resposta == null) {
                logger.warn("Nenhuma resposta recebida do servidor TCP na porta: " + porta);
                return "Erro: Nenhuma resposta do servidor interno TCP.";
            } else {
                logger.info("Resposta recebida do servidor TCP: " + resposta);
                return resposta;
            }

        } catch (IOException e) {
            // Se falhar ao conectar ao servidor, remover a porta da lista
            logger.error("Erro ao comunicar com o servidor TCP na porta: " + porta + " - " + e.getMessage());
            removerPortaInativa(porta);  // Chama o método para remover a porta
            return "Erro: Nenhum servidor TCP disponível.";
        }
    }

    // Método para remover a porta TCP inativa da lista de servidores
    private synchronized void removerPortaInativa(int porta) {
        logger.info("Removendo porta inativa: " + porta);
        tcpHandlerPorts.remove(Integer.valueOf(porta));  // Remove a porta da lista
        logger.info("Porta removida: " + porta);
    }





    // Enviar dados via UDP para o servidor interno
    private String enviarParaServidorInternoUDP(String dados) throws IOException {
        int porta;
        try {
            porta = getNextUDPHandlerPort();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Nenhum servidor UDP disponível.");
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] buffer = dados.getBytes(StandardCharsets.UTF_8);
            InetAddress address = InetAddress.getByName("localhost");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, porta);
            socket.send(packet);

            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.setSoTimeout(5000);  // Set a timeout for receiving the response

            socket.receive(responsePacket);  // Receive the response
            return new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            logger.error("Erro ao comunicar com o servidor UDP interno: " + e.getMessage());
            throw new IOException("Erro: Nenhum servidor UDP disponível.");
        }
    }


}
