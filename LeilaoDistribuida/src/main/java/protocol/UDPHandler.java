package protocol;

import database.BancoDados;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class UDPHandler {

    private static BancoDados bancoDados;

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        // Inicializar o banco de dados
        bancoDados = BancoDados.getInstance();

        try (DatagramSocket socket = new DatagramSocket(porta)) {
            System.out.println("Servidor UDP rodando na porta " + porta);

            // Registrar o servidor UDP no Gateway
            registrarNoGateway("udp", porta);  // Registrar dinamicamente no Gateway

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);  // Certifique-se que está lendo como UTF-8
                System.out.println("Recebido via UDP: " + mensagem);

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

                byte[] responseBytes = resposta.getBytes(StandardCharsets.UTF_8);  // Codificar resposta como UTF-8
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length, request.getAddress(), request.getPort());
                socket.send(response);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Método para registrar dinamicamente o servidor UDP no Gateway
    private static void registrarNoGateway(String tipo, int porta) {
        try {
            URL url = new URL("http://localhost:9000/registerServer");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            // Mensagem enviada para registrar: "udp;porta"
            String corpo = tipo + ";" + porta;
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = corpo.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            System.out.println("Servidor UDP registrado no Gateway com status: " + responseCode);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
