package com.mst;

import com.mst.db.DecisionStore;
import com.mst.db.JdbcExecutor;
import com.mst.analysis.SqlRenderer;
import com.mst.model.Statement;
import com.mst.scan.XmlScanner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the persistence in SQLite and the execution against a real JDBC
 * database (H2 in memory).
 */
class DecisionStoreAndExecutorTest {

    @Test
    void storesDecisionsInSqlite() throws Exception {
        DecisionStore store = new DecisionStore();
        store.bindProject("decision-store-test");
        try {
            store.saveParamType("com.example.UserMapper.select", "id", "Long");
            assertEquals("Long", store.getParamType("com.example.UserMapper.select", "id"));

            store.saveBranchParams("com.example.UserMapper.select", "com.example.UserMapper.select#if1#T",
                    "{\"id\":1}");
            Map<String, String> saved = store.getBranchParams();
            assertTrue(saved.values().contains("{\"id\":1}"));

            DecisionStore.DbConfig cfg = new DecisionStore.DbConfig();
            cfg.driverClass = "org.h2.Driver";
            cfg.jdbcUrl = "jdbc:h2:mem:configtest";
            cfg.username = "sa";
            cfg.password = "";
            store.saveDbConfig(cfg);
            DecisionStore.DbConfig read = store.getDbConfig();
            assertEquals("jdbc:h2:mem:configtest", read.jdbcUrl);
            assertEquals("org.h2.Driver", read.driverClass);
        } finally {
            store.close();
            new File(store.dbPath()).delete();
        }
    }

    @Test
    void executesAndRollsBackAgainstH2() throws Exception {
        String url = "jdbc:h2:mem:exectest;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS t_user (id INT PRIMARY KEY, user_name VARCHAR(50), age INT)");
            s.execute("DELETE FROM t_user");
            s.execute("INSERT INTO t_user VALUES (1,'tom',30)");
        }

        DecisionStore.DbConfig cfg = new DecisionStore.DbConfig();
        cfg.driverClass = "org.h2.Driver";
        cfg.jdbcUrl = url;
        cfg.username = "sa";
        cfg.password = "";
        JdbcExecutor exec = new JdbcExecutor(cfg);

        SqlRenderer.Rendered rendered = new SqlRenderer.Rendered();
        rendered.sql = "SELECT id, user_name, age FROM t_user WHERE age > ?";
        rendered.boundSql = null;
        // without a bound sql the executor can not bind parameters, use the
        // plain statement variant for this check
        JdbcExecutor.Result r = exec.execute("SELECT id, user_name, age FROM t_user WHERE age > 20");
        assertTrue(r.success, "error: " + r.error);
        assertEquals(3, r.columns.size());
        assertEquals(1, r.rows.size());
        assertEquals("tom", r.rows.get(0).get(1));

        // a write must be rolled back
        JdbcExecutor.Result ins = exec.execute("INSERT INTO t_user VALUES (2,'jerry',40)");
        assertTrue(ins.success, "error: " + ins.error);
        assertEquals(1, ins.updateCount);

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             java.sql.Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM t_user")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "the insert must have been rolled back");
        }
    }

    @Test
    void commitsWhenRequested() throws Exception {
        String url = "jdbc:h2:mem:committest;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS t_commit (id INT PRIMARY KEY)");
            s.execute("DELETE FROM t_commit");
        }
        DecisionStore.DbConfig cfg = new DecisionStore.DbConfig();
        cfg.driverClass = "org.h2.Driver";
        cfg.jdbcUrl = url;
        cfg.username = "sa";
        cfg.password = "";
        JdbcExecutor exec = new JdbcExecutor(cfg);
        exec.setCommitChanges(true);
        JdbcExecutor.Result r = exec.execute("INSERT INTO t_commit VALUES (7)");
        assertTrue(r.success, "error: " + r.error);
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             java.sql.Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM t_commit")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "the insert must have been committed");
        }
    }

    @Test
    void reportsRenderedErrors() throws Exception {
        String root = new File("sample").getAbsolutePath();
        XmlScanner scanner = new XmlScanner();
        scanner.scanProject(root);
        SqlRenderer renderer = new SqlRenderer();
        renderer.loadAll(scanner.xmlFiles);
        Statement st = null;
        for (Statement s : scanner.statements) {
            if (s.fullId().endsWith(".search")) st = s;
        }
        assertNotNull(st);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userName", "tom");
        params.put("age", 30);
        com.mst.model.TestCase tc = new com.mst.model.TestCase();
        tc.params = params;
        SqlRenderer.Rendered r = renderer.render(st, tc);
        assertNull(r.error);
        assertTrue(r.sql.contains("user_name LIKE"));
    }
}
