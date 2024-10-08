package models;

public class ItemLeilao {
    private int id;
    private String nome;
    private String descricao;
    private double precoInicial;
    private double maiorLance;
    private String clienteMaiorLance;

    public ItemLeilao(int id, String nome, String descricao, double precoInicial, double maiorLance, String clienteMaiorLance) {
        this.id = id;
        this.nome = nome;
        this.descricao = descricao;
        this.precoInicial = precoInicial;
        this.maiorLance = maiorLance;
        this.clienteMaiorLance = clienteMaiorLance;
    }

    // Getters e Setters

    public int getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getDescricao() {
        return descricao;
    }

    public double getPrecoInicial() {
        return precoInicial;
    }

    public double getMaiorLance() {
        return maiorLance;
    }

    public String getClienteMaiorLance() {
        return clienteMaiorLance;
    }

    public void setMaiorLance(double maiorLance) {
        this.maiorLance = maiorLance;
    }

    public void setClienteMaiorLance(String clienteMaiorLance) {
        this.clienteMaiorLance = clienteMaiorLance;
    }

    @Override
    public String toString() {
        return "ItemLeilao{" +
                "id=" + id +
                ", nome='" + nome + '\'' +
                ", descricao='" + descricao + '\'' +
                ", precoInicial=" + precoInicial +
                ", maiorLance=" + maiorLance +
                ", clienteMaiorLance='" + clienteMaiorLance + '\'' +
                '}';
    }
}
