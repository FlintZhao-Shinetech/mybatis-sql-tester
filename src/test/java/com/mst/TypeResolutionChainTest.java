package com.mst;

import com.mst.analysis.RootType;
import com.mst.analysis.SqlRenderer;
import com.mst.analysis.TestCaseGenerator;
import com.mst.analysis.TypeResolver;
import com.mst.model.ParamRef;
import com.mst.model.Statement;
import com.mst.model.TestCase;
import com.mst.scan.InterfaceScanner;
import com.mst.scan.XmlScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that type resolution follows nested POJO fields, inherited fields
 * and generic collection elements.
 */
class TypeResolutionChainTest {

    private static XmlScanner xmlScanner;
    private static InterfaceScanner ifaceScanner;
    private static TypeResolver resolver;
    private static Map<String, Map<String, RootType>> rootsByStmt = new LinkedHashMap<>();

    @BeforeAll
    static void scan() throws Exception {
        String root = new File("src/test/resources/fixtures/typechain").getAbsolutePath();
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
    void resolvesNestedPojoFieldOfSingleParameter() {
        Statement st = statement("com.example.mapper.UserMapper.insert");
        assertEquals("String", param(st, "address.city").javaType);
        assertEquals("String", param(st, "userName").javaType);
        RootType address = rootsByStmt.get(st.fullId()).get("address");
        assertNotNull(address);
        assertTrue(address.isPojo());
        assertEquals("Address", address.javaType);
        assertEquals("String", address.pojoFields.get("city"));
    }

    @Test
    void resolvesInheritedFields() {
        Statement st = statement("com.example.mapper.UserMapper.insert");
        assertEquals("Long", param(st, "id").javaType);
        assertEquals("Long", ifaceScanner.pojoIndex.get("User").get("id"));
        assertEquals("Date", ifaceScanner.pojoIndex.get("User").get("createTime"));
    }

    @Test
    void resolvesFieldsOfForeachItemAndGenericElement() {
        Statement st = statement("com.example.mapper.UserMapper.batchInsert");
        RootType users = rootsByStmt.get(st.fullId()).get("users");
        assertNotNull(users);
        assertTrue(users.isList());
        assertEquals("User", users.elementType);

        ParamRef city = param(st, "user.address.city");
        assertEquals("users", city.itemOf);
        assertEquals("String", city.javaType);

        ParamRef id = param(st, "user.id");
        assertEquals("users", id.itemOf);
        assertEquals("Long", id.javaType);
    }

    @Test
    void expandsPojoCollectionElementsForRendering() {
        Statement st = statement("com.example.mapper.UserMapper.batchInsert");
        TestCaseGenerator gen = new TestCaseGenerator();
        gen.setPojoIndex(ifaceScanner.pojoIndex);
        gen.generate(st, rootsByStmt.get(st.fullId()));

        SqlRenderer renderer = new SqlRenderer();
        renderer.loadAll(xmlScanner.xmlFiles);

        boolean sawNonEmpty = false;
        for (TestCase tc : st.testCases) {
            SqlRenderer.Rendered rendered = renderer.render(st, tc);
            assertNull(rendered.error, "render error: " + rendered.error);
            Object users = tc.params.get("users");
            assertTrue(users instanceof List);
            if (!((List<?>) users).isEmpty()) {
                sawNonEmpty = true;
                Object element = ((List<?>) users).get(0);
                assertTrue(element instanceof Map, "foreach element must be a map: " + element);
                assertTrue(((Map<?, ?>) element).get("address") instanceof Map,
                        "nested POJO must be expanded: " + element);
                assertTrue(((Map<?, ?>) element).get("id") != null, "inherited field must be present");
                assertTrue(rendered.sql.contains("?"));
            }
        }
        assertTrue(sawNonEmpty);
    }

    @Test
    void userConfirmedCollectionTypeUpdatesItemTypes() {
        Statement st = statement("com.example.mapper.UserMapper.batchInsert");
        resolver.resolve(st, Collections.singletonMap("users", "List<Address>"));
        RootType users = (RootType) st.rootTypes.get("users");
        assertNotNull(users);
        assertEquals("Address", users.elementType);
        assertEquals("String", param(st, "user.city").javaType);
        assertNull(param(st, "user.address.city").javaType);
        assertNull(param(st, "user.id").javaType);
        assertEquals("UNKNOWN", param(st, "user.id").typeSource);
    }

    @Test
    void appliesUserConfirmedTypeToRoot() {
        Statement st = statement("com.example.mapper.UserMapper.updateUnknown");
        assertNotNull(singletonPending(st, "tenantCode"));
        resolver.resolve(st, Collections.singletonMap("tenantCode", "Long"));
        RootType tenant = (RootType) st.rootTypes.get("tenantCode");
        assertNotNull(tenant);
        assertEquals("Long", tenant.javaType);
        assertEquals("USER", tenant.source);
        assertEquals("Long", param(st, "tenantCode").javaType);
    }

    private static ParamRef param(Statement st, String name) {
        for (ParamRef p : st.params) if (name.equals(p.name)) return p;
        fail("parameter not found: " + name);
        return null;
    }

    private static ParamRef singletonPending(Statement st, String name) {
        for (ParamRef p : st.params) {
            if (name.equals(p.name) && ("UNKNOWN".equals(p.typeSource) || p.javaType == null)) return p;
        }
        return null;
    }

    private static Statement statement(String fullId) {
        for (Statement st : xmlScanner.statements) if (st.fullId().equals(fullId)) return st;
        fail("statement not found: " + fullId);
        return null;
    }
}
