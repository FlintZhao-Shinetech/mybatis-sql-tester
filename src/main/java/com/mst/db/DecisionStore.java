package com.mst.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.sql.*;

/**
 * Persists user decisions (parameter types, target DB config) keyed by project name.
 * Database file lives next to the scanned project: <projectParent>/<projectName>.sqlite
 * or in a configurable decisions dir.
 */
@Component
public class DecisionStore {

    @Value("${mst.decisions-dir:#{null}}")
    private String decisionsDir;

    private String dbPath;
    private Connection conn;

    /**
     * sqlite-jdbc's own native extraction is unreliable inside some containers /
     * Spring Boot fat jars. We extract the bundled .so ourselves and point the
     * driver at it, falling back silently if the platform has no bundled native.
     */
    static {
        tryNativeSetup();
    }

    private static void tryNativeSetup() {
        if (System.getProperty("org.sqlite.lib.path") != null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "");
        String resDir, libName;
        if (os.contains("linux")) {
            if (arch.contains("aarch64")) resDir = "Linux/aarch64";
            else resDir = "Linux/x86_64";
            libName = "libsqlitejdbc.so";
        } else if (os.contains("mac")) {
            resDir = arch.contains("aarch64") ? "Mac/aarch64" : "Mac/x86_64";
            libName = "libsqlitejdbc.dylib";
        } else if (os.contains("win")) {
            resDir = "Windows/x86_64";
            libName = "sqlitejdbc.dll";
        } else {
            return;
        }
        String resPath = "/org/sqlite/native/" + resDir + "/" + libName;
        try (java.io.InputStream in = DecisionStore.class.getResourceAsStream(resPath)) {
            if (in == null) return;
            // The driver looks for a fixed file name inside org.sqlite.lib.path,
            // so extract into a stable directory with the canonical name.
            // NOTE: not java.io.tmpdir — /tmp is often mounted noexec, which makes
            // System.load() fail. Use the app data dir instead.
            File baseDir = new File(System.getProperty("user.home"), ".mybatis-sql-tester");
            File dir = new File(baseDir, "native-" + resDir.replace('/', '_'));
            dir.mkdirs();
            File target = new File(dir, libName);
            if (!target.exists()) {
                try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            try {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
                java.nio.file.Files.setPosixFilePermissions(target.toPath(), perms);
            } catch (Throwable t) {
                target.setExecutable(true, false);
                target.setReadable(true, false);
            }
            System.setProperty("org.sqlite.lib.path", dir.getAbsolutePath());
            System.setProperty("org.sqlite.lib.name", libName);
        } catch (Throwable ignored) {
            // fall back to driver's own loader
        }
    }

    public synchronized void bindProject(String projectName) {
        String dir = decisionsDir;
        if (dir == null) {
            dir = System.getProperty("user.home") + File.separator + ".mybatis-sql-tester";
        }
        File d = new File(dir);
        if (!d.exists()) d.mkdirs();
        String safe = projectName.replaceAll("[^A-Za-z0-9_.-]", "_");
        this.dbPath = new File(d, safe + ".sqlite").getAbsolutePath();
        open();
        initSchema();
    }

    /** Absolute path of the SQLite file that holds the decisions. */
    public String dbPath() {
        return dbPath;
    }

    /** Closes the SQLite connection. */
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {
        } finally {
            conn = null;
        }
    }

    private void open() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Cannot open decision db: " + dbPath, e);
        }
    }

    private void initSchema() {
        exec("CREATE TABLE IF NOT EXISTS param_type (" +
                "stmt_id TEXT NOT NULL, param_name TEXT NOT NULL, java_type TEXT NOT NULL, " +
                "updated_at INTEGER, PRIMARY KEY (stmt_id, param_name))");
        exec("CREATE TABLE IF NOT EXISTS db_config (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), driver_class TEXT, jdbc_url TEXT, " +
                "username TEXT, password TEXT, driver_jars TEXT, updated_at INTEGER)");
        exec("CREATE TABLE IF NOT EXISTS branch_param (" +
                "stmt_id TEXT NOT NULL, branch_id TEXT NOT NULL, params_json TEXT, " +
                "PRIMARY KEY (stmt_id, branch_id))");
    }

    private void exec(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- param types ----

    public String getParamType(String stmtId, String paramName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT java_type FROM param_type WHERE stmt_id=? AND param_name=?")) {
            ps.setString(1, stmtId);
            ps.setString(2, paramName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public java.util.Map<String, String> getAllParamTypes() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT stmt_id, param_name, java_type FROM param_type")) {
            while (rs.next()) {
                m.put(rs.getString(1) + "\u0000" + rs.getString(2), rs.getString(3));
            }
        } catch (SQLException ignored) {}
        return m;
    }

    public void saveParamType(String stmtId, String paramName, String javaType) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO param_type(stmt_id,param_name,java_type,updated_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(stmt_id,param_name) DO UPDATE SET java_type=excluded.java_type, updated_at=excluded.updated_at")) {
            ps.setString(1, stmtId);
            ps.setString(2, paramName);
            ps.setString(3, javaType);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- manual branch parameter overrides ----

    public void saveBranchParams(String stmtId, String branchId, String paramsJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branch_param(stmt_id,branch_id,params_json) VALUES(?,?,?) " +
                        "ON CONFLICT(stmt_id,branch_id) DO UPDATE SET params_json=excluded.params_json")) {
            ps.setString(1, stmtId);
            ps.setString(2, branchId);
            ps.setString(3, paramsJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public java.util.Map<String, String> getBranchParams() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT stmt_id,branch_id,params_json FROM branch_param")) {
            while (rs.next()) {
                m.put(rs.getString(1) + "\u0000" + rs.getString(2), rs.getString(3));
            }
        } catch (SQLException ignored) {}
        return m;
    }

    // ---- db config ----

    public static class DbConfig {
        public String driverClass;
        public String jdbcUrl;
        public String username;
        public String password;
        public String driverJars;
    }

    public DbConfig getDbConfig() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT driver_class,jdbc_url,username,password,driver_jars FROM db_config WHERE id=1")) {
            if (rs.next()) {
                DbConfig c = new DbConfig();
                c.driverClass = rs.getString(1);
                c.jdbcUrl = rs.getString(2);
                c.username = rs.getString(3);
                c.password = rs.getString(4);
                c.driverJars = rs.getString(5);
                return c;
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public void saveDbConfig(DbConfig c) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO db_config(id,driver_class,jdbc_url,username,password,driver_jars,updated_at) " +
                        "VALUES(1,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                        "driver_class=excluded.driver_class, jdbc_url=excluded.jdbc_url, username=excluded.username, " +
                        "password=excluded.password, driver_jars=excluded.driver_jars, updated_at=excluded.updated_at")) {
            ps.setString(1, c.driverClass);
            ps.setString(2, c.jdbcUrl);
            ps.setString(3, c.username);
            ps.setString(4, c.password);
            ps.setString(5, c.driverJars);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
