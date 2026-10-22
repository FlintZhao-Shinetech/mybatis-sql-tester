package com.mst.db;

import com.mst.analysis.SqlRenderer;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes generated SQL against an arbitrary JDBC target (MySQL, PostgreSQL, DM,
 * Oracle, ...). Driver jars are provided by the user (comma/semicolon separated
 * paths) and loaded via a dedicated URLClassLoader, so any JDBC driver works
 * without repackaging this tool.
 */
public class JdbcExecutor {

    private final DecisionStore.DbConfig cfg;
    /**
     * When false (default) every statement is rolled back, so the test data
     * never reaches the target database. When true the changes are committed.
     */
    private boolean commitChanges = false;

    public JdbcExecutor(DecisionStore.DbConfig cfg) {
        this.cfg = cfg;
    }

    public void setCommitChanges(boolean commitChanges) {
        this.commitChanges = commitChanges;
    }

    public static class Result {
        public boolean success;
        public String error;
        public int updateCount;
        public List<String> columns = new ArrayList<String>();
        public List<List<Object>> rows = new ArrayList<List<Object>>();
        public long elapsedMs;
    }

    private Connection connect() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cfg.driverJars != null && !cfg.driverJars.trim().isEmpty()) {
            List<URL> urls = new ArrayList<URL>();
            for (String p : cfg.driverJars.split("[,;]")) {
                p = p.trim();
                if (p.isEmpty()) continue;
                File f = new File(p);
                if (f.exists()) urls.add(f.toURI().toURL());
            }
            cl = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
        }
        Class<?> driverClass = Class.forName(cfg.driverClass, true, cl);
        Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        return DriverManager.getConnection(cfg.jdbcUrl, cfg.username, cfg.password);
    }

    /**
     * Executes a rendered statement. Parameters are bound with MyBatis' own
     * type handlers, exactly like the running application would do.
     */
    public Result execute(SqlRenderer.Rendered rendered, Map<String, Object> params) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        if (rendered == null || rendered.boundSql == null) {
            r.success = false;
            r.error = rendered == null ? "SQL not rendered" : rendered.error;
            r.elapsedMs = System.currentTimeMillis() - t0;
            return r;
        }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            MappedStatement ms = rendered.mappedStatement;
            BoundSql boundSql = rendered.boundSql;
            try (PreparedStatement ps = c.prepareStatement(boundSql.getSql())) {
                ParameterHandler ph = ms.getConfiguration().newParameterHandler(ms, params, boundSql);
                ph.setParameters(ps);
                boolean hasResultSet = ps.execute();
                if (hasResultSet) {
                    try (ResultSet rs = ps.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        for (int i = 1; i <= n; i++) r.columns.add(md.getColumnLabel(i));
                        int limit = 100;
                        while (rs.next() && limit-- > 0) {
                            List<Object> row = new ArrayList<Object>();
                            for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                            r.rows.add(row);
                        }
                    }
                } else {
                    r.updateCount = ps.getUpdateCount();
                }
                r.success = true;
            }
            // never persist test data unless the user asked for a commit
            if (commitChanges) c.commit();
            else c.rollback();
        } catch (Throwable e) {
            r.success = false;
            String msg = e.getMessage();
            if (msg == null) msg = e.toString();
            r.error = e.getClass().getSimpleName() + ": " + msg;
        }
        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** Executes plain SQL without parameters. */
    public Result execute(String sql) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                boolean hasResultSet = s.execute(sql);
                if (hasResultSet) {
                    try (ResultSet rs = s.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        for (int i = 1; i <= n; i++) r.columns.add(md.getColumnLabel(i));
                        int limit = 100;
                        while (rs.next() && limit-- > 0) {
                            List<Object> row = new ArrayList<Object>();
                            for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                            r.rows.add(row);
                        }
                    }
                } else {
                    r.updateCount = s.getUpdateCount();
                }
                r.success = true;
            }
            if (commitChanges) c.commit();
            else c.rollback();
        } catch (Throwable e) {
            r.success = false;
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** Required because DriverManager refuses drivers from custom classloaders. */
    private static class DriverShim implements Driver {
        private final Driver d;
        DriverShim(Driver d) { this.d = d; }
        public boolean acceptsURL(String u) throws SQLException { return d.acceptsURL(u); }
        public Connection connect(String u, java.util.Properties p) throws SQLException { return d.connect(u, p); }
        public int getMajorVersion() { return d.getMajorVersion(); }
        public int getMinorVersion() { return d.getMinorVersion(); }
        public DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) throws SQLException {
            return d.getPropertyInfo(u, p);
        }
        public boolean jdbcCompliant() { return d.jdbcCompliant(); }
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return d.getParentLogger();
        }
    }
}
