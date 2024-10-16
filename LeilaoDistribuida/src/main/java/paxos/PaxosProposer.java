package paxos;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import gateway.Gateway;
import protocol.HTTPHandler;
import protocol.TCPHandler;
import protocol.UDPHandler;

public class PaxosProposer {
    private final Gateway gateway;
    private final AtomicInteger proposalNumber = new AtomicInteger(0);

    public PaxosProposer(Gateway gateway) {
        this.gateway = gateway;
    }

    // Método para iniciar uma proposta de consenso
    public boolean propose(String valor) {
        int currentProposal = proposalNumber.incrementAndGet();

        // Coletar todos os Acceptors (HTTP, TCP, UDP)
        List<HTTPHandler> httpAcceptors = gateway.getHttpHandlers();
        List<TCPHandler> tcpAcceptors = gateway.getTcpHandlers();
        List<UDPHandler> udpAcceptors = gateway.getUdpHandlers();

        int totalAcceptors = httpAcceptors.size() + tcpAcceptors.size() + udpAcceptors.size();
        int prepareAcceptCount = 0;

        // Enviar Prepare para todos os Acceptors HTTP
        for (HTTPHandler acceptor : httpAcceptors) {
            if (acceptor.sendPrepare(currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Enviar Prepare para todos os Acceptors TCP
        for (TCPHandler acceptor : tcpAcceptors) {
            if (acceptor.sendPrepare(currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Enviar Prepare para todos os Acceptors UDP
        for (UDPHandler acceptor : udpAcceptors) {
            if (acceptor.sendPrepare(currentProposal)) {
                prepareAcceptCount++;
            }
        }

        // Verificar se a maioria aceitou a fase de Prepare
        if (prepareAcceptCount > totalAcceptors / 2) {
            int acceptConfirmCount = 0;

            // Enviar Accept para os Acceptors HTTP
            for (HTTPHandler acceptor : httpAcceptors) {
                if (acceptor.sendAccept(currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Enviar Accept para os Acceptors TCP
            for (TCPHandler acceptor : tcpAcceptors) {
                if (acceptor.sendAccept(currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Enviar Accept para os Acceptors UDP
            for (UDPHandler acceptor : udpAcceptors) {
                if (acceptor.sendAccept(currentProposal, valor)) {
                    acceptConfirmCount++;
                }
            }

            // Verificar se a maioria dos Acceptors confirmou a proposta
            if (acceptConfirmCount > totalAcceptors / 2) {
                return true; // Consenso alcançado
            }
        }

        return false; // Falha no consenso
    }
}

