package database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import models.ItemLeilao;
import models.Lance;

import java.sql.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BancoDados {
    private static BancoDados instance = null;
    private HikariDataSource dataSource;
    private static final Logger logger = LoggerFactory.getLogger(BancoDados.class);

    private BancoDados() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/leilao_db");
        config.setUsername("postgres"); // Substitua pelo seu usuário
        config.setPassword("password"); // Substitua pela sua senha
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(config);
    }

    public static BancoDados getInstance() {
        if (instance == null) {
            synchronized (BancoDados.class) {
                if (instance == null) {
                    instance = new BancoDados();
                }
            }
        }
        return instance;
    }

    /**
     * Adiciona um novo item de leilão ao banco de dados.
     *
     * @param nome         Nome do item
     * @param descricao    Descrição do item
     * @param precoInicial Preço inicial do item
     * @return ID do item cadastrado
     */
    public int adicionarItem(String nome, String descricao, double precoInicial) {
        String sql = "INSERT INTO itens_leilao (nome, descricao, preco_inicial) VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nome);
            stmt.setString(2, descricao);
            stmt.setDouble(3, precoInicial);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                logger.info("Item cadastrado com ID: {}", id);
                return id;
            }
        } catch (SQLException e) {
            logger.error("Erro ao cadastrar item: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Registra um lance para um item de leilão.
     *
     * @param idItem  ID do item
     * @param cliente Nome do cliente
     * @param valor   Valor do lance
     * @return true se o lance for o maior atual, false caso contrário
     */
    public boolean registrarLance(int idItem, String cliente, double valor) {
        String verificarSql = "SELECT maior_lance FROM itens_leilao WHERE id = ?";
        String atualizarItemSql = "UPDATE itens_leilao SET maior_lance = ?, cliente_maior_lance = ? WHERE id = ?";
        String inserirLanceSql = "INSERT INTO lances (id_item, cliente, valor) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // Verificar o maior lance atual
            double maiorLanceAtual = 0.0;
            try (PreparedStatement verificarStmt = conn.prepareStatement(verificarSql)) {
                verificarStmt.setInt(1, idItem);
                ResultSet rs = verificarStmt.executeQuery();
                if (rs.next()) {
                    maiorLanceAtual = rs.getDouble("maior_lance");
                } else {
                    conn.rollback();
                    logger.warn("Item com ID {} não encontrado.", idItem);
                    return false;
                }
            }

            if (valor > maiorLanceAtual) {
                // Atualizar o maior lance no item
                try (PreparedStatement atualizarStmt = conn.prepareStatement(atualizarItemSql)) {
                    atualizarStmt.setDouble(1, valor);
                    atualizarStmt.setString(2, cliente);
                    atualizarStmt.setInt(3, idItem);
                    atualizarStmt.executeUpdate();
                }

                // Inserir o lance na tabela de lances
                try (PreparedStatement inserirStmt = conn.prepareStatement(inserirLanceSql)) {
                    inserirStmt.setInt(1, idItem);
                    inserirStmt.setString(2, cliente);
                    inserirStmt.setDouble(3, valor);
                    inserirStmt.executeUpdate();
                }

                conn.commit();
                logger.info("Lance registrado com sucesso para o item ID {}: {} por {}", idItem, valor, cliente);
                return true;
            } else {
                conn.rollback();
                logger.info("Lance de {} para o item ID {} é inferior ao maior lance atual de {}", cliente, idItem, maiorLanceAtual);
                return false;
            }

        } catch (SQLException e) {
            logger.error("Erro ao registrar lance: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Obtém as informações de um item de leilão.
     *
     * @param idItem ID do item
     * @return Objeto ItemLeilao ou null se não encontrado
     */
    public ItemLeilao getItem(int idItem) {
        String sql = "SELECT * FROM itens_leilao WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, idItem);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                ItemLeilao item = new ItemLeilao(
                        rs.getInt("id"),
                        rs.getString("nome"),
                        rs.getString("descricao"),
                        rs.getDouble("preco_inicial"),
                        rs.getDouble("maior_lance"),
                        rs.getString("cliente_maior_lance")
                );
                return item;
            }
        } catch (SQLException e) {
            logger.error("Erro ao obter item: {}", e.getMessage());
        }
        return null;
    }
}
