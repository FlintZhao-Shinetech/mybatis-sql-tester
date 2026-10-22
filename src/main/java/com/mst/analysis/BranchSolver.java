package com.mst.analysis;

import com.mst.model.Statement;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.OgnlCache;
import org.apache.ibatis.session.Configuration;

import java.util.*;

/**
 * Finds concrete parameter values that make a dynamic SQL branch (or its
 * opposite) evaluate the way we want, and verifies the result with the very
 * same OGNL engine MyBatis uses.
 */
public class BranchSolver {

    /** A requirement on a branch: "this test expression must evaluate to true/false". */
    public static class Constraint {
        public String testExpr;
        public boolean desired;
        /** human readable label, used in messages */
        public String label;
        /** "TEST" or "COLLECTION" */
        public String kind = "TEST";
        /** for COLLECTION constraints: the collection path */
        public String collectionPath;
        /** for COLLECTION constraints: whether the collection must be non empty */
        public boolean collectionNonEmpty;

        public static Constraint test(String expr, boolean desired, String label) {
            Constraint c = new Constraint();
            c.testExpr = expr;
            c.desired = desired;
            c.label = label;
            return c;
        }

        public static Constraint collection(String path, boolean nonEmpty, String label) {
            Constraint c = new Constraint();
            c.kind = "COLLECTION";
            c.collectionPath = path;
            c.collectionNonEmpty = nonEmpty;
            c.label = label;
            return c;
        }
    }

    public static class Solution {
        public Map<String, Object> params = new LinkedHashMap<String, Object>();
        public boolean solved;
        public String reason;
        /** remarks about the generated values, shown in the UI */
        public final List<String> notes = new ArrayList<String>();
    }

    /** One atom together with the truth value it has to have. */
    private static class AtomReq {
        final ExprNode atom;
        final boolean desired;
        AtomReq(ExprNode atom, boolean desired) {
            this.atom = atom;
            this.desired = desired;
        }
    }

    private final ValueFactory vf = new ValueFactory();
    private final int maxCombinations = 400;
    /** shared configuration, only used to build the OGNL context */
    private static final Configuration CONFIGURATION = new Configuration();
    /** POJO class name -> field name -> field type, filled by the type resolver. */
    private Map<String, Map<String, String>> pojoIndex = new LinkedHashMap<String, Map<String, String>>();

    public BranchSolver setPojoIndex(Map<String, Map<String, String>> pojoIndex) {
        if (pojoIndex != null) this.pojoIndex = pojoIndex;
        return this;
    }

    /**
     * @param roots resolved parameter types
     * @param constraints the branch requirements that have to hold
     */
    public Solution solve(Statement st, Map<String, RootType> roots, List<Constraint> constraints) {
        Solution result = new Solution();
        if (constraints.isEmpty()) {
            result.params = baseline(roots);
            result.solved = true;
            return result;
        }

        // candidate atom combinations per constraint
        List<List<List<AtomReq>>> perConstraint = new ArrayList<>();
        for (Constraint c : constraints) {
            if ("COLLECTION".equals(c.kind)) {
                perConstraint.add(Collections.singletonList(Collections.<AtomReq>emptyList()));
                continue;
            }
            ExprNode node = ExprNode.parse(c.testExpr);
            List<List<AtomReq>> alts = alternatives(node, c.desired);
            if (alts.isEmpty()) {
                result.reason = "cannot interpret expression: " + c.testExpr;
                return result;
            }
            perConstraint.add(alts);
        }

        long total = 1;
        for (List<List<AtomReq>> alts : perConstraint) total *= alts.size();
        if (total > maxCombinations) {
            result.reason = "too many parameter combinations (" + total + ")";
            return result;
        }

        List<int[]> counters = new ArrayList<>();
        for (int i = 0; i < perConstraint.size(); i++) counters.add(new int[]{0});

        while (true) {
            List<List<AtomReq>> chosen = new ArrayList<>();
            for (int i = 0; i < perConstraint.size(); i++) {
                chosen.add(perConstraint.get(i).get(counters.get(i)[0]));
            }
            Solution attempt = attempt(st, roots, constraints, chosen);
            if (attempt.solved) return attempt;

            // advance the counters
            int i = counters.size() - 1;
            while (i >= 0) {
                counters.get(i)[0]++;
                if (counters.get(i)[0] < perConstraint.get(i).size()) break;
                counters.get(i)[0] = 0;
                i--;
            }
            if (i < 0) {
                result.reason = attempt.reason != null ? attempt.reason : "no parameter combination satisfies the branch";
                return result;
            }
        }
    }

