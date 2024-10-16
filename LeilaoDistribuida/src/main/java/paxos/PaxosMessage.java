package paxos;
import java.io.Serializable;

// Esta classe representa as mensagens trocadas no protocolo Paxos.
public class PaxosMessage implements Serializable {

    // Define os tipos de mensagem no protocolo Paxos
    public enum Type {
        PREPARE,  // Mensagem de preparação de uma proposta
        ACCEPT,   // Mensagem de aceitação de uma proposta
        PROMISE,  // Promessa de aceitação de uma proposta futura
        ACCEPTED  // Confirmação de aceitação de uma proposta
    }

    private final Type type;  // Tipo da mensagem (Prepare, Accept, etc.)
    private final int proposalNumber;  // Número da proposta
    private final String value;  // Valor proposto

    public PaxosMessage(Type type, int proposalNumber, String value) {
        this.type = type;
        this.proposalNumber = proposalNumber;
        this.value = value;
    }

    public Type getType() {
        return type;
    }

    public int getProposalNumber() {
        return proposalNumber;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "PaxosMessage{" +
                "type=" + type +
                ", proposalNumber=" + proposalNumber +
                ", value='" + value + '\'' +
                '}';
    }
}
