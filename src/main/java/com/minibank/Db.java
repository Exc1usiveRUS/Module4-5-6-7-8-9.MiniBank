package com.minibank;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Db {
    public static final String URL = "jdbc:postgresql://localhost:5432/minibank";
    public static final String USER = "minibank";
    public static final String PASSWORD = "minibank";

    private Db() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
