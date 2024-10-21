package protocol;

import database.BancoDados;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UDPHandler {

    private static final Logger logger = LoggerFactory.getLogger(UDPHandler.class);
    private static BancoDados bancoDados;

    // Fila para agrupar requisições (Request Batch)
    private static final List<String> requestBatch = new ArrayList<>();
    private static final int BATCH_SIZE = 5; // Tamanho máximo do batch
    private static final long BATCH_INTERVAL = 10; // Intervalo de processamento do batch em segundos

    // Executor para agendar o processamento do batch
    private static final ScheduledExecutorService batchScheduler = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        bancoDados = BancoDados.getInstance();

        // Agendar o processamento do batch em intervalos regulares
        batchScheduler.scheduleAtFixedRate(UDPHandler::processarBatch, BATCH_INTERVAL, BATCH_INTERVAL, TimeUnit.SECONDS);

        try (DatagramSocket socket = new DatagramSocket(porta)) {
            logger.info("Servidor UDP rodando na porta " + porta);

            registrarNoGateway("udp", porta);  // Registrar o servidor no gateway

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);

                logger.info("Recebido via UDP: " + mensagem);

                // Se for um "ping", responder com "pong" (para healthcheck)
                if ("ping".equals(mensagem)) {
                    logger.info("Recebida mensagem de 'ping'. Respondendo com 'Pong'.");
                    byte[] responseBytes = "Pong".getBytes(StandardCharsets.UTF_8);
                    DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length, request.getAddress(), request.getPort());
                    socket.send(response);  // Responder ao cliente Gateway
                    continue;
                }

                // Adiciona a requisição ao batch
                synchronized (requestBatch) {
                    requestBatch.add(mensagem);
                    logger.info("Requisição adicionada ao batch. Tamanho atual: " + requestBatch.size());
                }

                // Envia uma resposta imediatamente ao JMeter, mesmo antes do processamento do batch
                byte[] responseBytes = "Requisição recebida. Será processada no próximo batch.".getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length, request.getAddress(), request.getPort());
                socket.send(response);  // Responder ao cliente JMeter

                // Se o batch atingir o tamanho máximo, processar imediatamente
                if (requestBatch.size() >= BATCH_SIZE) {
                    processarBatch();
                }
            }

        } catch (IOException e) {
            logger.error("Erro no servidor UDP: " + e.getMessage(), e);
        }

    }

    // Método para processar um batch de requisições
    private static void processarBatch() {
        synchronized (requestBatch) {
            if (requestBatch.isEmpty()) {
                logger.info("Nenhuma requisição no batch para processar.");
                return; // Se não houver requisições no batch, sair
            }

            // Log para indicar o início do processamento em batch
            logger.info("Iniciando processamento do batch de requisições. Tamanho do batch: " + requestBatch.size());

            // Clonar a lista de requisições para evitar conflitos de concorrência
            List<String> batch = new ArrayList<>(requestBatch);
            requestBatch.clear();  // Limpar a fila original após clonar

            // Processar cada requisição no batch
            for (String mensagem : batch) {
                String resposta = processarRequisicao(mensagem);

                // Log para cada requisição processada no batch
                logger.info("Requisição processada: " + mensagem + ". Resposta: " + resposta);
            }

            // Log para indicar o fim do processamento em batch
            logger.info("Processamento do batch concluído. Total de requisições processadas: " + batch.size());
        }
    }

    // Método para processar uma única requisição
    private static String processarRequisicao(String mensagem) {
        String resposta;

        if (mensagem.startsWith("cadastrarItem")) {
            String[] partes = mensagem.split(";");
            if (partes.length == 4) {
                String nome = partes[1];
                String descricao = partes[2];
                double precoInicial = Double.parseDouble(partes[3]);
                int idItem = bancoDados.adicionarItem(nome, descricao, precoInicial);
                resposta = (idItem != -1) ? "Item cadastrado com sucesso: " + idItem : "Erro ao cadastrar item.";
            } else {
                resposta = "Mensagem inválida";
            }
        } else if (mensagem.startsWith("registrarLance")) {
            String[] partes = mensagem.split(";");
            if (partes.length == 4) {
                int idItem = Integer.parseInt(partes[1]);
                String clienteNome = partes[2];
                double valor = Double.parseDouble(partes[3]);
                boolean sucesso = bancoDados.registrarLance(idItem, clienteNome, valor);
                resposta = sucesso ? "Lance registrado com sucesso." : "Erro ao registrar lance.";
            } else {
                resposta = "Mensagem inválida";
            }
        } else {
            resposta = "Mensagem inválida";
        }

        return resposta;
    }

    // Registrar no Gateway
    private static void registrarNoGateway(String tipo, int porta) {
        try {
            URL url = new URL("http://localhost:9000/registerServer");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF_8");

            String corpo = tipo + ";" + porta;
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            logger.info("Servidor UDP registrado no Gateway com status: " + responseCode + ", na porta: " + porta);

        } catch (IOException e) {
            logger.error("Erro ao registrar no Gateway: " + e.getMessage(), e);
        }
    }
}
