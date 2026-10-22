package com.mst.analysis;

import com.mst.model.Branch;
import com.mst.model.Statement;

import java.util.*;

/**
 * Computes which dynamic SQL paths a given parameter set actually exercises.
 *
 * <p>The result is obtained by evaluating the branch expressions with MyBatis'
 * OGNL engine, so the numbers match what the database will see at runtime.</p>
 */
public class CoverageAnalyzer {

    /** One coverage unit: a branch and the side (taken / not taken) of it. */
    public static class Unit {
        public String id;
        public String branchId;
        public String side; // T | F
        public String kind;
        public String expression;
        public String description;

        public Unit(String id, String branchId, String side, String kind, String expression, String description) {
            this.id = id;
            this.branchId = branchId;
            this.side = side;
            this.kind = kind;
            this.expression = expression;
            this.description = description;
        }
    }

    private final ValueFactory vf = new ValueFactory();

    /** All coverage units of a statement. */
    public List<Unit> units(Statement st) {
        List<Unit> units = new ArrayList<Unit>();
        for (Branch b : st.branches) {
            if ("if".equals(b.kind)) {
                units.add(new Unit(b.branchId + "#T", b.branchId, "T", b.kind, b.testExpr,
                        "命中 <if test=\"" + b.testExpr + "\">"));
                units.add(new Unit(b.branchId + "#F", b.branchId, "F", b.kind, b.testExpr,
                        "跳过 <if test=\"" + b.testExpr + "\">"));
            } else if ("when".equals(b.kind)) {
                units.add(new Unit(b.branchId + "#T", b.branchId, "T", b.kind, b.testExpr,
                        "命中 <when test=\"" + b.testExpr + "\">"));
            } else if ("otherwise".equals(b.kind)) {
                units.add(new Unit(b.branchId + "#T", b.branchId, "T", b.kind, null,
                        "命中 <otherwise>"));
            } else if ("foreach".equals(b.kind)) {
                units.add(new Unit(b.branchId + "#T", b.branchId, "T", b.kind, b.collectionName,
                        "命中 <foreach collection=\"" + b.collectionName + "\"> (非空)"));
                units.add(new Unit(b.branchId + "#F", b.branchId, "F", b.kind, b.collectionName,
                        "跳过 <foreach collection=\"" + b.collectionName + "\"> (空)"));
            }
        }
        return units;
    }

    /** The unit ids covered by the given parameter set. */
    public Set<String> covered(Statement st, Map<String, Object> params) {
        Set<String> covered = new LinkedHashSet<String>();
        Map<String, Boolean> groupMatched = new LinkedHashMap<String, Boolean>();
        for (Branch b : st.branches) {
            if ("if".equals(b.kind)) {
                Boolean v = BranchSolver.eval(b.testExpr, params);
                if (v == null) continue;
                covered.add(b.branchId + "#" + (v ? "T" : "F"));
            } else if ("when".equals(b.kind)) {
                boolean alreadyMatched = groupMatched.containsKey(b.groupId) && groupMatched.get(b.groupId);
                if (alreadyMatched) continue;
                Boolean v = BranchSolver.eval(b.testExpr, params);
                if (v == null) continue;
                if (v) {
                    covered.add(b.branchId + "#T");
                    groupMatched.put(b.groupId, Boolean.TRUE);
                }
            } else if ("otherwise".equals(b.kind)) {
                boolean matched = groupMatched.containsKey(b.groupId) && groupMatched.get(b.groupId);
                if (!matched) covered.add(b.branchId + "#T");
            } else if ("foreach".equals(b.kind)) {
                Object coll = vf.readPath(params, b.collectionName);
                covered.add(b.branchId + "#" + (isEmpty(coll) ? "F" : "T"));
            }
        }
        return covered;
    }

    private boolean isEmpty(Object coll) {
        if (coll == null) return true;
        if (coll instanceof Collection) return ((Collection<?>) coll).isEmpty();
        if (coll instanceof Map) return ((Map<?, ?>) coll).isEmpty();
        if (coll instanceof String) return ((String) coll).isEmpty();
        if (coll.getClass().isArray()) return java.lang.reflect.Array.getLength(coll) == 0;
        return false;
    }
}
