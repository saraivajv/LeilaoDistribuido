package paxos;

public class PaxosAcceptor {
    private int promisedProposal = -1;
    private int acceptedProposal = -1;
    private String acceptedValue = null;
    private final int porta;

    public PaxosAcceptor(int porta) {
        this.porta = porta;
    }

    // Método para lidar com o Prepare
    public boolean receivePrepare(int proposalNumber) {
        if (proposalNumber > promisedProposal) {
            promisedProposal = proposalNumber;
            return true; // Promete não aceitar propostas menores
        }
        return false; // Rejeita a proposta
    }

    // Método para lidar com o Accept
    public boolean receiveAccept(int proposalNumber, String value) {
        if (proposalNumber >= promisedProposal) {
            acceptedProposal = proposalNumber;
            acceptedValue = value;
            return true; // Aceita a proposta
        }
        return false; // Rejeita a proposta
    }

    public String getAcceptedValue() {
        return acceptedValue;
    }
}
