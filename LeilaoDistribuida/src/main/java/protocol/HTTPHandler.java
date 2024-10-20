package protocol;

import database.BancoDados;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HTTPHandler {

    private static BancoDados bancoDados;
    private static final Logger logger = LoggerFactory.getLogger(HTTPHandler.class);

    // Fila para agrupar requisições (Request Batch)
    private static final List<String> requestBatch = new ArrayList<>();
    private static final int BATCH_SIZE = 5; // Tamanho máximo do batch
    private static final long BATCH_INTERVAL = 10; // Intervalo de processamento do batch em segundos

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        bancoDados = BancoDados.getInstance();

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(porta), 0);
            server.createContext("/cadastrarItem", new CadastrarItemHandler());
            server.createContext("/registrarLance", new RegistrarLanceHandler());
            server.createContext("/healthcheck", new HealthCheckHandler());  // Adiciona o contexto de healthcheck
            server.setExecutor(null); // Cria um executor padrão
            server.start();
            System.out.println("Servidor HTTP rodando na porta " + porta);

            registrarNoGateway("http", porta);  // Registrar no Gateway

            // Iniciar o agendador para processar o batch a cada intervalo
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(HTTPHandler::processarBatch, BATCH_INTERVAL, BATCH_INTERVAL, TimeUnit.SECONDS);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // Novo handler para o healthcheck
    static class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Responder com status 200 OK
            exchange.sendResponseHeaders(200, -1);  // -1 significa que não há corpo de resposta
            exchange.close();
        }
    }

    private static void registrarNoGateway(String tipo, int porta) {
        try {
            URL url = new URL("http://localhost:9000/registerServer");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            String corpo = tipo + ";" + porta;
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            System.out.println("Registrado no Gateway com status: " + responseCode);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Handler para a rota /cadastrarItem
    static class CadastrarItemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                InputStream is = exchange.getRequestBody();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String body = reader.readLine();

                logger.info("Dados recebidos (cadastrarItem): " + body);

                synchronized (requestBatch) {
                    requestBatch.add("cadastrarItem;" + body); // Adiciona um identificador para o tipo de requisição
                    logger.info("Requisição de cadastrarItem adicionada ao batch. Tamanho atual: " + requestBatch.size());
                }

                String resposta = "Requisição recebida e adicionada ao batch.";
                exchange.sendResponseHeaders(200, resposta.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(resposta.getBytes());
                os.close();

                // Se o batch atingir o tamanho máximo, processar imediatamente
                if (requestBatch.size() >= BATCH_SIZE) {
                    processarBatch();
                }

            } catch (Exception e) {
                logger.error("Erro no processamento do item: " + e.getMessage(), e);
                exchange.sendResponseHeaders(500, 0);
                OutputStream os = exchange.getResponseBody();
                os.write("Erro interno do servidor".getBytes(StandardCharsets.UTF_8));
                os.close();
            }
        }
    }

    // Handler para a rota /registrarLance
    static class RegistrarLanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                InputStream is = exchange.getRequestBody();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String body = reader.readLine();

                logger.info("Dados recebidos (registrarLance): " + body);

                synchronized (requestBatch) {
                    requestBatch.add("registrarLance;" + body); // Adiciona um identificador para o tipo de requisição
                    logger.info("Requisição de registrarLance adicionada ao batch. Tamanho atual: " + requestBatch.size());
                }

                String resposta = "Requisição de lance recebida e adicionada ao batch.";
                exchange.sendResponseHeaders(200, resposta.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(resposta.getBytes());
                os.close();

                // Se o batch atingir o tamanho máximo, processar imediatamente
                if (requestBatch.size() >= BATCH_SIZE) {
                    processarBatch();
                }

            } catch (Exception e) {
                logger.error("Erro no processamento do lance: " + e.getMessage(), e);
                exchange.sendResponseHeaders(500, 0);
                OutputStream os = exchange.getResponseBody();
                os.write("Erro interno do servidor".getBytes(StandardCharsets.UTF_8));
                os.close();
            }
        }
    }

    // Processar o batch de requisições
    private static void processarBatch() {
        List<String> batchParaProcessar;

        synchronized (requestBatch) {
            if (requestBatch.isEmpty()) {
                logger.info("Nenhuma requisição no batch para processar.");
                return;
            }

            // Copia as requisições do batch e limpa o batch
            batchParaProcessar = new ArrayList<>(requestBatch);
            requestBatch.clear();
        }

        logger.info("Processando batch com " + batchParaProcessar.size() + " requisições.");

        for (String request : batchParaProcessar) {
            try {
                String[] partes = request.split(";", 2); // Tipo da requisição e dados separados
                String tipoRequisicao = partes[0];
                String dadosRequisicao = partes[1];

                if ("cadastrarItem".equals(tipoRequisicao)) {
                    String[] dados = dadosRequisicao.split(";");
                    String nome = dados[0];
                    String descricao = dados[1];
                    double precoInicial = Double.parseDouble(dados[2]);

                    // Chama o banco de dados para cadastrar o item
                    int idItem = bancoDados.adicionarItem(nome, descricao, precoInicial);
                    if (idItem != -1) {
                        logger.info("Item cadastrado com sucesso: " + nome + " (ID: " + idItem + ")");
                    } else {
                        logger.error("Erro ao cadastrar o item: " + nome);
                    }
                } else if ("registrarLance".equals(tipoRequisicao)) {
                    String[] dados = dadosRequisicao.split(";");
                    int idItem = Integer.parseInt(dados[0]);
                    String cliente = dados[1];
                    double valor = Double.parseDouble(dados[2]);

                    // Chama o banco de dados para registrar o lance
                    boolean sucesso = bancoDados.registrarLance(idItem, cliente, valor);
                    if (sucesso) {
                        logger.info("Lance registrado com sucesso para o item " + idItem);
                    } else {
                        logger.error("Erro ao registrar lance para o item " + idItem);
                    }
                }

            } catch (Exception e) {
                logger.error("Erro ao processar requisição do batch: " + e.getMessage(), e);
            }
        }
    }

}
