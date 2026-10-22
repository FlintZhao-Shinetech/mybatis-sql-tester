package com.mst.analysis;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Creates sample values for parameters and converts literals to the java type
 * that was resolved for the parameter.
 */
public class ValueFactory {

    /** A neutral value for a root parameter, used as the base of every test case. */
    public Object defaultFor(RootType rt) {
        if (rt == null) return null;
        switch (rt.kind) {
            case RootType.MAP:
                return new LinkedHashMap<String, Object>();
            case RootType.LIST:
                return new ArrayList<Object>();
            case RootType.POJO: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, String> e : rt.pojoFields.entrySet()) {
                    m.put(e.getKey(), defaultForType(e.getValue(), e.getKey()));
                }
                return m;
            }
            case RootType.SCALAR:
                return defaultForType(rt.javaType, rt.name);
            default:
                return null;
        }
    }

    /**
     * A usable sample value for a root parameter. Unlike {@link #defaultFor}
     * the values are not empty, so the rendered SQL contains real data.
     */
    public Object sampleFor(RootType rt) {
        if (rt == null) return null;
        switch (rt.kind) {
            case RootType.MAP:
                return new LinkedHashMap<String, Object>();
            case RootType.LIST: {
                List<Object> list = new ArrayList<Object>();
                list.add(sampleForType(rt.elementType, rt.name));
                return list;
            }
            case RootType.POJO: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, String> e : rt.pojoFields.entrySet()) {
                    m.put(e.getKey(), sampleForType(e.getValue(), e.getKey()));
                }
                return m;
            }
            case RootType.SCALAR:
                return sampleForType(rt.javaType, rt.name);
            default:
                return null;
        }
    }

    /** A neutral value for a java type: numbers 0, booleans false, everything else null. */
    public Object defaultForType(String javaType, String name) {
        String base = TypeResolver.baseType(javaType);
        if (base == null) return null;
        switch (base) {
            case "Integer": case "int": return 0;
            case "Long": case "long": return 0L;
            case "Short": case "short": return (short) 0;
            case "Double": case "double": return 0d;
            case "Float": case "float": return 0f;
            case "BigDecimal": return BigDecimal.ZERO;
            case "BigInteger": return BigInteger.ZERO;
            case "Boolean": case "boolean": return Boolean.FALSE;
            default: return null;
        }
    }

    /** A non-null sample value for a java type. */
    public Object sampleForType(String javaType, String name) {
        String base = TypeResolver.baseType(javaType);
        if (base == null) base = "String";
        switch (base) {
            case "Integer": case "int": return 1;
            case "Long": case "long": return 1L;
            case "Short": case "short": return (short) 1;
            case "Double": case "double": return 1d;
            case "Float": case "float": return 1f;
            case "BigDecimal": return new BigDecimal("1.00");
            case "BigInteger": return BigInteger.ONE;
            case "Boolean": case "boolean": return Boolean.TRUE;
            case "Date": case "Timestamp": return new Date();
            case "LocalDate": return java.time.LocalDate.now();
            case "LocalDateTime": return java.time.LocalDateTime.now();
            case "byte[]": return new byte[]{1};
            case "Object": return "test";
            default: {
                String lower = name == null ? "" : name.toLowerCase();
                if (lower.endsWith("id")) return "1";
                if (lower.contains("email")) return "test@example.com";
                return "test";
            }
        }
    }

    /**
     * Converts a literal coming from an OGNL expression to the java type of the
     * parameter. Returns null when the literal cannot be represented.
     */
    public Object convertLiteral(String literal, boolean quoted, String javaType) {
        String base = TypeResolver.baseType(javaType);
        if (base == null || base.equals("Object")) {
            if (quoted) return literal;
            return numeric(literal);
        }
        switch (base) {
            case "String": case "CharSequence":
                return literal;
            case "Integer": case "int": {
                if (quoted) return null;
                try { return Integer.valueOf(literal.trim()); } catch (Exception e) { return null; }
            }
            case "Long": case "long": {
                if (quoted) return null;
                try { return Long.valueOf(literal.trim()); } catch (Exception e) { return null; }
            }
            case "Short": case "short": {
                if (quoted) return null;
                try { return Short.valueOf(literal.trim()); } catch (Exception e) { return null; }
            }
            case "Double": case "double": {
                if (quoted) return null;
                try { return Double.valueOf(literal.trim()); } catch (Exception e) { return null; }
            }
            case "Float": case "float": {
                if (quoted) return null;
                try { return Float.valueOf(literal.trim()); } catch (Exception e) { return null; }
            }
            case "BigDecimal": {
                if (quoted) return null;
                try { return new BigDecimal(literal.trim()); } catch (Exception e) { return null; }
            }
            case "Boolean": case "boolean": {
                if (quoted) return null;
                return Boolean.valueOf(literal.trim());
            }
            case "Date": case "Timestamp": case "LocalDate": case "LocalDateTime":
                return new Date();
            default:
                if (quoted) return literal;
                return numeric(literal);
        }
    }

    private Object numeric(String literal) {
        try { return Long.valueOf(literal.trim()); } catch (Exception e) {
            try { return Double.valueOf(literal.trim()); } catch (Exception e2) { return literal; }
        }
    }

    /**
     * A value that should match the given regular expression. The expression is
     * simplified (meta characters are dropped), the result is verified by the
     * OGNL engine afterwards.
     */
    public Object regexSample(String regex, String javaType) {
        String s = regex == null ? "" : regex;
        s = s.replaceAll("\\\\d", "0").replaceAll("\\\\w", "a").replaceAll("\\\\s", " ");
        s = s.replaceAll("[^A-Za-z0-9]", "");
        if (s.isEmpty()) s = "a";
        return convertLiteral(s, true, javaType);
    }

    /** A value that should not match the given regular expression. */
    public Object nonMatchingSample(String regex, String javaType) {
        String s = "zz_no_match_zz";
        try {
            int guard = 0;
            while (s.matches(regex) && guard++ < 20) {
                s = s + "_x";
            }
        } catch (Exception ignored) {
            // invalid regular expression, keep the plain value
        }
        return convertLiteral(s, true, javaType);
    }

    /** Assigns a value at a (possibly nested) property path inside the parameter map. */
    @SuppressWarnings("unchecked")
    public void assignPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object o = cur.get(parts[i]);
            if (!(o instanceof Map)) {
                o = new LinkedHashMap<String, Object>();
                cur.put(parts[i], o);
            }
            cur = (Map<String, Object>) o;
        }
        cur.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    public Object readPath(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(p);
            } else {
                return null;
            }
        }
        return cur;
    }
}
