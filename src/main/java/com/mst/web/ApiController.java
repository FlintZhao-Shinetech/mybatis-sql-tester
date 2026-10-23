package com.mst.web;

import com.mst.analysis.RootType;
import com.mst.analysis.SqlRenderer;
import com.mst.analysis.TestCaseGenerator;
import com.mst.analysis.TypeResolver;
import com.mst.db.DecisionStore;
import com.mst.db.JdbcExecutor;
import com.mst.model.Branch;
import com.mst.model.ParamRef;
import com.mst.model.Statement;
import com.mst.model.TestCase;
import com.mst.scan.InterfaceScanner;
import com.mst.scan.XmlScanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ApiController {

    @Autowired
    DecisionStore store;

    private InterfaceScanner ifaceScanner;
    private XmlScanner xmlScanner;
    private List<Statement> statements = new ArrayList<Statement>();
    /** statement id -> resolved root types */
    private Map<String, Map<String, RootType>> rootsByStmt = new LinkedHashMap<String, Map<String, RootType>>();
    /** statement id -> rendered sql per test case (same order as statement.testCases) */
    private Map<String, List<SqlRenderer.Rendered>> renderedByStmt = new LinkedHashMap<String, List<SqlRenderer.Rendered>>();
    private SqlRenderer renderer;
    private String projectPath;
    private String projectName;

    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody Map<String, String> body) throws Exception {
        projectPath = body.get("projectPath");
        if (projectPath == null || projectPath.trim().isEmpty()) {
            throw new IllegalArgumentException("projectPath is required");
        }
        File pf = new File(projectPath);
        projectName = body.getOrDefault("projectName", pf.getName());
        store.bindProject(projectName);

        ifaceScanner = new InterfaceScanner();
        ifaceScanner.scanProject(projectPath);

        xmlScanner = new XmlScanner();
        xmlScanner.scanProject(projectPath);
        statements = xmlScanner.statements;
        rootsByStmt = new LinkedHashMap<String, Map<String, RootType>>();
        renderedByStmt = new LinkedHashMap<String, List<SqlRenderer.Rendered>>();

        resolveAll();

        renderer = new SqlRenderer();
        renderer.loadAll(xmlScanner.xmlFiles);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("projectName", projectName);
        out.put("projectPath", projectPath);
        out.put("sqliteFile", store.dbPath());
        out.put("xmlCount", xmlScanner.xmlFiles.size());
        out.put("statementCount", statements.size());
        out.put("statements", statements);
        out.put("pendingTypes", pendingTypes());
        out.put("unresolvedBranches", unresolvedBranches());
        out.put("knownTypes", knownTypes());
        return out;
    }

    /**
     * Re-runs type resolution for every statement, including the types the
     * user already confirmed. Called after a scan and after a type is saved so
     * the confirmed type immediately influences the generated parameters.
     */
    private void resolveAll() {
        TypeResolver resolver = new TypeResolver(ifaceScanner);
        Map<String, String> saved = store.getAllParamTypes();
        rootsByStmt = new LinkedHashMap<String, Map<String, RootType>>();
        for (Statement st : statements) {
            Map<String, String> userTypes = new LinkedHashMap<String, String>();
            String prefix = st.fullId() + "\u0000";
            for (Map.Entry<String, String> e : saved.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    userTypes.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
            resolver.resolve(st, userTypes);
            Map<String, RootType> roots = new LinkedHashMap<String, RootType>();
            for (Object o : st.rootTypes.values()) {
                RootType rt = (RootType) o;
                roots.put(rt.name, rt);
            }
            rootsByStmt.put(st.fullId(), roots);
        }
    }

    /** POJO/entity class names discovered in the project, for UI autocomplete. */
    private List<String> knownTypes() {
        Set<String> types = new TreeSet<String>();
        if (ifaceScanner != null) {
            for (Map.Entry<String, Map<String, String>> e : ifaceScanner.pojoIndex.entrySet()) {
                if (e.getKey().indexOf('.') < 0 && e.getValue() != null && !e.getValue().isEmpty()) {
                    types.add(e.getKey());
                }
            }
        }
        return new ArrayList<String>(types);
    }

    /** Params whose type we could not determine -> need the user to pick once. */
    private List<Map<String, Object>> pendingTypes() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Set<String> seen = new HashSet<String>();
        for (Statement st : statements) {
            for (ParamRef p : st.params) {
                if (p.raw) continue;
                if ("UNKNOWN".equals(p.typeSource) || p.javaType == null) {
                    String key = st.fullId() + "\u0000" + p.name;
                    if (seen.add(key)) {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("stmtId", st.fullId());
                        m.put("paramName", p.name);
                        m.put("rootName", p.root());
                        m.put("itemOf", p.itemOf);
                        boolean collection = p.itemOf != null || isForeachCollection(st, p.name);
                        m.put("isCollection", collection);
                        m.put("collectionRoot", p.itemOf != null ? p.itemOf
                                : (collection ? p.name : null));
                        m.put("declaredJdbcType", p.declaredJdbcType);
                        m.put("statementType", st.type);
                        list.add(m);
                    }
                }
            }
        }
        return list;
    }

    private boolean isForeachCollection(Statement st, String name) {
        for (Branch b : st.branches) {
            if ("foreach".equals(b.kind) && name != null && name.equals(b.collectionName)) return true;
        }
        return false;
    }

    private List<Map<String, Object>> unresolvedBranches() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Statement st : statements) {
            for (Map<String, Object> m : st.unresolvedBranches) {
                Map<String, Object> copy = new LinkedHashMap<String, Object>(m);
                copy.put("stmtId", st.fullId());
                list.add(copy);
            }
        }
        return list;
    }

    @PostMapping("/param-type")
    public Map<String, Object> saveParamType(@RequestBody Map<String, String> body) {
        store.saveParamType(body.get("stmtId"), body.get("paramName"), body.get("javaType"));
        if (ifaceScanner != null) resolveAll();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("statements", statements);
        out.put("pendingTypes", pendingTypes());
        out.put("knownTypes", knownTypes());
        return out;
    }

    /** Manual parameter values for a branch the solver could not satisfy. */
    @PostMapping("/branch-params")
    public Map<String, Object> saveBranchParams(@RequestBody Map<String, Object> body) {
        String stmtId = String.valueOf(body.get("stmtId"));
        String branchId = String.valueOf(body.get("branchId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
            store.saveBranchParams(stmtId, branchId, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate() {
        TestCaseGenerator gen = new TestCaseGenerator();
        gen.setPojoIndex(ifaceScanner != null ? ifaceScanner.pojoIndex : new LinkedHashMap<String, Map<String, String>>());
        Map<String, Map<String, Object>> overrides = branchOverrides();
        renderedByStmt = new LinkedHashMap<String, List<SqlRenderer.Rendered>>();
        for (Statement st : statements) {
            Map<String, RootType> roots = rootsByStmt.get(st.fullId());
            gen.generate(st, roots, overrides);
            List<SqlRenderer.Rendered> rendered = new ArrayList<SqlRenderer.Rendered>();
            for (TestCase tc : st.testCases) {
                SqlRenderer.Rendered r = renderer.render(st, tc);
                tc.renderedSql = r.sql;
                rendered.add(r);
            }
            renderedByStmt.put(st.fullId(), rendered);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("statements", statements);
        out.put("unresolvedBranches", unresolvedBranches());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> branchOverrides() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, String> saved = store.getBranchParams();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            String[] parts = e.getKey().split("\u0000", 2);
            if (parts.length < 2) continue;
            try {
                Map<String, Object> params = mapper.readValue(e.getValue(), Map.class);
                result.put(parts[1], params);
            } catch (Exception ignored) {}
        }
        return result;
    }

    @GetMapping("/db-config")
    public DecisionStore.DbConfig getDbConfig() {
        DecisionStore.DbConfig c = store.getDbConfig();
        return c != null ? c : new DecisionStore.DbConfig();
    }

    @PostMapping("/db-config")
    public Map<String, Object> saveDbConfig(@RequestBody DecisionStore.DbConfig c) {
        store.saveDbConfig(c);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    /**
     * Executes the generated SQL. Without a statement id every statement of the
     * project is executed.
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        DecisionStore.DbConfig cfg = store.getDbConfig();
        if (cfg == null || cfg.jdbcUrl == null || cfg.jdbcUrl.trim().isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", false);
            out.put("error", "目标数据库未配置");
            return out;
        }
        JdbcExecutor exec = new JdbcExecutor(cfg);
        Object commitFlag = body.get("commit");
        exec.setCommitChanges(Boolean.TRUE.equals(commitFlag)
                || "true".equalsIgnoreCase(String.valueOf(commitFlag)));
        String stmtId = (String) body.get("stmtId");
        for (Statement st : statements) {
            if (stmtId != null && !stmtId.isEmpty() && !st.fullId().equals(stmtId)) continue;
            List<SqlRenderer.Rendered> rendered = renderedByStmt.get(st.fullId());
            if (rendered == null) continue;
            for (int i = 0; i < st.testCases.size() && i < rendered.size(); i++) {
                TestCase tc = st.testCases.get(i);
                JdbcExecutor.Result r = exec.execute(rendered.get(i), tc.params);
                tc.success = r.success;
                tc.error = r.error;
                tc.elapsedMs = r.elapsedMs;
                tc.columns = r.columns;
                tc.rows = r.rows;
                tc.updateCount = r.updateCount;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("statements", statements);
        return out;
    }
}
