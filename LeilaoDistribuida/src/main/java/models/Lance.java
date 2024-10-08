package models;

public class Lance {
    private int id;
    private int idItem;
    private String cliente;
    private double valor;

    public Lance(int id, int idItem, String cliente, double valor) {
        this.id = id;
        this.idItem = idItem;
        this.cliente = cliente;
        this.valor = valor;
    }

    // Getters e Setters

    public int getId() {
        return id;
    }

    public int getIdItem() {
        return idItem;
    }

    public String getCliente() {
        return cliente;
    }

    public double getValor() {
        return valor;
    }

    public void setValor(double valor) {
        this.valor = valor;
    }

    @Override
    public String toString() {
        return "Lance{" +
                "id=" + id +
                ", idItem=" + idItem +
                ", cliente='" + cliente + '\'' +
                ", valor=" + valor +
                '}';
    }
}
