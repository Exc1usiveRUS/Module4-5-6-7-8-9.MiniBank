package com.minibank;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BankService {
    public Client registerClient(String name, String email) {
        String sql = "INSERT INTO clients (name, email) VALUES (?, ?) RETURNING id";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Client(rs.getLong("id"), name, email);
            }
        } catch (SQLException e) {
            throw new BankDataException("Не удалось зарегистрировать клиента " + email, e);
        }
    }

    public Account openAccount(Long clientId, String accountNumber) {
        getClient(clientId);

        String sql = "INSERT INTO accounts (client_id, account_number, balance) "
                + "VALUES (?, ?, 0) RETURNING id";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, clientId);
            ps.setString(2, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Account(rs.getLong("id"), clientId, accountNumber, BigDecimal.ZERO);
            }
        } catch (SQLException e) {
            throw new BankDataException("Не удалось открыть счёт " + accountNumber, e);
        }
    }

    public Account getAccount(Long accountId) {
        String sql = "SELECT id, client_id, account_number, balance FROM accounts WHERE id = ?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Счёт не найден: " + accountId);
                }
                return new Account(
                        rs.getLong("id"),
                        rs.getLong("client_id"),
                        rs.getString("account_number"),
                        rs.getBigDecimal("balance"));
            }
        } catch (SQLException e) {
            throw new BankDataException("Не удалось загрузить счёт " + accountId, e);
        }
    }

    public Client getClient(Long clientId) {
        String sql = "SELECT id, name, email FROM clients WHERE id = ?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Клиент не найден: " + clientId);
                }
                return new Client(rs.getLong("id"), rs.getString("name"), rs.getString("email"));
            }
        } catch (SQLException e) {
            throw new BankDataException("Не удалось загрузить клиента " + clientId, e);
        }
    }

    public void deposit(Long accountId, BigDecimal amount) {
        requirePositive(amount);
        inTransaction("Не удалось пополнить счёт " + accountId, c -> {
            lockAccount(c, accountId);
            addToBalance(c, accountId, amount);
            recordTransaction(c, accountId, amount, "DEPOSIT");
            return null;
        });
    }

    public void transfer(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        requirePositive(amount);
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Нельзя переводить деньги на тот же счёт: " + fromAccountId);
        }
        inTransaction("Не удалось выполнить перевод: " + fromAccountId + " -> " + toAccountId, c -> {
            Long firstId = Math.min(fromAccountId, toAccountId);
            Long secondId = Math.max(fromAccountId, toAccountId);
            Account first = lockAccount(c, firstId);
            Account second = lockAccount(c, secondId);
            Account from = firstId.equals(fromAccountId) ? first : second;

            if (from.getBalance().compareTo(amount) < 0) {
                throw new IllegalStateException("Недостаточно средств на счёте " + fromAccountId);
            }
            addToBalance(c, fromAccountId, amount.negate());
            recordTransaction(c, fromAccountId, amount.negate(), "TRANSFER_OUT");
            addToBalance(c, toAccountId, amount);
            recordTransaction(c, toAccountId, amount, "TRANSFER_IN");
            return null;
        });
    }

    public List<Transaction> getTransactions(Long accountId, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT id, account_id, amount, type, transaction_date "
                + "FROM transactions "
                + "WHERE account_id = ? AND transaction_date >= ? AND transaction_date < ? "
                + "ORDER BY transaction_date, id";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                List<Transaction> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new Transaction(
                            rs.getLong("id"),
                            rs.getLong("account_id"),
                            rs.getBigDecimal("amount"),
                            rs.getString("type"),
                            rs.getObject("transaction_date", LocalDateTime.class)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new BankDataException("Не удалось загрузить транзакции счёта " + accountId, e);
        }
    }

    @FunctionalInterface
    private interface TxWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T inTransaction(String errorMessage, TxWork<T> work) {
        try (Connection c = Db.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new BankDataException(errorMessage, e);
        }
    }

    private Account lockAccount(Connection c, Long accountId) throws SQLException {
        String sql = "SELECT id, client_id, account_number, balance FROM accounts WHERE id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Счёт не найден: " + accountId);
                }
                return new Account(
                        rs.getLong("id"),
                        rs.getLong("client_id"),
                        rs.getString("account_number"),
                        rs.getBigDecimal("balance"));
            }
        }
    }

    private void addToBalance(Connection c, Long accountId, BigDecimal delta) throws SQLException {
        String sql = "UPDATE accounts SET balance = balance + ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, delta);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    private void recordTransaction(Connection c, Long accountId, BigDecimal amount, String type)
            throws SQLException {
        String sql = "INSERT INTO transactions (account_id, amount, type, transaction_date) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setBigDecimal(2, amount);
            ps.setString(3, type);
            ps.setObject(4, LocalDateTime.now());
            ps.executeUpdate();
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма должна быть положительной: " + amount);
        }
    }
}
