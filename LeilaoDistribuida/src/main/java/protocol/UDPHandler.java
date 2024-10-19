package protocol;

import database.BancoDados;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class UDPHandler {

    private static final Logger logger = LoggerFactory.getLogger(UDPHandler.class);  // Logger declaration
    private static BancoDados bancoDados;

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        // Inicializar o banco de dados
        bancoDados = BancoDados.getInstance();

        try (DatagramSocket socket = new DatagramSocket(porta)) {
            logger.info("Servidor UDP rodando na porta " + porta);

            // Call to registrarNoGateway to register this UDP server at the Gateway
            registrarNoGateway("udp", porta);  // Register the server dynamically

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);  
                
                logger.info("Recebido via UDP: " + mensagem);

                String resposta;

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

                logger.info("Resposta gerada para o cliente: " + resposta);

                byte[] responseBytes = resposta.getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length, request.getAddress(), request.getPort());
                socket.send(response);
                logger.info("Resposta enviada para o cliente.");
            }

        } catch (IOException e) {
            logger.error("Erro no servidor UDP: " + e.getMessage(), e);
        }
    }

    // Method to register the server in the Gateway
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
            logger.info("Servidor UDP registrado no Gateway com status: " + responseCode + ", na porta: " + porta);

        } catch (IOException e) {
            logger.error("Erro ao registrar no Gateway: " + e.getMessage(), e);
        }
    }
}
