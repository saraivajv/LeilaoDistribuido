package protocol;

import database.BancoDados;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class TCPHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TCPHandler.class);
    private int porta;
    private AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread thread;

    public TCPHandler(int porta) {
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
            logger.warn("Servidor TCP já está rodando na porta {}", porta);
            return;
        }
        try {
            serverSocket = new ServerSocket(porta);
            running.set(true);
            thread = new Thread(this);
            thread.start();
            logger.info("Servidor TCP iniciado na porta {}", porta);
        } catch (IOException e) {
            logger.error("Erro ao iniciar o servidor TCP na porta {}: {}", porta, e.getMessage(), e);
        }
    }

    public void parar() {
        if (!running.get()) {
            logger.warn("Servidor TCP não está rodando na porta {}", porta);
            return;
        }
        running.set(false);
        try {
            serverSocket.close();
            thread.join();
            logger.info("Servidor TCP parado na porta {}", porta);
        } catch (IOException | InterruptedException e) {
            logger.error("Erro ao parar o servidor TCP na porta {}: {}", porta, e.getMessage(), e);
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Socket cliente = serverSocket.accept();
                logger.info("Cliente TCP conectado: {}", cliente.getRemoteSocketAddress());
                new Thread(new ClienteTCP(cliente)).start();
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Erro ao aceitar conexão TCP na porta {}: {}", porta, e.getMessage(), e);
                }
            }
        }
    }

    // Classe para tratar cada cliente TCP
    class ClienteTCP implements Runnable {
        private Socket cliente;
        private static final Logger logger = LoggerFactory.getLogger(ClienteTCP.class);

        public ClienteTCP(Socket cliente) {
            this.cliente = cliente;
        }

        @Override
        public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(cliente.getOutputStream(), StandardCharsets.UTF_8));
            ) {
                String mensagem = in.readLine(); // Lê uma linha
                if (mensagem != null) {
                    logger.info("Recebido via TCP: {}", mensagem);
                    // Processamento da requisição
                    String[] partes = mensagem.split(";");
                    if (partes.length < 2) {
                        String resposta = "Formato inválido.\n";
                        out.write(resposta);
                        out.flush();
                        return;
                    }

                    String operacao = partes[0];
                    String resposta;

                    if ("cadastrarItem".equalsIgnoreCase(operacao)) {
                        if (partes.length != 4) {
                            resposta = "Formato inválido para cadastrarItem. Use: cadastrarItem;nome;descricao;precoInicial\n";
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
                            resposta = "Preço inicial inválido.\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }

                        // Chama o método cadastrarItem do TCPHandler atual
                        resposta = cadastrarItem(nome, descricao, precoInicial);
                        out.write(resposta);
                        out.flush();
                    } else if ("registrarLance".equalsIgnoreCase(operacao)) {
                        if (partes.length != 4) {
                            resposta = "Formato inválido para registrarLance. Use: registrarLance;idItem;cliente;valor\n";
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
                            resposta = "ID do item ou valor inválido.\n";
                            out.write(resposta);
                            out.flush();
                            return;
                        }

                        // Chama o método registrarLance do TCPHandler atual
                        resposta = registrarLance(idItem, clienteNome, valor);
                        out.write(resposta);
                        out.flush();
                    } else {
                        resposta = "Operação desconhecida.\n";
                        out.write(resposta);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                logger.error("Erro no cliente TCP: {}", e.getMessage(), e);
            } finally {
                try {
                    logger.info("Cliente TCP desconectado: {}", cliente.getRemoteSocketAddress());
                    cliente.close();
                } catch (IOException e) {
                    logger.error("Erro ao fechar conexão TCP: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Cadastra um novo item no banco de dados via TCP.
     *
     * @param nome         Nome do item
     * @param descricao    Descrição do item
     * @param precoInicial Preço inicial do item
     * @return Resposta do cadastro
     */
    public String cadastrarItem(String nome, String descricao, double precoInicial) {
        BancoDados db = BancoDados.getInstance();
        int id = db.adicionarItem(nome, descricao, precoInicial);
        if (id != -1) {
            return "Item cadastrado com ID: " + id;
        } else {
            return "Erro ao cadastrar item.";
        }
    }

    /**
     * Registra um lance para um item no banco de dados via TCP.
     *
     * @param idItem      ID do item
     * @param clienteNome Nome do cliente
     * @param valor       Valor do lance
     * @return Resposta do registro do lance
     */
    public String registrarLance(int idItem, String clienteNome, double valor) {
        BancoDados db = BancoDados.getInstance();
        boolean sucesso = db.registrarLance(idItem, clienteNome, valor);
        if (sucesso) {
            return "Lance registrado com sucesso.";
        } else {
            return "Lance inferior ao maior lance atual.";
        }
    }
}
