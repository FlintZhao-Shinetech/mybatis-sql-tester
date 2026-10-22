package com.mst.analysis;

import com.mst.model.Branch;
import com.mst.model.Statement;
import com.mst.model.TestCase;

import java.util.*;

/**
 * Generates parameter sets that cover every dynamic branch of a statement.
 *
 * <p>For every branch decision point both sides are solved for:
 * an {@code <if>} needs a case where the test is true and one where it is
 * false, a {@code <when>} needs its test to be true (and all previous
 * {@code <when}s of the same group to be false), an {@code <otherwise>} needs
 * all {@code <when}s of its group to be false and a {@code <foreach>} needs
 * both a non empty and an empty collection.</p>
 *
 * <p>All generated values are verified with MyBatis' OGNL engine, and cases
 * that are compatible are merged so the number of test cases stays small.</p>
 */
public class TestCaseGenerator {

    private final BranchSolver solver = new BranchSolver();
    private final CoverageAnalyzer coverage = new CoverageAnalyzer();

    public void setPojoIndex(Map<String, Map<String, String>> pojoIndex) {
        solver.setPojoIndex(pojoIndex);
    }

    public void generate(Statement st, Map<String, RootType> roots) {
        generate(st, roots, Collections.<String, Map<String, Object>>emptyMap());
    }

