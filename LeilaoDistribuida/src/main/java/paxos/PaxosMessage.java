package paxos;

public class PaxosMessage {
    private final int proposalNumber;
    private final String value;

    public PaxosMessage(int proposalNumber, String value) {
        this.proposalNumber = proposalNumber;
        this.value = value;
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
                "proposalNumber=" + proposalNumber +
                ", value='" + value + '\'' +
                '}';
    }
}
