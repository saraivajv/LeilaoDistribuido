package protocol;

import database.BancoDados;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TCPHandler {
    private static final Logger logger = LoggerFactory.getLogger(TCPHandler.class);
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
        batchScheduler.scheduleAtFixedRate(TCPHandler::processarBatch, BATCH_INTERVAL, BATCH_INTERVAL, TimeUnit.SECONDS);

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            logger.info("Servidor TCP rodando na porta {}", porta);
            registrarNoGateway(porta); // Registrar no gateway

            while (true) {
                Socket cliente = serverSocket.accept();
                new Thread(new ClienteTCPHandler(cliente)).start();
            }

        } catch (IOException e) {
            logger.error("Erro ao iniciar o servidor TCP: " + e.getMessage(), e);
        }
    }

    // Registrar o servidor no Gateway
    private static void registrarNoGateway(int porta) {
        try {
            URL url = new URL("http://localhost:9000/registerServer");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            String corpo = "tcp;" + porta;
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            logger.info("Servidor TCP registrado no Gateway com status: " + responseCode);

        } catch (IOException e) {
            logger.error("Erro ao registrar servidor TCP no Gateway: " + e.getMessage(), e);
        }
    }

    static class ClienteTCPHandler implements Runnable {
        private final Socket cliente;

        public ClienteTCPHandler(Socket cliente) {
            this.cliente = cliente;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(cliente.getOutputStream(), StandardCharsets.UTF_8))) {

                String mensagem = in.readLine();
                if (mensagem != null) {
                    logger.info("Requisição recebida no TCPHandler: " + mensagem);

                    // Adicionar a requisição na fila do batch
                    synchronized (requestBatch) {
                        requestBatch.add(mensagem);
                        logger.info("Requisição adicionada ao batch. Tamanho atual do batch: " + requestBatch.size());

                        // Verifica se o batch atingiu o tamanho máximo para processar imediatamente
                        if (requestBatch.size() >= BATCH_SIZE) {
                            logger.info("Tamanho máximo do batch atingido. Processando batch...");
                            processarBatch();
                        }
                    }

                    // Resposta imediata para o cliente (a requisição será processada no batch)
                    out.write("Requisição recebida e será processada em batch.\n");
                    out.flush();
                } else {
                    logger.warn("Nenhuma mensagem recebida do cliente.");
                    out.write("Erro: Nenhuma mensagem recebida\n");
                    out.flush();
                }

            } catch (IOException e) {
                logger.error("Erro no TCPHandler: " + e.getMessage(), e);
            } finally {
                try {
                    cliente.close();
                } catch (IOException e) {
                    logger.error("Erro ao fechar conexão do cliente: " + e.getMessage(), e);
                }
            }
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
}
