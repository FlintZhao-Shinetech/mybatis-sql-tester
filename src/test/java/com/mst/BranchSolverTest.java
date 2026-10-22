package com.mst;

import com.mst.analysis.BranchSolver;
import com.mst.analysis.ExprNode;
import com.mst.analysis.RootType;
import com.mst.analysis.ValueFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OGNL based solver helpers.
 */
class BranchSolverTest {

    @Test
    void evaluatesPlainExpressions() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "tom");
        params.put("age", 30);
        assertEquals(Boolean.TRUE, BranchSolver.eval("name != null", params));
        assertEquals(Boolean.FALSE, BranchSolver.eval("name == null", params));
        assertEquals(Boolean.TRUE, BranchSolver.eval("age > 0", params));
        assertEquals(Boolean.FALSE, BranchSolver.eval("age > 100", params));
    }

    @Test
    void resolvesNestedPropertyPaths() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("status", 'A');
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("params", inner);
        assertEquals(Boolean.TRUE, BranchSolver.eval("params.status == 'A'", params));
        assertEquals(Boolean.FALSE, BranchSolver.eval("params.status == 'B'", params));
    }

    @Test
    void charLiteralAgainstStringFails() {
        // OGNL parses 'A' as a char and coerces the other operand to a number,
        // so a String value raises a NumberFormatException (null result) -
        // the same behaviour as real MyBatis.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", "A");
        assertNull(BranchSolver.eval("status == 'A'", params));
    }

    @Test
    void missingPropertyIsNull() {
        Map<String, Object> params = new LinkedHashMap<>();
        assertEquals(Boolean.FALSE, BranchSolver.eval("missing != null", params));
    }

    @Test
    void parsesConjunctions() {
        ExprNode node = ExprNode.parse("a != null and b > 0");
        assertEquals(ExprNode.Kind.AND, node.kind);
        assertEquals(2, node.children.size());
        assertEquals(ExprNode.Op.NOTNULL, node.children.get(0).op);
        assertEquals(ExprNode.Op.GT, node.children.get(1).op);
    }

    @Test
    void parsesDisjunctions() {
        ExprNode node = ExprNode.parse("a == 1 or b == 2");
        assertEquals(ExprNode.Kind.OR, node.kind);
        assertEquals(2, node.children.size());
    }

    @Test
    void detectsSingleCharacterLiterals() {
        ExprNode node = ExprNode.parse("status == 'A'");
        assertEquals(ExprNode.Op.EQ, node.op);
        assertTrue(node.literalChar, "'A' is a char literal for OGNL");
        ExprNode other = ExprNode.parse("status == \"A\"");
        assertFalse(other.literalChar);
    }

    @Test
    void buildsNestedParameterMaps() {
        ValueFactory vf = new ValueFactory();
        Map<String, Object> params = new LinkedHashMap<>();
        vf.assignPath(params, "params.status", "A");
        assertEquals("A", ((Map<?, ?>) params.get("params")).get("status"));
        assertEquals("A", vf.readPath(params, "params.status"));
    }

    @Test
    void sampleValuesAreNotEmpty() {
        ValueFactory vf = new ValueFactory();
        RootType rt = new RootType();
        rt.kind = RootType.LIST;
        rt.javaType = "List<Long>";
        rt.elementType = "Long";
        Object value = vf.sampleFor(rt);
        assertTrue(value instanceof java.util.List);
        assertEquals(1, ((java.util.List<?>) value).size());
        assertEquals(1L, ((java.util.List<?>) value).get(0));
    }
}
