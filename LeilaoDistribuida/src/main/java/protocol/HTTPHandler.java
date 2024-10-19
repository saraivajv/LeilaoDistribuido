package protocol;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import database.BancoDados;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HTTPHandler {

    private static BancoDados bancoDados;

    public static void main(String[] args) {
        int porta = Integer.parseInt(args[0]);

        // Inicializar o banco de dados
        bancoDados = BancoDados.getInstance();

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(porta), 0);
            server.createContext("/cadastrarItem", new CadastrarItemHandler());
            server.createContext("/registrarLance", new RegistrarLanceHandler());
            server.setExecutor(null); // Cria um executor padrão
            server.start();
            System.out.println("Servidor HTTP rodando na porta " + porta);

            // Registrar o servidor no Gateway
            registrarNoGateway("http", porta);

        } catch (IOException e) {
            e.printStackTrace();
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

    static class CadastrarItemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                InputStream is = exchange.getRequestBody();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String body = reader.readLine();
                
                // Verificar os dados recebidos
                System.out.println("Dados recebidos: " + body);

                // Verifique se a string contém ao menos 3 partes
                String[] partes = body.split(";");
                if (partes.length != 3) {
                    throw new IllegalArgumentException("Dados inválidos. Esperado formato: nome;descricao;preco");
                }

                String nome = partes[0];
                String descricao = partes[1];
                double precoInicial;
                
                try {
                    precoInicial = Double.parseDouble(partes[2]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("O preço inicial deve ser um número.");
                }

                // Chamar o banco de dados para cadastrar o item
                int idItem = bancoDados.adicionarItem(nome, descricao, precoInicial);

                String resposta;
                if (idItem != -1) {
                    resposta = "Item cadastrado com sucesso. ID: " + idItem;
                } else {
                    throw new RuntimeException("Erro ao cadastrar o item no banco de dados.");
                }

                // Enviar a resposta de volta ao cliente via Gateway
                exchange.sendResponseHeaders(200, resposta.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(resposta.getBytes());
                os.close();

            } catch (Exception e) {
                // Logar a exceção para entender o que está acontecendo
                e.printStackTrace();
                System.out.println("Erro no processamento do item: " + e.getMessage());
                
                exchange.sendResponseHeaders(500, 0);
                OutputStream os = exchange.getResponseBody();
                os.write("Erro interno do servidor".getBytes(StandardCharsets.UTF_8));
                os.close();
            }
        }
    }



    static class RegistrarLanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            InputStream is = exchange.getRequestBody();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String body = reader.readLine();

            // Supondo que os dados vêm no formato idItem;clienteNome;valor
            String[] partes = body.split(";");
            int idItem = Integer.parseInt(partes[0]);
            String clienteNome = partes[1];
            double valor = Double.parseDouble(partes[2]);

            // Chamar o banco de dados para registrar o lance
            boolean sucesso = bancoDados.registrarLance(idItem, clienteNome, valor);

            String resposta;
            if (sucesso) {
                resposta = "Lance registrado com sucesso.";
            } else {
                resposta = "Erro ao registrar o lance.";
            }

            // Enviar a resposta de volta ao cliente via Gateway
            exchange.sendResponseHeaders(200, resposta.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(resposta.getBytes());
            os.close();
        }
    }
}
