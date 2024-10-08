package protocol;

import database.BancoDados;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class HTTPHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(HTTPHandler.class);
    private int porta;
    private AtomicBoolean running = new AtomicBoolean(false);
    private HttpServer serverHTTP;
    private ExecutorService executorService;

    public HTTPHandler(int porta) {
        this.porta = porta;
    }

    public int getPorta() {
        return porta;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void iniciar() {
        if (running.get()) {
            logger.warn("Servidor HTTP já está rodando na porta {}", porta);
            return;
        }
        try {
            serverHTTP = HttpServer.create(new InetSocketAddress(porta), 0);
            serverHTTP.createContext("/cadastrarItem", new CadastrarItemHandler());
            serverHTTP.createContext("/registrarLance", new RegistrarLanceHandler());
            executorService = Executors.newFixedThreadPool(10);
            serverHTTP.setExecutor(executorService);
            serverHTTP.start();
            running.set(true);
            logger.info("Servidor HTTP iniciado na porta {}", porta);
        } catch (IOException e) {
            logger.error("Erro ao iniciar o servidor HTTP na porta {}: {}", porta, e.getMessage(), e);
        }
    }

    public void parar() {
        if (!running.get()) {
            logger.warn("Servidor HTTP não está rodando na porta {}", porta);
            return;
        }
        serverHTTP.stop(0);
        executorService.shutdown();
        running.set(false);
        logger.info("Servidor HTTP parado na porta {}", porta);
    }

    @Override
    public void run() {
        iniciar();
    }

    /**
     * Handler para processar requisições de cadastro de item via HTTP.
     */
    static class CadastrarItemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                responder(exchange, 405, "Método não permitido.");
                return;
            }

            // Ler o corpo da requisição
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

            // Processar a requisição via BancoDados
            BancoDados db = BancoDados.getInstance();
            int id = db.adicionarItem(nome, descricao, precoInicial);
            String resposta;
            if (id != -1) {
                resposta = "Item cadastrado com ID: " + id;
                responder(exchange, 200, resposta);
            } else {
                resposta = "Erro ao cadastrar item.";
                responder(exchange, 500, resposta);
            }
        }
    }

    /**
     * Handler para processar requisições de registro de lance via HTTP.
     */
    static class RegistrarLanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                responder(exchange, 405, "Método não permitido.");
                return;
            }

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

            // Processar a requisição via BancoDados
            BancoDados db = BancoDados.getInstance();
            boolean sucesso = db.registrarLance(idItem, clienteNome, valor);
            String resposta;
            if (sucesso) {
                resposta = "Lance registrado com sucesso.";
                responder(exchange, 200, resposta);
            } else {
                resposta = "Lance inferior ao maior lance atual.";
                responder(exchange, 400, resposta);
            }
        }
    }

    /**
     * Método auxiliar para enviar respostas HTTP.
     */
    private static void responder(HttpExchange exchange, int codigoStatus, String resposta) throws IOException {
        byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigoStatus, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
