package com.mst.analysis;

import com.mst.model.Statement;
import com.mst.model.TestCase;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the final SQL for a test case using MyBatis' own dynamic-SQL engine,
 * so the output matches exactly what the application would send to the database.
 *
 * <p>The mapper XML is parsed from a sanitized copy: the {@code resultType} /
 * {@code parameterType} / {@code type} attributes are replaced with a generic
 * map, because the project classes are usually not compiled (and therefore not
 * on the classpath) while the tool is running. Those attributes only describe
 * the result mapping, they have no influence on the generated SQL.</p>
 */
public class SqlRenderer {

    private final Configuration configuration = new Configuration();
    private final Map<String, Boolean> loadedFiles = new HashMap<String, Boolean>();
    private final Map<String, String> loadErrors = new HashMap<String, String>();
    private Path sanitizedDir;

    public SqlRenderer() {
        // Result maps / parameter maps must not fail when the project classes are missing.
        configuration.setMapUnderscoreToCamelCase(false);
    }

    /** Message of the last load failure, null when everything parsed. */
    public String loadError(String xmlFile) {
        return loadErrors.get(xmlFile);
    }

    private synchronized void ensureLoaded(String xmlFile) {
        if (loadedFiles.containsKey(xmlFile)) return;
        Path sanitized = sanitizeToTemp(xmlFile);
        try (InputStream in = Files.newInputStream(sanitized)) {
            XMLMapperBuilder b = new XMLMapperBuilder(in, configuration, xmlFile, configuration.getSqlFragments());
            b.parse();
            loadedFiles.put(xmlFile, Boolean.TRUE);
        } catch (Exception e) {
            loadErrors.put(xmlFile, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new RuntimeException("Cannot load mapper xml: " + xmlFile + " -> "
                    + e.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    /** Writes a copy of the mapper with unusable class references removed. */
    private Path sanitizeToTemp(String xmlFile) {
        try {
            if (sanitizedDir == null) {
                sanitizedDir = Files.createTempDirectory("mst-mappers");
                sanitizedDir.toFile().deleteOnExit();
            }
            String content = new String(Files.readAllBytes(Paths.get(xmlFile)), StandardCharsets.UTF_8);
            content = content.replaceAll("resultType\\s*=\\s*\"[^\"]*\"", "resultType=\"java.util.Map\"");
            content = content.replaceAll("parameterType\\s*=\\s*\"[^\"]*\"", "parameterType=\"java.util.Map\"");
            content = content.replaceAll("(?<![A-Za-z])type\\s*=\\s*\"[^\"]*\"", "type=\"java.util.Map\"");
            content = content.replaceAll("(?<![A-Za-z])javaType\\s*=\\s*\"[^\"]*\"", "javaType=\"java.lang.Object\"");
            content = content.replaceAll("(?<![A-Za-z])typeHandler\\s*=\\s*\"[^\"]*\"", "typeHandler=\"org.apache.ibatis.type.UnknownTypeHandler\"");
            Path out = sanitizedDir.resolve(Paths.get(xmlFile).getFileName().toString());
            Files.write(out, content.getBytes(StandardCharsets.UTF_8));
            out.toFile().deleteOnExit();
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Cannot prepare mapper xml: " + xmlFile + " -> " + e.getMessage(), e);
        }
    }

    public void loadAll(List<String> xmlFiles) {
        for (String f : xmlFiles) {
            try {
                ensureLoaded(f);
            } catch (Exception e) {
                loadErrors.put(f, e.getMessage());
            }
        }
    }

    /** The rendered SQL together with everything needed to execute it. */
    public static class Rendered {
        public String sql;
        public BoundSql boundSql;
        public MappedStatement mappedStatement;
        public String error;
        public boolean ok() { return error == null; }
    }

    public Rendered render(Statement st, TestCase tc) {
        Rendered r = new Rendered();
        try {
            ensureLoaded(st.xmlFile);
            MappedStatement ms = configuration.getMappedStatement(st.fullId(), false);
            BoundSql boundSql = ms.getBoundSql(tc.params);
            r.sql = boundSql.getSql();
            r.boundSql = boundSql;
            r.mappedStatement = ms;
        } catch (Exception e) {
            r.error = e.getMessage();
            r.sql = "/* render error: " + e.getMessage() + " */";
        }
        return r;
    }

    public Configuration configuration() {
        return configuration;
    }
}
