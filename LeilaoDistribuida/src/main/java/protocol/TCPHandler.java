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

public class TCPHandler {
    private static final Logger logger = LoggerFactory.getLogger(TCPHandler.class);  // Definição do logger
    private static BancoDados bancoDados;

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        // Inicializar o banco de dados
        bancoDados = BancoDados.getInstance();

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            logger.info("Servidor TCP rodando na porta {}", porta);

            // Registrar este servidor TCP no gateway
            registrarNoGateway(porta);

            // Aguardar conexões dos clientes
            while (true) {
                Socket cliente = serverSocket.accept();
                new Thread(new ClienteTCPHandler(cliente)).start();
            }

        } catch (IOException e) {
            logger.error("Erro ao iniciar o servidor TCP: " + e.getMessage(), e);
        }
    }

    // Método de registro dinâmico no Gateway
    private static void registrarNoGateway(int porta) {
        try {
            // URL para o endpoint de registro do Gateway
            URL url = new URL("http://localhost:9000/registerServer");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            // Corpo da mensagem no formato esperado: "tcp;porta"
            String corpo = "tcp;" + porta;
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Verificar o código de resposta do Gateway
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

                logger.info("Aguardando dados do cliente...");

                String mensagem = in.readLine();  // Lê a mensagem do cliente
                if (mensagem != null) {
                    logger.info("Mensagem recebida no TCPHandler: " + mensagem);
                    String resposta;

                    // Processar o comando conforme necessário
                    if (mensagem.startsWith("cadastrarItem")) {
                        String[] partes = mensagem.split(";");
                        if (partes.length == 4) {
                            String nome = partes[1];
                            String descricao = partes[2];
                            double precoInicial = Double.parseDouble(partes[3]);
                            int idItem = bancoDados.adicionarItem(nome, descricao, precoInicial);
                            resposta = (idItem != -1) ? "Item cadastrado com sucesso." : "Erro ao cadastrar item.";
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

                    logger.info("Resposta gerada no TCPHandler: " + resposta);
                    out.write(resposta + "\n");
                    out.flush();
                } else {
                    logger.warn("Nenhuma mensagem recebida do cliente.");
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
}
