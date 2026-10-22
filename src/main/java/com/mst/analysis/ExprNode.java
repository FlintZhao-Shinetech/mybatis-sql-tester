package com.mst.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal parser for the OGNL test expressions used in dynamic SQL
 * ({@code <if test="...">}, {@code <when test="...">}).
 *
 * <p>The expression is split into a small tree of AND / OR nodes whose leaves are
 * comparison atoms. The atoms are the ones the solver can turn into concrete
 * parameter values.</p>
 */
public class ExprNode {

    public enum Kind { AND, OR, ATOM }

    /** Comparison kinds understood by the solver. */
    public enum Op {
        EQ, NE, NULL, NOTNULL, GT, GE, LT, LE,
        EMPTY, NOTEMPTY, CONTAINS, STARTSWITH, ENDSWITH, MATCHES, UNSUPPORTED
    }

    private static final Pattern VAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

    public Kind kind;
    public String text;
    public List<ExprNode> children = new ArrayList<>();

    /** Atom fields */
    public Op op = Op.UNSUPPORTED;
    public String var;
    public String literal;
    public boolean literalQuoted;
    /**
     * true when the literal was written with single quotes and holds exactly
     * one character. OGNL parses such a literal as a {@code char} and then
     * compares numerically, so the value has to be a {@code Character} as well.
     */
    public boolean literalChar;
    /** set when the atom could not be understood */
    public String unsupportedReason;

    public static ExprNode parse(String expression) {
        String e = expression == null ? "" : expression.trim();
        if (e.startsWith("(") && e.endsWith(")") && balanced(e.substring(1, e.length() - 1))) {
            e = e.substring(1, e.length() - 1).trim();
        }
        List<String> orParts = splitTop(e, "or");
        if (orParts.size() > 1) {
            ExprNode n = new ExprNode();
            n.kind = Kind.OR;
            n.text = e;
            for (String p : orParts) n.children.add(parse(p));
            return n;
        }
        List<String> andParts = splitTop(e, "and");
        if (andParts.size() > 1) {
            ExprNode n = new ExprNode();
            n.kind = Kind.AND;
            n.text = e;
            for (String p : andParts) n.children.add(parse(p));
            return n;
        }
        ExprNode n = new ExprNode();
        n.kind = Kind.ATOM;
        n.text = e;
        classify(n);
        return n;
    }

