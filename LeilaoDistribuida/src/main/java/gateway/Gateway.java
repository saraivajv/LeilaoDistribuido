package gateway;

import protocol.TCPHandler;
import protocol.HTTPHandler;
import protocol.UDPHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;

import paxos.PaxosProposer;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Gateway {
    private static final Logger logger = LoggerFactory.getLogger(Gateway.class);
    private static final int PORTA_GATEWAY_HTTP = 9000; // Porta para requisições HTTP do Gateway
    private static final int PORTA_GATEWAY_TCP = 9001;  // Porta para requisições TCP do Gateway
    private static final int PORTA_GATEWAY_UDP = 9002;  // Porta para requisições UDP do Gateway

    private HttpServer serverHTTP;
    private ExecutorService executorServiceHTTP;

    // Listas para gerenciar múltiplas instâncias dos servidores internos
    private final List<HTTPHandler> httpHandlers = new ArrayList<>();
    private final List<TCPHandler> tcpHandlers = new ArrayList<>();
    private final List<UDPHandler> udpHandlers = new ArrayList<>();

    // Índices para Round Robin
    private int roundRobinHTTP = 0;
    private int roundRobinTCP = 0;
    private int roundRobinUDP = 0;

    public static void main(String[] args) {
        Gateway gateway = new Gateway();
        gateway.iniciar();
    }

    /**
     * Método para iniciar o Gateway.
     */
    public void iniciar() {
        try {
            // Inicializar o servidor HTTP do Gateway
            serverHTTP = HttpServer.create(new InetSocketAddress(PORTA_GATEWAY_HTTP), 0);
            serverHTTP.createContext("/cadastrarItem", new GatewayHttpHandler(this));
            serverHTTP.createContext("/registrarLance", new GatewayHttpHandler(this));
            executorServiceHTTP = Executors.newFixedThreadPool(10);
            serverHTTP.setExecutor(executorServiceHTTP);
            serverHTTP.start();
            logger.info("Gateway HTTP iniciado e escutando na porta {}", PORTA_GATEWAY_HTTP);

            // Inicializar o servidor TCP do Gateway
            iniciarServidorTCPGateway();

            // Inicializar o servidor UDP do Gateway
            iniciarServidorUDPGateway();

            // Iniciar a interface de comandos em uma nova thread
            new Thread(this::startCommandInterface).start();
        } catch (Exception e) {
            logger.error("Erro ao iniciar o Gateway: {}", e.getMessage(), e);
        }
    }

    /**
     * Método para iniciar o servidor TCP do Gateway.
     */
    private void iniciarServidorTCPGateway() {
        new Thread(() -> {
            try (ServerSocket serverSocketTCP = new ServerSocket(PORTA_GATEWAY_TCP)) {
                logger.info("Gateway TCP iniciado e escutando na porta {}", PORTA_GATEWAY_TCP);

                while (true) {
                    Socket cliente = serverSocketTCP.accept();
                    logger.info("Cliente TCP conectado no Gateway TCP: {}", cliente.getRemoteSocketAddress());
                    new Thread(new ClienteGatewayTCP(cliente, this)).start();
                }
            } catch (IOException e) {
                logger.error("Erro no Gateway TCP: {}", e.getMessage(), e);
            }
        }).start();
    }

    /**
     * Método para iniciar o servidor UDP do Gateway.
     */
    private void iniciarServidorUDPGateway() {
        // Inicializar apenas o UDPHandler principal do Gateway na porta 9002
        UDPHandler gatewayUDPHandler = new UDPHandler(PORTA_GATEWAY_UDP, this, true);
        gatewayUDPHandler.iniciar();
        udpHandlers.add(gatewayUDPHandler);
        logger.info("Gateway UDP principal iniciado e escutando na porta {}", PORTA_GATEWAY_UDP);
    }

    /**
     * Método sincronizado para obter o próximo servidor HTTP interno usando Round Robin.
     * @return Próximo HTTPHandler disponível ou null se nenhum estiver ativo.
     */
    public synchronized HTTPHandler getNextHTTPHandler() {
        if (httpHandlers.isEmpty()) {
            return null;
        }
        HTTPHandler handler = httpHandlers.get(roundRobinHTTP);
        roundRobinHTTP = (roundRobinHTTP + 1) % httpHandlers.size();
        return handler;
    }

    /**
     * Método sincronizado para obter o próximo servidor TCP interno usando Round Robin.
     * @return Próximo TCPHandler disponível ou null se nenhum estiver ativo.
     */
    public synchronized TCPHandler getNextTCPHandler() {
        if (tcpHandlers.isEmpty()) {
            return null;
        }
        TCPHandler handler = tcpHandlers.get(roundRobinTCP);
        roundRobinTCP = (roundRobinTCP + 1) % tcpHandlers.size();
        return handler;
    }

    /**
     * Método sincronizado para obter o próximo servidor UDP interno usando Round Robin.
     * @return Próximo UDPHandler interno disponível ou null se nenhum estiver ativo.
     */
    public synchronized UDPHandler getNextUDPHandler() {
        // Exclui o UDPHandler principal do Gateway da lista de Round Robin
        List<UDPHandler> internalUDPHandlers = new ArrayList<>(udpHandlers);
        internalUDPHandlers.removeIf(handler -> handler.isGateway());

        if (internalUDPHandlers.isEmpty()) {
            return null;
        }
        UDPHandler handler = internalUDPHandlers.get(roundRobinUDP);
        roundRobinUDP = (roundRobinUDP + 1) % internalUDPHandlers.size();
        return handler;
    }

    /**
     * Método para iniciar um servidor HTTP interno na porta especificada.
     * @param porta Porta para iniciar o servidor HTTP.
     * @return true se o servidor foi iniciado com sucesso, false caso contrário.
     */
    public synchronized boolean iniciarServidorHTTP(int porta) {
        for (HTTPHandler handler : httpHandlers) {
            if (handler.getPorta() == porta && handler.isRunning()) {
                logger.warn("Servidor HTTP já está rodando na porta {}", porta);
                return false;
            }
        }
        HTTPHandler httpHandler = new HTTPHandler(porta);
        httpHandler.iniciar();
        httpHandlers.add(httpHandler);
        logger.info("Servidor HTTP iniciado na porta {}", porta);
        return true;
    }

    /**
     * Método para parar um servidor HTTP interno na porta especificada.
     * @param porta Porta do servidor HTTP a ser parado.
     * @return true se o servidor foi parado com sucesso, false caso contrário.
     */
    public synchronized boolean pararServidorHTTP(int porta) {
        Iterator<HTTPHandler> iterator = httpHandlers.iterator();
        while (iterator.hasNext()) {
            HTTPHandler handler = iterator.next();
            if (handler.getPorta() == porta && handler.isRunning()) {
                handler.parar();
                iterator.remove();
                logger.info("Servidor HTTP parado na porta {}", porta);
                return true;
            }
        }
        logger.warn("Servidor HTTP não está rodando na porta {}", porta);
        return false;
    }

    /**
     * Método para iniciar um servidor TCP interno na porta especificada.
     * @param porta Porta para iniciar o servidor TCP.
     * @return true se o servidor foi iniciado com sucesso, false caso contrário.
     */
    public synchronized boolean iniciarServidorTCP(int porta) {
        for (TCPHandler handler : tcpHandlers) {
            if (handler.getPorta() == porta && handler.isRunning()) {
                logger.warn("Servidor TCP já está rodando na porta {}", porta);
                return false;
            }
        }
        TCPHandler tcpHandler = new TCPHandler(porta);
        tcpHandler.iniciar();
        tcpHandlers.add(tcpHandler);
        logger.info("Servidor TCP iniciado na porta {}", porta);
        return true;
    }

    /**
     * Método para parar um servidor TCP interno na porta especificada.
     * @param porta Porta do servidor TCP a ser parado.
     * @return true se o servidor foi parado com sucesso, false caso contrário.
     */
    public synchronized boolean pararServidorTCP(int porta) {
        Iterator<TCPHandler> iterator = tcpHandlers.iterator();
        while (iterator.hasNext()) {
            TCPHandler handler = iterator.next();
            if (handler.getPorta() == porta && handler.isRunning()) {
                handler.parar();
                iterator.remove();
                logger.info("Servidor TCP parado na porta {}", porta);
                return true;
            }
        }
        logger.warn("Servidor TCP não está rodando na porta {}", porta);
        return false;
    }

    /**
     * Método para iniciar um servidor UDP interno na porta especificada.
     * @param porta Porta para iniciar o servidor UDP interno.
     * @return true se o servidor foi iniciado com sucesso, false caso contrário.
     */
    public synchronized boolean iniciarServidorUDP(int porta) {
        // Não iniciar o UDPHandler principal novamente
        if (porta == PORTA_GATEWAY_UDP) {
            logger.warn("Servidor UDP principal já está rodando na porta {}", porta);
            return false;
        }

        for (UDPHandler handler : udpHandlers) {
            if (handler.getPorta() == porta && handler.isRunning()) {
                logger.warn("Servidor UDP já está rodando na porta {}", porta);
                return false;
            }
        }
        UDPHandler udpHandler = new UDPHandler(porta, this, false);
        udpHandler.iniciar();
        udpHandlers.add(udpHandler);
        logger.info("Servidor UDP iniciado na porta {}", porta);
        return true;
    }

    /**
     * Método para parar um servidor UDP interno na porta especificada.
     * @param porta Porta do servidor UDP interno a ser parado.
     * @return true se o servidor foi parado com sucesso, false caso contrário.
     */
    public synchronized boolean pararServidorUDP(int porta) {
        if (porta == PORTA_GATEWAY_UDP) {
            logger.warn("Servidor UDP principal não pode ser parado diretamente.");
            return false;
        }

        Iterator<UDPHandler> iterator = udpHandlers.iterator();
        while (iterator.hasNext()) {
            UDPHandler handler = iterator.next();
            if (handler.getPorta() == porta && handler.isRunning()) {
                handler.parar();
                iterator.remove();
                logger.info("Servidor UDP parado na porta {}", porta);
                return true;
            }
        }
        logger.warn("Servidor UDP não está rodando na porta {}", porta);
        return false;
    }

    /**
     * Método para obter as portas ativas dos servidores HTTP internos.
     * @return Lista de portas HTTP ativas.
     */
    public synchronized List<Integer> getPortasHTTPAtivas() {
        return httpHandlers.stream()
                .filter(HTTPHandler::isRunning)
                .map(HTTPHandler::getPorta)
                .collect(Collectors.toList());
    }

    /**
     * Método para obter as portas ativas dos servidores TCP internos.
     * @return Lista de portas TCP ativas.
     */
    public synchronized List<Integer> getPortasTCPAtivas() {
        return tcpHandlers.stream()
                .filter(TCPHandler::isRunning)
                .map(TCPHandler::getPorta)
                .collect(Collectors.toList());
    }

    /**
     * Método para obter as portas ativas dos servidores UDP internos.
     * @return Lista de portas UDP ativas.
     */
    public synchronized List<Integer> getPortasUDPAtivas() {
        return udpHandlers.stream()
                .filter(UDPHandler::isRunning)
                .map(UDPHandler::getPorta)
                .collect(Collectors.toList());
    }

    /**
     * Método para iniciar a interface de comandos do Gateway.
     */
    private void startCommandInterface() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String comando;
            logger.info("Interface de Comandos do Gateway Iniciada.");
            logger.info("Comandos Disponíveis:");
            logger.info("  iniciar_http [porta]");
            logger.info("  parar_http [porta]");
            logger.info("  iniciar_tcp [porta]");
            logger.info("  parar_tcp [porta]");
            logger.info("  iniciar_udp [porta]");
            logger.info("  parar_udp [porta]");
            logger.info("  listar");
            logger.info("  sair");

            while (true) {
                System.out.print("> ");
                comando = reader.readLine();
                if (comando == null) continue;
                comando = comando.trim();
                if (comando.isEmpty()) continue;

                String[] partes = comando.split(" ");
                String acao = partes[0].toLowerCase();

                switch (acao) {
                    case "iniciar_http":
                        if (partes.length != 2) {
                            logger.warn("Uso: iniciar_http [porta]");
                            break;
                        }
                        try {
                            int portaHTTP = Integer.parseInt(partes[1]);
                            iniciarServidorHTTP(portaHTTP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "parar_http":
                        if (partes.length != 2) {
                            logger.warn("Uso: parar_http [porta]");
                            break;
                        }
                        try {
                            int portaHTTP = Integer.parseInt(partes[1]);
                            pararServidorHTTP(portaHTTP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "iniciar_tcp":
                        if (partes.length != 2) {
                            logger.warn("Uso: iniciar_tcp [porta]");
                            break;
                        }
                        try {
                            int portaTCP = Integer.parseInt(partes[1]);
                            iniciarServidorTCP(portaTCP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "parar_tcp":
                        if (partes.length != 2) {
                            logger.warn("Uso: parar_tcp [porta]");
                            break;
                        }
                        try {
                            int portaTCP = Integer.parseInt(partes[1]);
                            pararServidorTCP(portaTCP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "iniciar_udp":
                        if (partes.length != 2) {
                            logger.warn("Uso: iniciar_udp [porta]");
                            break;
                        }
                        try {
                            int portaUDP = Integer.parseInt(partes[1]);
                            iniciarServidorUDP(portaUDP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "parar_udp":
                        if (partes.length != 2) {
                            logger.warn("Uso: parar_udp [porta]");
                            break;
                        }
                        try {
                            int portaUDP = Integer.parseInt(partes[1]);
                            pararServidorUDP(portaUDP);
                        } catch (NumberFormatException e) {
                            logger.warn("Porta inválida: {}", partes[1]);
                        }
                        break;
                    case "listar":
                        listarServidoresAtivos();
                        break;
                    case "sair":
                        parar();
                        logger.info("Encerrando Gateway.");
                        System.exit(0);
                        break;
                    default:
                        logger.warn("Comando desconhecido.");
                }
            }
        } catch (Exception e) {
            logger.error("Erro na Interface de Comandos: {}", e.getMessage(), e);
        }
    }

    /**
     * Método para listar os servidores ativos.
     */
    private void listarServidoresAtivos() {
        logger.info("Servidores HTTP Ativos: {}", getPortasHTTPAtivas());
        logger.info("Servidores TCP Ativos: {}", getPortasTCPAtivas());
        logger.info("Servidores UDP Ativos: {}", getPortasUDPAtivas());
    }
    
    
 // Add this getter method to retrieve the list of HTTP handlers
    public List<HTTPHandler> getHttpHandlers() {
        return httpHandlers; // Returns the list of HTTPHandlers
    }

    // Add this getter method to retrieve the list of TCP handlers
    public List<TCPHandler> getTcpHandlers() {
        return tcpHandlers; // Returns the list of TCPHandlers
    }

    // Add this getter method to retrieve the list of UDP handlers
    public List<UDPHandler> getUdpHandlers() {
        return udpHandlers; // Returns the list of UDPHandlers
    }

    /**
     * Método para parar o Gateway e todos os servidores internos.
     */
    public void parar() {
        try {
            // Parar servidores internos
            pararServidoresInternos();

            // Parar o servidor HTTP do Gateway
            if (serverHTTP != null) {
                serverHTTP.stop(0);
                logger.info("Servidor HTTP do Gateway parado.");
            }

            // Parar o ExecutorService
            if (executorServiceHTTP != null && !executorServiceHTTP.isShutdown()) {
                executorServiceHTTP.shutdown();
                logger.info("ExecutorService do Gateway parado.");
            }

            // Parar o UDPHandler principal
            for (UDPHandler handler : udpHandlers) {
                if (handler.isGateway()) {
                    handler.parar();
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Erro ao parar o Gateway: {}", e.getMessage(), e);
        }
    }

    /**
     * Método para parar todos os servidores internos.
     */
    private void pararServidoresInternos() {
        // Parar todos os servidores HTTP
        for (Iterator<HTTPHandler> it = httpHandlers.iterator(); it.hasNext();) {
            HTTPHandler handler = it.next();
            if (handler.isRunning()) {
                handler.parar();
                logger.info("Servidor HTTP parado na porta {}", handler.getPorta());
                it.remove();
            }
        }

        // Parar todos os servidores TCP
        for (Iterator<TCPHandler> it = tcpHandlers.iterator(); it.hasNext();) {
            TCPHandler handler = it.next();
            if (handler.isRunning()) {
                handler.parar();
                logger.info("Servidor TCP parado na porta {}", handler.getPorta());
                it.remove();
            }
        }

        // Parar todos os servidores UDP internos
        for (Iterator<UDPHandler> it = udpHandlers.iterator(); it.hasNext();) {
            UDPHandler handler = it.next();
            if (!handler.isGateway() && handler.isRunning()) {
                handler.parar();
                logger.info("Servidor UDP interno parado na porta {}", handler.getPorta());
                it.remove();
            }
        }
    }

    /**
     * Classe interna para tratar requisições HTTP recebidas pelo Gateway.
     */
    static class GatewayHttpHandler implements HttpHandler {
        private final Gateway gateway;
        private static final Logger logger = LoggerFactory.getLogger(GatewayHttpHandler.class);
        private final PaxosProposer proposer;  // Adiciona o Proposer ao handler

        public GatewayHttpHandler(Gateway gateway) {
            this.gateway = gateway;
            this.proposer = new PaxosProposer(gateway);  // Inicializa o Proposer
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String metodo = exchange.getRequestMethod();
            String caminho = exchange.getRequestURI().getPath();

            logger.info("Requisição recebida: {} {}", metodo, caminho);

            if ("POST".equalsIgnoreCase(metodo)) {
                if ("/cadastrarItem".equalsIgnoreCase(caminho)) {
                    handleCadastrarItem(exchange);
                } else if ("/registrarLance".equalsIgnoreCase(caminho)) {
                    handleRegistrarLance(exchange);
                } else {
                    responder(exchange, 404, "Endpoint não encontrado.");
                }
            } else {
                responder(exchange, 405, "Método não permitido.");
            }
        }

        /**
         * Método para lidar com a requisição de cadastro de item.
         */
        private void handleCadastrarItem(HttpExchange exchange) throws IOException {
            InputStream is = exchange.getRequestBody();
            String corpo = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // Supondo que o corpo esteja no formato: nome;descricao;precoInicial
            String[] partes = corpo.split(";");
            if (partes.length != 3) {
                responder(exchange, 400, "Formato inválido. Use: nome;descricao;precoInicial");
                return;
            }

            String nome = partes[0];
            String descricao = partes[1];
            double precoInicial;
            try {
                precoInicial = Double.parseDouble(partes[2]);
            } catch (NumberFormatException e) {
                responder(exchange, 400, "Preço inicial inválido.");
                return;
            }

         // Verifica se há um servidor HTTP interno disponível
            HTTPHandler httpHandler = gateway.getNextHTTPHandler();
            if (httpHandler != null && httpHandler.isRunning()) {
                // Propor o cadastro do item no Paxos
                String proposta = "CADASTRAR_ITEM;" + nome + ";" + descricao + ";" + precoInicial;
                if (proposer.propose(proposta)) {
                    // Consenso alcançado, prosseguir com o cadastro do item
                    responder(exchange, 200, "Item cadastrado com sucesso.");
                } else {
                    // Consenso falhou
                    responder(exchange, 500, "Falha ao cadastrar o item.");
                }
            } else {
                // Nenhum servidor HTTP interno disponível
                responder(exchange, 500, "Nenhum servidor HTTP interno disponível para processar a requisição.");
            }
        }

        /**
         * Método para lidar com a requisição de registrar lance.
         */
        private void handleRegistrarLance(HttpExchange exchange) throws IOException {
            // Ler o corpo da requisição
            InputStream is = exchange.getRequestBody();
            String corpo = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // Supondo que o corpo esteja no formato: idItem;clienteNome;valor
            String[] partes = corpo.split(";");
            if (partes.length != 3) {
                responder(exchange, 400, "Formato inválido. Use: idItem;clienteNome;valor");
                return;
            }

            int idItem;
            String clienteNome = partes[1];
            double valor;
            try {
                idItem = Integer.parseInt(partes[0]);
                valor = Double.parseDouble(partes[2]);
            } catch (NumberFormatException e) {
                responder(exchange, 400, "ID do item ou valor inválido.");
                return;
            }

            // Selecionar o próximo servidor HTTP interno usando Round Robin
            // Verifica se há um servidor HTTP interno disponível
            HTTPHandler httpHandler = gateway.getNextHTTPHandler();
            if (httpHandler != null && httpHandler.isRunning()) {
                // Propor o registro do lance no Paxos
                String proposta = "REGISTRAR_LANCE;" + idItem + ";" + clienteNome + ";" + valor;
                if (proposer.propose(proposta)) {
                    // Consenso alcançado, registrar o lance
                    responder(exchange, 200, "Lance registrado com sucesso.");
                } else {
                    // Consenso falhou
                    responder(exchange, 500, "Falha ao registrar o lance.");
                }
            } else {
                // Nenhum servidor HTTP interno disponível
                responder(exchange, 500, "Nenhum servidor HTTP interno disponível para processar a requisição.");
            }
        }

        /**
         * Método auxiliar para enviar requisições HTTP para servidores internos.
         * @param porta Porta do servidor interno.
         * @param caminho Caminho do endpoint.
         * @param corpo Corpo da requisição.
         * @return Resposta recebida do servidor interno ou mensagem de erro.
         */
        private String enviarRequisicaoHTTP(int porta, String caminho, String corpo) {
            try {
                URL url = new URL("http://localhost:" + porta + caminho);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int codigoResposta = conn.getResponseCode();
                InputStream is = (codigoResposta < HttpURLConnection.HTTP_BAD_REQUEST) ?
                        conn.getInputStream() : conn.getErrorStream();
                String resposta = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));
                return resposta;
            } catch (IOException e) {
                logger.error("Erro ao enviar requisição HTTP: {}", e.getMessage(), e);
                return "Erro ao processar a requisição via HTTP.";
            }
        }

        /**
         * Método auxiliar para enviar respostas HTTP.
         * @param exchange Objeto HttpExchange.
         * @param codigoStatus Código de status HTTP.
         * @param resposta Corpo da resposta.
         * @throws IOException
         */
        private static void responder(HttpExchange exchange, int codigoStatus, String resposta) throws IOException {
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codigoStatus, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    /**
     * Classe para tratar requisições TCP recebidas pelo Gateway.
     */
    static class ClienteGatewayTCP implements Runnable {
        private final Socket cliente;
        private final Gateway gateway;
        private static final Logger logger = LoggerFactory.getLogger(ClienteGatewayTCP.class);

        public ClienteGatewayTCP(Socket cliente, Gateway gateway) {
            this.cliente = cliente;
            this.gateway = gateway;
        }

        @Override
        public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(cliente.getOutputStream(), StandardCharsets.UTF_8));
            ) {
                String mensagem = in.readLine(); // Lê uma linha
                if (mensagem != null) {
                    logger.info("Recebido via Gateway TCP: {}", mensagem);
                    // Processamento da requisição
                    String[] partes = mensagem.split(";");
                    if (partes.length < 2) {
                        String resposta = "Erro: Formato inválido.\n";
                        out.write(resposta);
                        out.flush();
                        return;
                    }

                    String operacao = partes[0];
                    String resposta;

                    if ("cadastrarItem".equalsIgnoreCase(operacao)) {
                        if (partes.length != 4) {
                            resposta = "Erro: Formato inválido para cadastrarItem. Use: cadastrarItem;nome;descricao;precoInicial\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }
                        String nome = partes[1];
                        String descricao = partes[2];
                        double precoInicial;
                        try {
                            precoInicial = Double.parseDouble(partes[3]);
                        } catch (NumberFormatException e) {
                            resposta = "Erro: Preço inicial inválido.\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }

                        // Selecionar o próximo servidor TCP interno usando Round Robin
                        TCPHandler tcpHandler = gateway.getNextTCPHandler();
                        if (tcpHandler != null && tcpHandler.isRunning()) {
                            resposta = tcpHandler.cadastrarItem(nome, descricao, precoInicial);
                            out.write(resposta + "\n");
                            out.flush();

                            // Logar a porta para a qual a requisição foi redirecionada
                            logger.info("Requisição TCP redirecionada para o servidor TCP na porta {}", tcpHandler.getPorta());
                        } else {
                            // Enviar erro para o cliente quando não há servidores TCP internos
                            resposta = "Erro: Nenhum servidor TCP interno disponível para processar a requisição.\n";
                            logger.error("Nenhum servidor TCP interno disponível.");
                            out.write(resposta);  // Envia a resposta de erro ao JMeter
                            out.flush();
                        }
                    } else if ("registrarLance".equalsIgnoreCase(operacao)) {
                        if (partes.length != 4) {
                            resposta = "Erro: Formato inválido para registrarLance. Use: registrarLance;idItem;cliente;valor\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }
                        int idItem;
                        String clienteNome = partes[2];
                        double valor;
                        try {
                            idItem = Integer.parseInt(partes[1]);
                            valor = Double.parseDouble(partes[3]);
                        } catch (NumberFormatException e) {
                            resposta = "Erro: ID do item ou valor inválido.\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }

                        // Selecionar o próximo servidor TCP interno usando Round Robin
                        TCPHandler tcpHandler = gateway.getNextTCPHandler();
                        if (tcpHandler != null && tcpHandler.isRunning()) {
                            resposta = tcpHandler.registrarLance(idItem, clienteNome, valor);
                            out.write(resposta + "\n");
                            out.flush();

                            // Logar a porta para a qual a requisição foi redirecionada
                            logger.info("Requisição TCP redirecionada para o servidor TCP na porta {}", tcpHandler.getPorta());
                        } else {
                            // Enviar erro para o cliente quando não há servidores TCP internos
                            resposta = "Erro: Nenhum servidor TCP interno disponível para processar a requisição.\n";
                            logger.error("Nenhum servidor TCP interno disponível.");
                            out.write(resposta);  // Envia a resposta de erro ao JMeter
                            out.flush();
                        }
                    } else {
                        resposta = "Erro: Operação desconhecida.\n";
                        out.write(resposta);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                logger.error("Erro no Gateway TCP: {}", e.getMessage(), e);
            } finally {
                try {
                    logger.info("Cliente TCP desconectado no Gateway TCP: {}", cliente.getRemoteSocketAddress());
                    cliente.close();
                } catch (IOException e) {
                    logger.error("Erro ao fechar conexão TCP no Gateway: {}", e.getMessage(), e);
                }
            }
        }


    }
}