    /**
     * @param overrides manually provided parameter values per branch id
     *                  ({@code branchId -> {param: value}}), used for branches the
     *                  solver could not satisfy on its own.
     */
    public void generate(Statement st, Map<String, RootType> roots,
                         Map<String, Map<String, Object>> overrides) {
        st.testCases.clear();
        st.unresolvedBranches.clear();
        st.coverageUnits.clear();

        List<CoverageAnalyzer.Unit> units = coverage.units(st);
        for (CoverageAnalyzer.Unit u : units) {
            st.coverageUnits.put(u.id, u.description);
        }
        if (units.isEmpty()) {
            TestCase tc = new TestCase();
            tc.name = "全字段/无分支";
            tc.params = solver.solve(st, roots, Collections.<BranchSolver.Constraint>emptyList()).params;
            st.testCases.add(tc);
            return;
        }

        List<CaseSpec> specs = new ArrayList<CaseSpec>();
        for (CoverageAnalyzer.Unit u : units) {
            specs.add(new CaseSpec(u, constraintsFor(st, u)));
        }

        List<Case> cases = new ArrayList<Case>();
        Map<String, String> failures = new LinkedHashMap<String, String>();
        for (CaseSpec cs : specs) {
            Map<String, Object> override = overrides.get(cs.unit.id);
            if (override == null) override = overrides.get(cs.unit.branchId);
            BranchSolver.Solution sol;
            boolean manual = false;
            if (override != null) {
                sol = new BranchSolver.Solution();
                sol.params = new LinkedHashMap<String, Object>();
                sol.solved = true;
                for (Map.Entry<String, RootType> e : roots.entrySet()) {
                    sol.params.put(e.getKey(), new ValueFactory().sampleFor(e.getValue()));
                }
                sol.params.putAll(override);
                manual = true;
                sol.notes.add("该分支使用了手工指定的参数值");
            } else {
                sol = solver.solve(st, roots, cs.constraints);
            }
            if (!sol.solved) {
                failures.put(cs.unit.id, sol.reason);
                continue;
            }
            boolean merged = false;
            for (Case c : cases) {
                if (manual || c.manual) continue;
                if (conflicts(c, cs)) continue;
                List<BranchSolver.Constraint> combined = new ArrayList<BranchSolver.Constraint>(c.constraints);
                combined.addAll(cs.constraints);
                BranchSolver.Solution mergedSol = solver.solve(st, roots, combined);
                if (mergedSol.solved) {
                    c.constraints = combined;
                    c.params = mergedSol.params;
                    c.units.add(cs.unit);
                    c.notes.addAll(mergedSol.notes);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                Case c = new Case();
                c.constraints = cs.constraints;
                c.params = sol.params;
                c.units.add(cs.unit);
                c.manual = manual;
                c.notes.addAll(sol.notes);
                cases.add(c);
            }
        }

        Set<String> coveredUnits = new LinkedHashSet<String>();
        for (Case c : cases) {
            TestCase tc = new TestCase();
            tc.name = describe(c.units);
            tc.params = c.params;
            tc.manual = c.manual;
            tc.coveredBranches = new ArrayList<String>();
            for (CoverageAnalyzer.Unit u : c.units) {
                tc.coveredBranches.add(u.id);
                coveredUnits.add(u.id);
            }
            // the truth: what does this parameter set really exercise?
            for (String id : coverage.covered(st, c.params)) {
                if (!tc.coveredBranches.contains(id)) tc.coveredBranches.add(id);
            }
            if (!c.notes.isEmpty()) {
                tc.note = String.join("; ", c.notes);
            }
            for (CoverageAnalyzer.Unit u : c.units) {
                if ("foreach".equals(u.kind) && "F".equals(u.side)) {
                    tc.note = append(tc.note, "集合为空时 MyBatis 会跳过 foreach 内容，"
                            + "生成的 SQL 片段可能不合法（如 IN 后缺少括号），需业务自行处理");
                    break;
                }
            }
            st.testCases.add(tc);
        }

        for (CoverageAnalyzer.Unit u : units) {
            if (coveredUnits.contains(u.id)) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("unitId", u.id);
            m.put("branchId", u.branchId);
            m.put("kind", u.kind);
            m.put("expression", u.expression);
            m.put("description", u.description);
            m.put("reason", failures.get(u.id) != null ? failures.get(u.id) : "无法自动满足该分支，请手动指定参数值");
            st.unresolvedBranches.add(m);
        }
    }

    /** The constraints that have to hold for a coverage unit. */
    private List<BranchSolver.Constraint> constraintsFor(Statement st, CoverageAnalyzer.Unit u) {
        List<BranchSolver.Constraint> cs = new ArrayList<BranchSolver.Constraint>();
        Branch b = findBranch(st, u.branchId);
        if (b == null) return cs;
        if ("if".equals(b.kind)) {
            cs.add(BranchSolver.Constraint.test(b.testExpr, u.side.equals("T"), u.description));
        } else if ("when".equals(b.kind)) {
            cs.add(BranchSolver.Constraint.test(b.testExpr, true, u.description));
            for (Branch prev : groupMembers(st, b)) {
                cs.add(BranchSolver.Constraint.test(prev.testExpr, false, "前序 when 需为 false"));
            }
        } else if ("otherwise".equals(b.kind)) {
            for (Branch prev : groupMembers(st, b)) {
                cs.add(BranchSolver.Constraint.test(prev.testExpr, false, "前序 when 需为 false"));
            }
        } else if ("foreach".equals(b.kind)) {
            cs.add(BranchSolver.Constraint.collection(b.collectionName, u.side.equals("T"), u.description));
        }
        return cs;
    }

    /** Branches of the same choose-group that come before the given one. */
    private List<Branch> groupMembers(Statement st, Branch b) {
        List<Branch> members = new ArrayList<Branch>();
        if (b.groupId == null) return members;
        for (Branch other : st.branches) {
            if (b.groupId.equals(other.groupId) && other.groupIndex < b.groupIndex
                    && "when".equals(other.kind)) {
                members.add(other);
            }
        }
        return members;
    }

    private Branch findBranch(Statement st, String branchId) {
        for (Branch b : st.branches) {
            if (b.branchId.equals(branchId)) return b;
        }
        return null;
    }

    /**
     * Two coverage units of the same branch contradict each other when they
     * ask for opposite sides of the same decision (an {@code <if>} that has to
     * be true and false at the same time).
     */
    private boolean conflicts(Case c, CaseSpec cs) {
        if (cs.unit == null) return false;
        for (CoverageAnalyzer.Unit a : c.units) {
            CoverageAnalyzer.Unit b = cs.unit;
            if (a.branchId.equals(b.branchId) && !a.side.equals(b.side)) return true;
        }
        return false;
    }

    private String describe(List<CoverageAnalyzer.Unit> units) {
        if (units.isEmpty()) return "基础用例";
        StringBuilder sb = new StringBuilder();
        for (CoverageAnalyzer.Unit u : units) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(u.description);
        }
        return sb.toString();
    }

    private static String append(String note, String extra) {
        if (note == null || note.isEmpty()) return extra;
        return note + "; " + extra;
    }

    private static class CaseSpec {
        final CoverageAnalyzer.Unit unit;
        final List<BranchSolver.Constraint> constraints;
        CaseSpec(CoverageAnalyzer.Unit unit, List<BranchSolver.Constraint> constraints) {
            this.unit = unit;
            this.constraints = constraints;
        }
    }

    private static class Case {
        List<BranchSolver.Constraint> constraints = new ArrayList<BranchSolver.Constraint>();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        List<CoverageAnalyzer.Unit> units = new ArrayList<CoverageAnalyzer.Unit>();
        List<String> notes = new ArrayList<String>();
        boolean manual;
    }
}