    /** Splits on a keyword that appears outside quotes and parentheses. */
    public static List<String> splitTop(String s, String kw) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int last = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; i++; continue; }
            if (c == '(') { depth++; i++; continue; }
            if (c == ')') { depth--; i++; continue; }
            if (depth == 0 && isKeywordAt(s, i, kw)) {
                parts.add(s.substring(last, i));
                i += kw.length();
                last = i;
                continue;
            }
            i++;
        }
        parts.add(s.substring(last));
        List<String> out = new ArrayList<>();
        for (String p : parts) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private static boolean isKeywordAt(String s, int i, String kw) {
        if (!s.startsWith(kw, i)) return false;
        if (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '_')) return false;
        int end = i + kw.length();
        if (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) return false;
        return true;
    }

    private static boolean balanced(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return depth == 0;
    }

    private static void classify(ExprNode n) {
        String t = n.text == null ? "" : n.text.trim();
        if (t.isEmpty()) {
            n.op = Op.UNSUPPORTED;
            n.unsupportedReason = "empty expression";
            return;
        }
        if (t.startsWith("!")) {
            t = t.substring(1).trim();
        }

        // method calls
        Matcher m = Pattern.compile("^(.+?)\\.size\\(\\)\\s*(>=|>)\\s*(\\d+)$").matcher(t);
        if (m.matches()) {
            n.var = m.group(1).trim();
            n.op = (m.group(3).equals("0") && m.group(2).equals(">")) ? Op.EMPTY : Op.NOTEMPTY;
            n.literal = m.group(3);
            return;
        }
        m = Pattern.compile("^(.+?)\\.size\\(\\)\\s*(==|!=)\\s*(\\d+)$").matcher(t);
        if (m.matches()) {
            n.var = m.group(1).trim();
            boolean empty = m.group(3).equals("0");
            n.op = (m.group(2).equals("==") == empty) ? Op.EMPTY : Op.NOTEMPTY;
            n.literal = m.group(3);
            return;
        }
        m = Pattern.compile("^(.+?)\\.length\\(\\)\\s*(>=|>)\\s*(\\d+)$").matcher(t);
        if (m.matches()) {
            n.var = m.group(1).trim();
            n.op = (m.group(3).equals("0") && m.group(2).equals(">")) ? Op.EMPTY : Op.NOTEMPTY;
            n.literal = m.group(3);
            return;
        }
        m = Pattern.compile("^(.+?)\\.isEmpty\\(\\)$").matcher(t);
        if (m.matches()) {
            n.var = m.group(1).trim();
            n.op = Op.EMPTY;
            return;
        }
        m = Pattern.compile("^(.+?)\\.(contains|startsWith|endsWith|matches)\\((.+)\\)$").matcher(t);
        if (m.matches()) {
            n.var = m.group(1).trim();
            String fn = m.group(2);
            n.op = "contains".equals(fn) ? Op.CONTAINS : "startsWith".equals(fn) ? Op.STARTSWITH
                    : "endsWith".equals(fn) ? Op.ENDSWITH : Op.MATCHES;
            n.literal = unquote(m.group(3).trim());
            n.literalQuoted = true;
            return;
        }

        // comparisons
        m = Pattern.compile("^(.+?)\\s*!=\\s*(.+)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim();
            String lit = m.group(2).trim();
            if ("null".equals(lit)) { n.op = Op.NOTNULL; return; }
            n.op = Op.NE;
            n.literal = unquote(lit);
            n.literalQuoted = isQuoted(lit);
            n.literalChar = isCharLiteral(lit);
            return;
        }
        m = Pattern.compile("^(.+?)\\s*==\\s*(.+)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim();
            String lit = m.group(2).trim();
            if ("null".equals(lit)) { n.op = Op.NULL; return; }
            n.op = Op.EQ;
            n.literal = unquote(lit);
            n.literalQuoted = isQuoted(lit);
            n.literalChar = isCharLiteral(lit);
            return;
        }
        m = Pattern.compile("^(.+?)\\s*>=\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim(); n.op = Op.GE; n.literal = m.group(2); return;
        }
        m = Pattern.compile("^(.+?)\\s*<=\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim(); n.op = Op.LE; n.literal = m.group(2); return;
        }
        m = Pattern.compile("^(.+?)\\s*>\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim(); n.op = Op.GT; n.literal = m.group(2); return;
        }
        m = Pattern.compile("^(.+?)\\s*<\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(t);
        if (m.matches() && isVar(m.group(1))) {
            n.var = m.group(1).trim(); n.op = Op.LT; n.literal = m.group(2); return;
        }

        n.op = Op.UNSUPPORTED;
        n.unsupportedReason = "cannot interpret '" + t + "'";
    }

    private static boolean isVar(String s) {
        return s != null && VAR.matcher(s.trim()).matches();
    }

    private static boolean isQuoted(String s) {
        return s.length() >= 2 && (s.startsWith("'") || s.startsWith("\""))
                && s.endsWith(s.substring(0, 1));
    }

    /** {@code 'x'} - a single quoted literal with exactly one character. */
    private static boolean isCharLiteral(String s) {
        return s.length() == 3 && s.startsWith("'") && s.endsWith("'");
    }

    private static String unquote(String s) {
        if (isQuoted(s)) return s.substring(1, s.length() - 1);
        return s;
    }

    /** Human readable description, used in the UI. */
    public String describe() {
        switch (kind) {
            case AND: return "(" + join(children, " AND ") + ")";
            case OR: return "(" + join(children, " OR ") + ")";
            default: return text;
        }
    }

    private static String join(List<ExprNode> nodes, String sep) {
        StringBuilder sb = new StringBuilder();
        for (ExprNode n : nodes) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(n.describe());
        }
        return sb.toString();
    }
}
