package com.mst.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the constraints a single parameter path has to satisfy
 * (e.g. {@code status == 'A'} plus {@code status != null}) and derives a
 * concrete value from them.
 */
public class PathSpec {

    public final String path;

    /** exact value required */
    public String eq;
    public boolean eqQuoted;
    /** the required value is a single character literal ({@code 'A'}) */
    public boolean eqChar;
    /** the value must be null / must not be null */
    public boolean isNull;
    public boolean notNull;
    /** numeric bounds */
    public Double gt, ge, lt, le;
    /** emptiness constraints */
    public boolean empty;
    public boolean notEmpty;
    /** string constraints */
    public String contains, startsWith, endsWith;
    /** regular expression the value has to match / must not match */
    public String matches, notMatches;
    /** values the parameter must not have (soft constraints) */
    public final List<String> notEq = new ArrayList<>();
    /** single character literals the parameter must not have */
    public final List<String> notEqChars = new ArrayList<>();

    /** true when at least one hard constraint was collected */
    public boolean hasPositive;
    /** set when the value had to be a character because of a char literal */
    public boolean usedCharLiteral;
    /** set when the collected constraints cannot be satisfied together */
    public boolean conflict;
    public String conflictReason;

    public PathSpec(String path) {
        this.path = path;
    }

    /** Applies the constraint that makes the atom true. */
    public void applyTrue(ExprNode atom) {
        hasPositive = true;
        switch (atom.op) {
            case EQ:
                if (eq != null && !eq.equals(atom.literal)) {
                    conflict = true;
                    conflictReason = "requires both '" + eq + "' and '" + atom.literal + "'";
                }
                eq = atom.literal;
                eqQuoted = atom.literalQuoted;
                eqChar = atom.literalChar;
                if (notEq.contains(atom.literal)) {
                    conflict = true;
                    conflictReason = "must equal and must not equal '" + atom.literal + "'";
                }
                break;
            case NE:
                if (atom.literal != null) {
                    if (atom.literalChar) notEqChars.add(atom.literal);
                    else notEq.add(atom.literal);
                }
                break;
            case NULL:
                if (notNull) {
                    conflict = true;
                    conflictReason = "must be null and must not be null";
                }
                isNull = true;
                break;
            case NOTNULL:
                if (isNull) {
                    conflict = true;
                    conflictReason = "must be null and must not be null";
                }
                notNull = true;
                break;
            case GT: gt = num(atom.literal, gt); break;
            case GE: ge = num(atom.literal, ge); break;
            case LT: lt = num(atom.literal, lt); break;
            case LE: le = num(atom.literal, le); break;
            case EMPTY:
                if (notEmpty) {
                    conflict = true;
                    conflictReason = "collection must be empty and must not be empty";
                }
                empty = true;
                notEmpty = false;
                break;
            case NOTEMPTY:
                if (empty) {
                    conflict = true;
                    conflictReason = "collection must be empty and must not be empty";
                }
                notEmpty = true;
                empty = false;
                break;
            case CONTAINS: contains = atom.literal; break;
            case STARTSWITH: startsWith = atom.literal; break;
            case ENDSWITH: endsWith = atom.literal; break;
            case MATCHES:
                if (matches != null && !matches.equals(atom.literal)) {
                    conflict = true;
                    conflictReason = "requires two different regular expressions";
                }
                matches = atom.literal;
                break;
            default:
                conflict = true;
                conflictReason = atom.unsupportedReason;
        }
    }

    /** Applies the constraint that makes the atom false. */
    public void applyFalse(ExprNode atom) {
        switch (atom.op) {
            case EQ:
                if (atom.literal != null) {
                    if (atom.literalChar) notEqChars.add(atom.literal);
                    else notEq.add(atom.literal);
                }
                if (eq != null && eq.equals(atom.literal)) {
                    conflict = true;
                    conflictReason = "must equal '" + eq + "' and must not equal it";
                }
                break;
            case NE:
                if (eq != null && !eq.equals(atom.literal)) {
                    conflict = true;
                    conflictReason = "requires both '" + eq + "' and '" + atom.literal + "'";
                }
                eq = atom.literal;
                eqQuoted = atom.literalQuoted;
                eqChar = atom.literalChar;
                hasPositive = true;
                break;
            case NULL: notNull = true; break;
            case NOTNULL: isNull = true; break;
            case GT: le = num(atom.literal, le); break;
            case GE: lt = num(atom.literal, lt); break;
            case LT: ge = num(atom.literal, ge); break;
            case LE: gt = num(atom.literal, gt); break;
            case EMPTY: notEmpty = true; break;
            case NOTEMPTY: empty = true; break;
            case MATCHES: notMatches = atom.literal; break;
            default:
                // cannot be expressed precisely, the OGNL verification will catch it
                break;
        }
    }

    private static Double num(String literal, Double current) {
        try {
            Double v = Double.valueOf(literal.trim());
            return current == null ? v : current;
        } catch (Exception e) {
            return current;
        }
    }

