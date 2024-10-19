package paxos;

import gateway.Gateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;
import java.net.*;

public class PaxosProposer {
    private final Gateway gateway;
    private final AtomicInteger proposalNumber = new AtomicInteger(0);

    public PaxosProposer(Gateway gateway) {
        this.gateway = gateway;
    }

    // Método para iniciar uma proposta de consenso
    public boolean propose(String valor) {
        int currentProposal = proposalNumber.incrementAndGet();

        // Coletar todos os Acceptors (HTTP, TCP, UDP) dinamicamente registrados
        List<Integer> httpAcceptors = gateway.getHttpHandlerPorts();
        List<Integer> tcpAcceptors = gateway.getTcpHandlerPorts();
        List<Integer> udpAcceptors = gateway.getUdpHandlerPorts();

        int totalAcceptors = httpAcceptors.size() + tcpAcceptors.size() + udpAcceptors.size();
        int prepareAcceptCount = 0;

        // Enviar Prepare para todos os Acceptors HTTP
        for (int porta : httpAcceptors) {
            if (sendPrepareHTTP(porta, currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Enviar Prepare para todos os Acceptors TCP
        for (int porta : tcpAcceptors) {
            if (sendPrepareTCP(porta, currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Enviar Prepare para todos os Acceptors UDP
        for (int porta : udpAcceptors) {
            if (sendPrepareUDP(porta, currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Verificar se a maioria aceitou a fase de Prepare
        if (prepareAcceptCount > totalAcceptors / 2) {
            int acceptConfirmCount = 0;

            // Enviar Accept para os Acceptors HTTP
            for (int porta : httpAcceptors) {
                if (sendAcceptHTTP(porta, currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Enviar Accept para os Acceptors TCP
            for (int porta : tcpAcceptors) {
                if (sendAcceptTCP(porta, currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Enviar Accept para os Acceptors UDP
            for (int porta : udpAcceptors) {
                if (sendAcceptUDP(porta, currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Verificar se a maioria dos Acceptors confirmou a proposta
            return acceptConfirmCount > totalAcceptors / 2;
        }

        return false; // Falha no consenso
    }

    // Enviar Prepare via HTTP
    private boolean sendPrepareHTTP(int porta, int proposalNumber) {
        try {
            URL url = new URL("http://localhost:" + porta + "/prepare");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("proposal=" + proposalNumber).getBytes());
            }
            return conn.getResponseCode() == 200; // Verifica se foi aceito
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Enviar Prepare via TCP
    private boolean sendPrepareTCP(int porta, int proposalNumber) {
        try (Socket socket = new Socket("localhost", porta);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("PREPARE " + proposalNumber);
            return "OK".equals(in.readLine()); // Espera uma resposta "OK"

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Enviar Prepare via UDP
    private boolean sendPrepareUDP(int porta, int proposalNumber) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String mensagem = "PREPARE " + proposalNumber;
            byte[] buffer = mensagem.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName("localhost"), porta);
            socket.send(packet);

            // Receber a resposta
            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(responsePacket);
            String resposta = new String(responsePacket.getData(), 0, responsePacket.getLength());
            return "OK".equals(resposta);

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Enviar Accept via HTTP
    private boolean sendAcceptHTTP(int porta, int proposalNumber, String value) {
        try {
            URL url = new URL("http://localhost:" + porta + "/accept");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("proposal=" + proposalNumber + "&value=" + value).getBytes());
            }
            return conn.getResponseCode() == 200; // Verifica se foi aceito
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Enviar Accept via TCP
    private boolean sendAcceptTCP(int porta, int proposalNumber, String value) {
        try (Socket socket = new Socket("localhost", porta);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("ACCEPT " + proposalNumber + " " + value);
            return "OK".equals(in.readLine()); // Espera uma resposta "OK"

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Enviar Accept via UDP
    private boolean sendAcceptUDP(int porta, int proposalNumber, String value) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String mensagem = "ACCEPT " + proposalNumber + " " + value;
            byte[] buffer = mensagem.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName("localhost"), porta);
            socket.send(packet);

            // Receber a resposta
            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(responsePacket);
            String resposta = new String(responsePacket.getData(), 0, responsePacket.getLength());
            return "OK".equals(resposta);

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
