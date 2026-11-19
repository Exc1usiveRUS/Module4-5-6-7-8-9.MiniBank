package com.minibank;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;

public final class Migrations {
    private static final String CHANGELOG = "db/changelog/changelog.sql";

    private Migrations() {
    }

    public static void run() {
        run("!seed");
    }

    public static void run(String contexts) {
        try (Connection connection = Db.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(contexts), new LabelExpression());
        } catch (Exception e) {
            throw new BankDataException("Не удалось применить миграции Liquibase", e);
        }
    }
}