    /** Derives a concrete value satisfying the collected constraints. */
    public Object compute(ValueFactory vf, String leafType, String name) {
        if (conflict) return null;
        if (isNull) return null;
        if (eq != null) {
            if (eqChar) {
                usedCharLiteral = true;
                return charValue(eq, leafType);
            }
            Object v = vf.convertLiteral(eq, eqQuoted, leafType);
            return v;
        }
        if (!notEqChars.isEmpty()) {
            // the value has to be a character as well, otherwise OGNL would
            // try to parse it as a number and blow up
            usedCharLiteral = true;
            return charValue(differentChar(notEqChars), leafType);
        }
        if (matches != null) {
            return vf.regexSample(matches, leafType);
        }
        if (notMatches != null) {
            return vf.nonMatchingSample(notMatches, leafType);
        }
        if (contains != null) {
            Object v = vf.convertLiteral(contains, true, leafType);
            if (v instanceof String) return v + "x";
            return v;
        }
        if (startsWith != null) {
            Object v = vf.convertLiteral(startsWith, true, leafType);
            if (v instanceof String) return v + "x";
            return v;
        }
        if (endsWith != null) {
            Object v = vf.convertLiteral(endsWith, true, leafType);
            if (v instanceof String) return "x" + v;
            return v;
        }
        if (notEmpty) {
            String base = TypeResolver.baseType(leafType);
            if (base != null && (base.equals("List") || base.equals("Set") || base.equals("Collection"))) {
                java.util.List<Object> l = new java.util.ArrayList<Object>();
                l.add(vf.sampleForType(elementTypeOf(leafType), name));
                l.add(vf.sampleForType(elementTypeOf(leafType), name));
                return l;
            }
            return vf.sampleForType(leafType, name);
        }
        if (empty) {
            String base = TypeResolver.baseType(leafType);
            if (base != null && (base.equals("List") || base.equals("Set") || base.equals("Collection"))) {
                return new java.util.ArrayList<Object>();
            }
            return "";
        }
        Double lo = null, hi = null;
        boolean integral = isIntegral(leafType);
        if (gt != null) lo = gt + (integral ? 1d : 1e-9d);
        if (ge != null) lo = (lo == null) ? ge : Math.max(lo, ge);
        if (lt != null) hi = lt - (integral ? 1d : 1e-9d);
        if (le != null) hi = (hi == null) ? le : Math.min(hi, le);
        if (lo != null && hi != null && lo > hi) return null;
        if (notNull) {
            // pick something inside the bounds if there are any
            if (lo != null || hi != null) {
                Double v = lo != null ? lo : hi;
                return numericValue(vf, v, leafType);
            }
            return vf.sampleForType(leafType, name);
        }
        if (lo != null) return numericValue(vf, lo, leafType);
        if (hi != null) return numericValue(vf, hi, leafType);
        if (notNull) return vf.sampleForType(leafType, name);
        return vf.defaultForType(leafType, name);
    }

    /** The element type of a collection type such as "List&lt;Long&gt;". */
    private static String elementTypeOf(String leafType) {
        String generic = TypeResolver.genericArgs(leafType);
        if (generic == null) return null;
        String[] parts = generic.split(",");
        return parts[parts.length - 1].trim();
    }

    /** true for java types that can only hold whole numbers. */
    private static boolean isIntegral(String leafType) {
        String base = TypeResolver.baseType(leafType);
        if (base == null) return false;
        switch (base) {
            case "Integer": case "int": case "Long": case "long":
            case "Short": case "short": case "Byte": case "byte":
            case "BigInteger":
                return true;
            default:
                return false;
        }
    }

    /**
     * Builds the value for a single character literal. OGNL treats
     * {@code 'A'} as a {@code char} and compares numerically, so the value has
     * to be a character too - otherwise the expression cannot even be
     * evaluated.
     */
    private static Object charValue(String literal, String leafType) {
        if (literal == null || literal.isEmpty()) return null;
        char c = literal.charAt(0);
        String base = TypeResolver.baseType(leafType);
        if ("String".equals(base) || "CharSequence".equals(base)) {
            // keep the declared type, OGNL compares the numeric representation
            return String.valueOf((int) c);
        }
        return Character.valueOf(c);
    }

    /** A character that is different from all the given ones. */
    private static String differentChar(List<String> forbidden) {
        char c = 'A';
        outer:
        for (int i = 0; i < 26; i++) {
            c = (char) ('A' + i);
            for (String f : forbidden) {
                if (!f.isEmpty() && f.charAt(0) == c) continue outer;
            }
            break;
        }
        return String.valueOf(c);
    }

    private Object numericValue(ValueFactory vf, Double v, String leafType) {        String base = TypeResolver.baseType(leafType);
        if (base == null) {
            return v == null ? null : (v == Math.floor(v) ? (Object) v.longValue() : (Object) v.doubleValue());
        }
        switch (base) {
            case "Integer": case "int": return (int) Math.round(v);
            case "Long": case "long": return Math.round(v);
            case "Short": case "short": return (short) Math.round(v);
            case "Double": case "double": return v;
            case "Float": case "float": return v.floatValue();
            case "BigDecimal": return new java.math.BigDecimal(v);
            default: return v;
        }
    }

}