    private Solution attempt(Statement st, Map<String, RootType> roots, List<Constraint> constraints,
                             List<List<AtomReq>> chosen) {
        Solution sol = new Solution();
        sol.params = baseline(roots);

        Map<String, PathSpec> specs = new LinkedHashMap<>();
        for (int i = 0; i < constraints.size(); i++) {
            Constraint c = constraints.get(i);
            if ("COLLECTION".equals(c.kind)) {
                PathSpec spec = specFor(specs, c.collectionPath);
                if (c.collectionNonEmpty) spec.applyTrue(atomForCollection(c.collectionPath, true));
                else spec.applyTrue(atomForCollection(c.collectionPath, false));
                continue;
            }
            for (AtomReq ar : chosen.get(i)) {
                PathSpec spec = specFor(specs, ar.atom.var);
                if (ar.desired) spec.applyTrue(ar.atom);
                else spec.applyFalse(ar.atom);
            }
        }

        // compute the values
        for (Map.Entry<String, PathSpec> e : specs.entrySet()) {
            PathSpec spec = e.getValue();
            if (spec.conflict) {
                sol.reason = "无法满足 '" + e.getKey() + "' 的条件: " + spec.conflictReason;
                return sol;
            }
            String leafType = leafType(roots, e.getKey());
            Object value = spec.compute(vf, leafType, e.getKey());
            if (value == null && spec.hasPositive && !spec.isNull) {
                // the literal could not be represented in the target type
                sol.reason = "cannot build a value for '" + e.getKey() + "' of type " + leafType;
                return sol;
            }
            vf.assignPath(sol.params, e.getKey(), value);
            if (spec.usedCharLiteral) {
                sol.notes.add("'" + e.getKey() + "' 的条件使用了单引号字符字面量，OGNL 会按数值比较，已改用字符类型取值");
            }
        }

        // verify with the real OGNL engine
        for (Constraint c : constraints) {
            if ("COLLECTION".equals(c.kind)) {
                Object coll = vf.readPath(sol.params, c.collectionPath);
                boolean nonEmpty = coll instanceof Collection && !((Collection<?>) coll).isEmpty();
                if (nonEmpty != c.collectionNonEmpty) {
                    sol.reason = "collection '" + c.collectionPath + "' is "
                            + (nonEmpty ? "not empty" : "empty") + " instead of "
                            + (c.collectionNonEmpty ? "not empty" : "empty");
                    return sol;
                }
                continue;
            }
            Boolean value = eval(c.testExpr, sol.params);
            if (value == null) {
                sol.reason = "cannot evaluate expression: " + c.testExpr;
                return sol;
            }
            if (value != c.desired) {
                sol.reason = "expression '" + c.testExpr + "' evaluated to " + value + " instead of " + c.desired;
                return sol;
            }
        }
        sol.solved = true;
        return sol;
    }

    private ExprNode atomForCollection(String path, boolean nonEmpty) {
        ExprNode n = new ExprNode();
        n.kind = ExprNode.Kind.ATOM;
        n.op = nonEmpty ? ExprNode.Op.NOTEMPTY : ExprNode.Op.EMPTY;
        n.var = path;
        n.text = nonEmpty ? "notEmpty" : "empty";
        return n;
    }

