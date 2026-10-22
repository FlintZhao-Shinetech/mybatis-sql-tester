package com.mst;

import com.mst.analysis.RootType;
import com.mst.analysis.SqlRenderer;
import com.mst.analysis.TestCaseGenerator;
import com.mst.analysis.TypeResolver;
import com.mst.analysis.CoverageAnalyzer;
import com.mst.model.Statement;
import com.mst.model.TestCase;
import com.mst.scan.InterfaceScanner;
import com.mst.scan.XmlScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scans the bundled sample project and verifies that every dynamic branch is
 * covered by the generated test cases.
 */
class SampleProjectTest {

    private static XmlScanner xmlScanner;
    private static InterfaceScanner ifaceScanner;
    private static TypeResolver resolver;
    private static Map<String, Map<String, RootType>> rootsByStmt = new LinkedHashMap<>();

    @BeforeAll
    static void scan() throws Exception {
        String root = new File("sample").getAbsolutePath();
        xmlScanner = new XmlScanner();
        xmlScanner.scanProject(root);
        ifaceScanner = new InterfaceScanner();
        ifaceScanner.scanProject(root);
        resolver = new TypeResolver(ifaceScanner);
        for (Statement st : xmlScanner.statements) {
            resolver.resolve(st);
            Map<String, RootType> roots = new LinkedHashMap<>();
            for (Object o : st.rootTypes.values()) {
                RootType rt = (RootType) o;
                roots.put(rt.name, rt);
            }
            rootsByStmt.put(st.fullId(), roots);
        }
    }

    @Test
    void findsAllStatements() {
        assertEquals(1, xmlScanner.xmlFiles.size());
        assertEquals(7, xmlScanner.statements.size());
    }

    @Test
    void expandsIncludeFragments() {
        Statement st = statement("com.example.mapper.UserMapper.selectById");
        assertNotNull(st);
        // the base columns come from <include refid="baseColumns"/>
        assertTrue(st.params.size() >= 1);
        assertEquals("id", st.params.get(0).name);
        assertEquals("BIGINT", st.params.get(0).declaredJdbcType);
    }

    @Test
    void detectsForeachItemParameters() {
        Statement st = statement("com.example.mapper.UserMapper.selectByIds");
        assertNotNull(st);
        boolean foundItem = false;
        for (com.mst.model.ParamRef p : st.params) {
            if ("id".equals(p.name)) {
                assertEquals("ids", p.itemOf, "the foreach item must be linked to its collection");
                foundItem = true;
            }
        }
        assertTrue(foundItem);
        // the item is not a root parameter
        assertFalse(rootsByStmt.get(st.fullId()).containsKey("id"));
    }

    @Test
    void keepsGenericTypeArguments() {
        Map<String, RootType> roots = rootsByStmt.get("com.example.mapper.UserMapper.selectByIds");
        RootType ids = roots.get("ids");
        assertNotNull(ids);
        assertEquals("List<Long>", ids.javaType);
        assertEquals("Long", ids.elementType);
    }

    @Test
    void resolvesMapParameter() {
        Map<String, RootType> roots = rootsByStmt.get("com.example.mapper.UserMapper.selectWithMap");
        RootType params = roots.get("params");
        assertNotNull(params);
        assertTrue(params.isMap());
    }

    @Test
    void everyBranchIsCovered() {
        TestCaseGenerator gen = new TestCaseGenerator();
        gen.setPojoIndex(ifaceScanner.pojoIndex);
        SqlRenderer renderer = new SqlRenderer();
        renderer.loadAll(xmlScanner.xmlFiles);
        CoverageAnalyzer coverage = new CoverageAnalyzer();

        int totalUnits = 0;
        for (Statement st : xmlScanner.statements) {
            gen.generate(st, rootsByStmt.get(st.fullId()));
            Set<String> units = new LinkedHashSet<>(coverage.units(st).stream()
                    .map(u -> u.id).collect(java.util.stream.Collectors.toList()));
            totalUnits += units.size();
            assertTrue(st.unresolvedBranches.isEmpty(),
                    "unresolved branches of " + st.fullId() + ": " + st.unresolvedBranches);

            Set<String> covered = new LinkedHashSet<>();
            for (TestCase tc : st.testCases) {
                covered.addAll(coverage.covered(st, tc.params));
                for (String id : tc.coveredBranches) covered.add(id);
            }
            assertTrue(covered.containsAll(units),
                    "not all branches covered for " + st.fullId() + ", missing: " + minus(units, covered));
        }
        assertEquals(17, totalUnits, "total number of branch coverage points");
    }

    @Test
    void rendersSqlVariants() {
        TestCaseGenerator gen = new TestCaseGenerator();
        gen.setPojoIndex(ifaceScanner.pojoIndex);
        SqlRenderer renderer = new SqlRenderer();
        renderer.loadAll(xmlScanner.xmlFiles);

        Statement st = statement("com.example.mapper.UserMapper.search");
        gen.generate(st, rootsByStmt.get(st.fullId()));
        List<String> sqls = new java.util.ArrayList<>();
        for (TestCase tc : st.testCases) {
            SqlRenderer.Rendered r = renderer.render(st, tc);
            assertNull(r.error, "render error: " + r.error);
            sqls.add(r.sql.replaceAll("\\s+", " ").trim());
        }
        assertTrue(sqls.stream().anyMatch(s -> s.contains("user_name LIKE")),
                "expected a variant with the like condition: " + sqls);
        assertTrue(sqls.stream().anyMatch(s -> s.contains("age = ?")),
                "expected a variant with the age condition: " + sqls);
        assertTrue(sqls.stream().anyMatch(s -> !s.contains("LIKE") && !s.contains("age = ?")),
                "expected a variant without any condition: " + sqls);
    }

    @Test
    void foreachProducesInClause() {
        TestCaseGenerator gen = new TestCaseGenerator();
        gen.setPojoIndex(ifaceScanner.pojoIndex);
        SqlRenderer renderer = new SqlRenderer();
        renderer.loadAll(xmlScanner.xmlFiles);

        Statement st = statement("com.example.mapper.UserMapper.selectByIds");
        gen.generate(st, rootsByStmt.get(st.fullId()));
        boolean sawIn = false;
        boolean sawEmpty = false;
        for (TestCase tc : st.testCases) {
            SqlRenderer.Rendered r = renderer.render(st, tc);
            assertNull(r.error, "render error: " + r.error);
            String sql = r.sql.replaceAll("\\s+", " ").trim();
            if (sql.matches(".*\\bIN\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\).*")) sawIn = true;
            if (sql.matches(".*\\bIN\\s*$")) sawEmpty = true;
        }
        assertTrue(sawIn, "expected a variant with the in clause");
        assertTrue(sawEmpty, "expected a variant with an empty in clause");
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>(a);
        r.removeAll(b);
        return r;
    }

    private static Statement statement(String fullId) {
        for (Statement st : xmlScanner.statements) {
            if (st.fullId().equals(fullId)) return st;
        }
        return null;
    }
}
