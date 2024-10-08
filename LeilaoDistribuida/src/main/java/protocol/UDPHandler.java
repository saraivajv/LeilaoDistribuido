package protocol;

import gateway.Gateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UDPHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(UDPHandler.class);
    private final int porta;
    private volatile boolean running = false;
    private DatagramSocket socket;
    private final Gateway gateway;
    private final boolean isGateway;
    private Thread thread;

    /**
     * Construtor para UDPHandler.
     *
     * @param porta     Porta na qual o UDPHandler irá escutar.
     * @param gateway   Referência ao Gateway para redirecionamento.
     * @param isGateway Indica se este UDPHandler é o Gateway principal.
     */
    public UDPHandler(int porta, Gateway gateway, boolean isGateway) {
        this.porta = porta;
        this.gateway = gateway;
        this.isGateway = isGateway;
    }

    public int getPorta() {
        return porta;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Retorna se este UDPHandler é o Gateway principal.
     *
     * @return true se for o Gateway principal, false caso contrário.
     */
    public boolean isGateway() {
        return isGateway;
    }

    /**
     * Inicia o UDPHandler.
     */
    public void iniciar() {
        try {
            socket = new DatagramSocket(porta);
            running = true;
            thread = new Thread(this);
            thread.start();
            if (isGateway) {
                logger.info("UDPHandler do Gateway iniciado na porta {}", porta);
            } else {
                logger.info("Servidor UDP interno iniciado na porta {}", porta);
            }
        } catch (SocketException e) {
            if (isGateway) {
                logger.error("Erro ao iniciar UDPHandler do Gateway na porta {}: {}", porta, e.getMessage(), e);
            } else {
                logger.error("Erro ao iniciar servidor UDP interno na porta {}: {}", porta, e.getMessage(), e);
            }
        }
    }

    /**
     * Para o UDPHandler.
     */
    public void parar() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (isGateway) {
            logger.info("UDPHandler do Gateway parado na porta {}", porta);
        } else {
            logger.info("Servidor UDP interno parado na porta {}", porta);
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        while (running) {
            try {
                DatagramPacket packetRecebido = new DatagramPacket(buffer, buffer.length);
                socket.receive(packetRecebido);

                String mensagem = new String(packetRecebido.getData(), 0, packetRecebido.getLength(), StandardCharsets.UTF_8);
                if (isGateway) {
                    logger.info("Requisição UDP recebida na porta {}: {}", porta, mensagem);
                    // Selecionar o próximo servidor UDP interno usando Round Robin
                    UDPHandler internalUDP = gateway.getNextUDPHandler();
                    if (internalUDP != null && internalUDP.isRunning()) {
                        // Enviar a mensagem para o servidor UDP interno
                        DatagramSocket socketInterno = new DatagramSocket();
                        InetAddress address = InetAddress.getByName("localhost");
                        DatagramPacket packetParaEnviar = new DatagramPacket(
                                mensagem.getBytes(StandardCharsets.UTF_8),
                                mensagem.length(),
                                address,
                                internalUDP.getPorta()
                        );
                        socketInterno.send(packetParaEnviar);
                        logger.info("Requisição UDP redirecionada para o servidor UDP interno na porta {}", internalUDP.getPorta());

                        // Receber a resposta do servidor UDP interno
                        DatagramPacket respostaInterna = new DatagramPacket(new byte[1024], 1024);
                        socketInterno.setSoTimeout(5000); // Timeout de 5 segundos
                        try {
                            socketInterno.receive(respostaInterna);
                            String resposta = new String(respostaInterna.getData(), 0, respostaInterna.getLength(), StandardCharsets.UTF_8);

                            // Enviar a resposta de volta para o cliente original
                            DatagramPacket packetResposta = new DatagramPacket(
                                    resposta.getBytes(StandardCharsets.UTF_8),
                                    resposta.length(),
                                    packetRecebido.getAddress(),
                                    packetRecebido.getPort()
                            );
                            socket.send(packetResposta);
                            logger.info("Resposta UDP enviada para o cliente na porta {}", packetRecebido.getPort());
                        } catch (SocketTimeoutException e) {
                            logger.warn("Timeout ao receber resposta do servidor UDP interno na porta {}", internalUDP.getPorta());
                            String erro = "Erro: Timeout ao processar a requisição via UDP.";
                            DatagramPacket packetErro = new DatagramPacket(
                                    erro.getBytes(StandardCharsets.UTF_8),
                                    erro.length(),
                                    packetRecebido.getAddress(),
                                    packetRecebido.getPort()
                            );
                            socket.send(packetErro);
                        } finally {
                            socketInterno.close();
                        }
                    } else {
                        logger.warn("Nenhum servidor UDP interno disponível para processar a requisição.");
                        String erro = "Erro: Nenhum servidor UDP interno disponível.";
                        DatagramPacket packetErro = new DatagramPacket(
                                erro.getBytes(StandardCharsets.UTF_8),
                                erro.length(),
                                packetRecebido.getAddress(),
                                packetRecebido.getPort()
                        );
                        socket.send(packetErro);
                    }
                } else {
                    // Servidor UDP interno processa a requisição
                    logger.info("Requisição UDP recebida na porta {}: {}", porta, mensagem);
                    String resposta = processarRequisicao(mensagem);

                    // Enviar a resposta de volta para o cliente original
                    DatagramPacket packetResposta = new DatagramPacket(
                            resposta.getBytes(StandardCharsets.UTF_8),
                            resposta.length(),
                            packetRecebido.getAddress(),
                            packetRecebido.getPort()
                    );
                    socket.send(packetResposta);
                    logger.info("Resposta UDP enviada para o cliente na porta {}", packetRecebido.getPort());
                }
            } catch (SocketException e) {
                if (running) {
                    if (isGateway) {
                        logger.error("Erro no UDPHandler do Gateway na porta {}: {}", porta, e.getMessage(), e);
                    } else {
                        logger.error("Erro no servidor UDP interno na porta {}: {}", porta, e.getMessage(), e);
                    }
                }
            } catch (IOException e) {
                if (isGateway) {
                    logger.error("Erro ao processar requisição UDP no Gateway na porta {}: {}", porta, e.getMessage(), e);
                } else {
                    logger.error("Erro ao processar requisição UDP no servidor UDP interno na porta {}: {}", porta, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Método para processar a requisição UDP no servidor UDP interno.
     * Aqui você pode implementar a lógica de negócio necessária.
     *
     * @param mensagem Mensagem recebida.
     * @return Resposta a ser enviada.
     */
    private String processarRequisicao(String mensagem) {
        // Exemplo de processamento simples
        // Você deve substituir este método pela lógica real de processamento
        String[] partes = mensagem.split(";");
        if (partes.length < 4) {
            return "Formato inválido. Use: cadastrarItem;nome;descricao;precoInicial";
        }

        String operacao = partes[0];
        if ("cadastrarItem".equalsIgnoreCase(operacao)) {
            String nome = partes[1];
            String descricao = partes[2];
            String precoInicial = partes[3];
            // Aqui você pode adicionar lógica para armazenar o item, gerar um ID, etc.
            // Para este exemplo, vamos apenas retornar uma confirmação
            int idItem = new java.util.Random().nextInt(1000) + 1; // Gerar um ID aleatório
            return "Item cadastrado com ID: " + idItem;
        }

        // Outras operações podem ser adicionadas aqui

        return "Operação desconhecida.";
    }
}