    private PathSpec specFor(Map<String, PathSpec> specs, String path) {
        PathSpec s = specs.get(path);
        if (s == null) {
            s = new PathSpec(path);
            specs.put(path, s);
        }
        return s;
    }

    private Map<String, Object> baseline(Map<String, RootType> roots) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (roots == null) return params;
        for (Map.Entry<String, RootType> e : roots.entrySet()) {
            params.put(e.getKey(), vf.sampleFor(e.getValue()));
        }
        return params;
    }

    /** The java type of a (possibly nested) property path. */
    public String leafType(Map<String, RootType> roots, String path) {
        if (roots == null) return null;
        String[] parts = path.split("\\.");
        RootType rt = roots.get(parts[0]);
        if (rt == null) return null;
        if (parts.length == 1) return rt.javaType;
        if (rt.isPojo()) {
            String t = rt.javaType;
            for (int i = 1; i < parts.length; i++) {
                Map<String, String> fields = pojoFields(t);
                if (fields == null) return null;
                t = fields.get(parts[i]);
                if (t == null) return null;
            }
            return t;
        }
        if (rt.isMap()) return null; // unknown value type
        return null;
    }

    private Map<String, String> pojoFields(String type) {
        if (type == null) return null;
        Map<String, String> f = pojoIndex.get(type);
        if (f == null) {
            String base = TypeResolver.baseType(type);
            f = pojoIndex.get(base);
        }
        return f;
    }

    /**
     * Evaluates an OGNL expression exactly the way MyBatis does: the parameter
     * object is put into a {@link DynamicContext}, so nested property paths
     * such as {@code params.status} are resolved through the meta object.
     * Returns null when the expression cannot be evaluated.
     */
    public static Boolean eval(String expression, Object root) {
        if (expression == null || expression.trim().isEmpty()) return null;
        if (root == null) return null;
        try {
            DynamicContext context = new DynamicContext(CONFIGURATION, root);
            Object v = OgnlCache.getValue(expression, context.getBindings());
            if (v instanceof Boolean) return (Boolean) v;
            if (v == null) return Boolean.FALSE;
            return Boolean.TRUE;
        } catch (Throwable t) {
            return null;
        }
    }

    /** All ways to satisfy a node with the given truth value. */
    private List<List<AtomReq>> alternatives(ExprNode node, boolean desired) {
        List<List<AtomReq>> result = new ArrayList<List<AtomReq>>();
        switch (node.kind) {
            case ATOM: {
                List<AtomReq> one = new ArrayList<AtomReq>();
                one.add(new AtomReq(node, desired));
                result.add(one);
                return result;
            }
            case AND: {
                if (desired) {
                    result.add(new ArrayList<AtomReq>());
                    for (ExprNode child : node.children) {
                        List<List<AtomReq>> childAlts = alternatives(child, true);
                        List<List<AtomReq>> next = new ArrayList<List<AtomReq>>();
                        for (List<AtomReq> base : result) {
                            for (List<AtomReq> alt : childAlts) {
                                List<AtomReq> combo = new ArrayList<AtomReq>(base);
                                combo.addAll(alt);
                                next.add(combo);
                            }
                        }
                        result = next;
                    }
                } else {
                    for (ExprNode child : node.children) {
                        result.addAll(alternatives(child, false));
                    }
                }
                return result;
            }
            case OR: {
                if (desired) {
                    for (ExprNode child : node.children) {
                        result.addAll(alternatives(child, true));
                    }
                } else {
                    result.add(new ArrayList<AtomReq>());
                    for (ExprNode child : node.children) {
                        List<List<AtomReq>> childAlts = alternatives(child, false);
                        List<List<AtomReq>> next = new ArrayList<List<AtomReq>>();
                        for (List<AtomReq> base : result) {
                            for (List<AtomReq> alt : childAlts) {
                                List<AtomReq> combo = new ArrayList<AtomReq>(base);
                                combo.addAll(alt);
                                next.add(combo);
                            }
                        }
                        result = next;
                    }
                }
                return result;
            }
            default:
                return result;
        }
    }
}
