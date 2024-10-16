package protocol;

import database.BancoDados;
import gateway.Gateway;
import paxos.PaxosAcceptor;

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
    private int promisedProposal = -1;  // Armazena a maior proposta prometida
    private int acceptedProposal = -1;  // Armazena a maior proposta aceita
    private String acceptedValue = null;  // Armazena o valor da proposta aceita
    private final PaxosAcceptor paxosAcceptor;

    // Envia um Prepare e retorna se pode prometer aceitar propostas maiores
    public boolean sendPrepare(int proposalNumber) {
        if (proposalNumber > promisedProposal) {
            promisedProposal = proposalNumber;
            return true;  // Promete não aceitar propostas menores
        }
        return false;  // Rejeita a proposta
    }

    // Envia um Accept e retorna se aceita a proposta
    public boolean sendAccept(int proposalNumber, String value) {
        if (proposalNumber >= promisedProposal) {
            acceptedProposal = proposalNumber;
            acceptedValue = value;
            return true;  // Aceita a proposta
        }
        return false;  // Rejeita a proposta
    }

    public String getAcceptedValue() {
        return acceptedValue;
    }

    public UDPHandler(int porta, Gateway gateway, boolean isGateway) {
        this.porta = porta;
        this.gateway = gateway;
        this.isGateway = isGateway;
        this.paxosAcceptor = new PaxosAcceptor();
    }

    public int getPorta() {
        return porta;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isGateway() {
        return isGateway;
    }

    public void iniciar() {
        try {
            socket = new DatagramSocket(porta);
            running = true;
            thread = new Thread(this);
            thread.start();
            logger.info(isGateway ? "UDPHandler do Gateway iniciado na porta {}" : "Servidor UDP interno iniciado na porta {}", porta);
        } catch (SocketException e) {
            logger.error("Erro ao iniciar o UDPHandler na porta {}: {}", porta, e.getMessage(), e);
        }
    }

    public void parar() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        logger.info(isGateway ? "UDPHandler do Gateway parado na porta {}" : "Servidor UDP interno parado na porta {}", porta);
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
                        // Redirecionar para o servidor interno
                        DatagramSocket socketInterno = new DatagramSocket();
                        InetAddress address = InetAddress.getByName("localhost");
                        DatagramPacket packetParaEnviar = new DatagramPacket(
                                mensagem.getBytes(StandardCharsets.UTF_8),
                                mensagem.length(),
                                address,
                                internalUDP.getPorta()
                        );
                        socketInterno.send(packetParaEnviar);

                        // Esperar resposta do servidor interno
                        DatagramPacket respostaInterna = new DatagramPacket(new byte[1024], 1024);
                        socketInterno.setSoTimeout(5000);
                        try {
                            socketInterno.receive(respostaInterna);
                            String resposta = new String(respostaInterna.getData(), 0, respostaInterna.getLength(), StandardCharsets.UTF_8);

                            // Verificar se a resposta contém erro
                            if (resposta.startsWith("Erro:")) {
                                logger.error("Erro retornado pelo servidor interno: {}", resposta);
                                enviarErroUDP(packetRecebido, resposta);
                            } else {
                                // Enviar a resposta para o cliente original
                                DatagramPacket packetResposta = new DatagramPacket(
                                        resposta.getBytes(StandardCharsets.UTF_8),
                                        resposta.length(),
                                        packetRecebido.getAddress(),
                                        packetRecebido.getPort()
                                );
                                socket.send(packetResposta);
                                logger.info("Resposta UDP enviada para o cliente na porta {}", packetRecebido.getPort());
                            }
                        } catch (SocketTimeoutException e) {
                            String erro = "Erro: Timeout ao processar a requisição via UDP.";
                            enviarErroUDP(packetRecebido, erro);
                        } finally {
                            socketInterno.close();
                        }
                    } else {
                        String erro = "Erro: Nenhum servidor UDP interno disponível.";
                        enviarErroUDP(packetRecebido, erro);
                    }
                } else {
                    // Servidor interno processa a requisição
                    logger.info("Requisição UDP recebida na porta {}: {}", porta, mensagem);
                    String resposta = processarRequisicao(mensagem);  // Chama o método processarRequisicao
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
                    logger.error("Erro no UDPHandler na porta {}: {}", porta, e.getMessage(), e);
                }
            } catch (IOException e) {
                logger.error("Erro ao processar requisição UDP na porta {}: {}", porta, e.getMessage(), e);
            }
        }
    }

    /**
     * Processa a requisição UDP no servidor UDP interno.
     * Este método lida com o cadastro de itens e o registro de lances.
     */
    private String processarRequisicao(String mensagem) {
        BancoDados db = BancoDados.getInstance();
        logger.info("Mensagem recebida: {}", mensagem);  // Log da mensagem recebida
        String[] partes = mensagem.split(";");
        
        // Verificação da quantidade de partes para garantir o formato correto
        if (partes.length < 4) {
            return "Erro: Formato inválido. Use: registrarLance;idItem;clienteNome;valor";
        }

        String operacao = partes[0];

        if ("cadastrarItem".equalsIgnoreCase(operacao)) {
            String nome = partes[1];
            String descricao = partes[2];
            double precoInicial;
            try {
                precoInicial = Double.parseDouble(partes[3]);
            } catch (NumberFormatException e) {
                return "Erro: Preço inicial inválido.";
            }

            int id = db.adicionarItem(nome, descricao, precoInicial);
            return id != -1 ? "Item cadastrado com ID: " + id : "Erro: Não foi possível cadastrar o item.";
        } else if ("registrarLance".equalsIgnoreCase(operacao)) {
            int idItem;
            String clienteNome = partes[2];  // Cliente é o terceiro parâmetro
            double valor;
            try {
                idItem = Integer.parseInt(partes[1]);  // ID do item é o segundo parâmetro
                valor = Double.parseDouble(partes[3]); // Valor é o quarto parâmetro
            } catch (NumberFormatException e) {
                logger.error("Erro ao converter ID ou valor: {}", e.getMessage());
                return "Erro: ID do item ou valor inválido.";
            }

            // Registrar o lance no banco de dados
            boolean sucesso = db.registrarLance(idItem, clienteNome, valor);
            return sucesso ? "Lance registrado com sucesso." : "Erro: Lance inferior ao maior lance atual.";
        }

        return "Erro: Operação desconhecida.";
    }


    /**
     * Método auxiliar para enviar mensagens de erro via UDP.
     */
    private void enviarErroUDP(DatagramPacket packetRecebido, String erro) throws IOException {
        DatagramPacket packetErro = new DatagramPacket(
                erro.getBytes(StandardCharsets.UTF_8),
                erro.length(),
                packetRecebido.getAddress(),
                packetRecebido.getPort()
        );
        socket.send(packetErro);
        logger.warn("Erro enviado para o cliente: {}", erro);
    }
}
