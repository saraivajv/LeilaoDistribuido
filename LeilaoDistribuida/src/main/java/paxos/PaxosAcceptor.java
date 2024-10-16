package paxos;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PaxosAcceptor {

    // Armazena o número de proposta aceito mais recente
    private final AtomicInteger acceptedProposalNumber = new AtomicInteger(-1);

    // Armazena o valor aceito mais recente
    private final AtomicReference<String> acceptedValue = new AtomicReference<>(null);

    // Armazena o número da última proposta recebida
    private final AtomicInteger promisedProposalNumber = new AtomicInteger(-1);

    /**
     * Recebe uma mensagem Prepare de um Proposer.
     * Retorna verdadeiro se o Acceptor prometer aceitar somente propostas com
     * um número maior ou igual ao número da proposta atual.
     * 
     * @param proposalNumber Número da proposta recebida.
     * @return true se o Acceptor prometer, false caso contrário.
     */
    public boolean receivePrepare(int proposalNumber) {
        // Verifica se o número da proposta é maior do que o número de proposta prometido
        if (proposalNumber > promisedProposalNumber.get()) {
            // Atualiza o número prometido
            promisedProposalNumber.set(proposalNumber);
            return true;
        }
        return false; // Rejeita a proposta
    }

    /**
     * Recebe uma mensagem Accept de um Proposer.
     * Se o Acceptor tiver prometido aceitar propostas com um número maior ou igual,
     * ele aceita o valor e retorna verdadeiro.
     * 
     * @param proposalNumber Número da proposta.
     * @param value Valor a ser aceito.
     * @return true se a proposta for aceita, false caso contrário.
     */
    public boolean receiveAccept(int proposalNumber, String value) {
        // Verifica se o número da proposta é maior ou igual ao prometido
        if (proposalNumber >= promisedProposalNumber.get()) {
            // Aceita a proposta e armazena o valor
            acceptedProposalNumber.set(proposalNumber);
            acceptedValue.set(value);
            return true;
        }
        return false; // Rejeita a proposta
    }

    /**
     * Retorna o valor aceito mais recente.
     * 
     * @return O valor aceito mais recente.
     */
    public String getAcceptedValue() {
        return acceptedValue.get();
    }

    /**
     * Retorna o número de proposta aceito mais recente.
     * 
     * @return O número de proposta aceito mais recente.
     */
    public int getAcceptedProposalNumber() {
        return acceptedProposalNumber.get();
    }
}
