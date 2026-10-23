package com.mst.analysis;

import com.mst.model.Branch;
import com.mst.model.ParamRef;
import com.mst.model.Statement;
import com.mst.scan.InterfaceScanner;

import java.util.*;

/**
 * Resolves the most likely javaType for each parameter of a statement and
 * builds the {@link RootType} model used to generate test data.
 *
 * Priority for the type of a parameter:
 * <ol>
 *   <li>inline jdbcType in the XML (e.g. #{id,jdbcType=INTEGER})</li>
 *   <li>mapper interface method signature / POJO field scan</li>
 *   <li>OGNL test-expression heuristics (name != null, price &gt; 10 ...)</li>
 * </ol>
 * Anything still unknown is flagged so the UI can ask the user once.
 */
public class TypeResolver {

    private final InterfaceScanner ifaceScanner;

    public TypeResolver(InterfaceScanner ifaceScanner) {
        this.ifaceScanner = ifaceScanner;
    }

    public void resolve(Statement st) {
        resolve(st, Collections.<String, String>emptyMap());
    }

    /**
     * @param userTypes parameter name -> java type confirmed by the user
     *                  (already persisted in the decision store)
     */
    public void resolve(Statement st, Map<String, String> userTypes) {
        List<InterfaceScanner.ParamInfo> sig = lookupSignature(st);
        Map<String, RootType> roots = new LinkedHashMap<>();

        // 1. roots referenced by #{} / ${}
        for (ParamRef pr : st.params) {
            if (pr.itemOf != null) {
                // foreach item: its type comes from the collection element type;
                // for #{item.field} continue into the element class
                RootType coll = itemRootType(st, roots, pr.itemOf, sig);
                String elem = collectionElementType(coll, pr.itemOf);
                String jt = null;
                String itemPath = itemPropertyPath(pr.name);
                if (itemPath != null && elem != null) {
                    jt = fieldTypeOf(elem, itemPath);
                    if (jt == null && ("Map".equals(baseType(elem)) || "Object".equals(elem))) {
                        jt = "Object";
                    }
                } else if (itemPath == null) {
                    jt = elem;
                }
                if (jt != null && !jt.equals("Object")) {
                    pr.javaType = jt;
                    pr.typeSource = "SOURCE";
                } else {
                    pr.javaType = heuristicType(pr.name, st);
                    pr.typeSource = pr.javaType == null ? "UNKNOWN" : "HEURISTIC";
                }
                continue;
            }
            String root = pr.root();
            RootType rt = roots.get(root);
            if (rt == null) {
                rt = resolveRoot(root, pr, sig, st);
                roots.put(root, rt);
            }
            if (pr.declaredJdbcType != null) {
                pr.javaType = jdbcToJava(pr.declaredJdbcType);
                pr.typeSource = "JDBC_TYPE";
                if (rt.isUnknown()) {
                    rt.kind = RootType.SCALAR;
                    rt.javaType = pr.javaType;
                    rt.source = "JDBC_TYPE";
                }
            } else {
                String leaf = leafType(root, pr.name, rt, sig);
                pr.javaType = leaf;
                pr.typeSource = rt.isUnknown() ? (leaf == null ? "UNKNOWN" : "HEURISTIC") : "SOURCE";
                if (leaf == null) {
                    leaf = heuristicType(pr.name, st);
                    pr.javaType = leaf;
                    pr.typeSource = leaf == null ? "UNKNOWN" : "HEURISTIC";
                }
            }
        }

        // 2. roots referenced by branch test expressions
        for (Branch b : st.branches) {
            if ("foreach".equals(b.kind)) {
                if (b.collectionName != null && !b.collectionName.isEmpty()) {
                    String root = b.collectionName.contains(".")
                            ? b.collectionName.substring(0, b.collectionName.indexOf('.')) : b.collectionName;
                    if (!roots.containsKey(root)) {
                        roots.put(root, resolveRoot(root, null, sig, st));
                    }
                }
                continue;
            }
            for (String v : b.referencedVars) {
                String root = v.contains(".") ? v.substring(0, v.indexOf('.')) : v;
                if (!roots.containsKey(root)) {
                    roots.put(root, resolveRoot(root, null, sig, st));
                }
            }
        }

        applyUserTypes(st, roots, userTypes);

        st.rootTypes = new LinkedHashMap<>();
        st.rootTypes.putAll(roots);
    }

    private RootType itemRootType(Statement st, Map<String, RootType> roots, String collection, List<InterfaceScanner.ParamInfo> sig) {
        String root = collection.contains(".") ? collection.substring(0, collection.indexOf('.')) : collection;
        RootType rt = roots.get(root);
        if (rt == null) {
            rt = resolveRoot(root, null, sig, st);
            roots.put(root, rt);
        }
        return rt;
    }

    private String elementTypeOf(RootType rt) {
        if (rt == null) return null;
        return rt.elementType;
    }

    /** Element type of a collection root or of a nested collection property. */
    private String collectionElementType(RootType rt, String collectionPath) {
        if (rt == null || collectionPath == null) return null;
        if (!collectionPath.contains(".")) return rt.elementType;
        if (!rt.isPojo()) return rt.elementType;
        String fieldPath = collectionPath.substring(collectionPath.indexOf('.') + 1);
        String fieldType = fieldTypeOf(rt.javaType, fieldPath);
        if (fieldType == null) return null;
        return fromJavaType("item", fieldType, "SOURCE").elementType;
    }

    /** For a foreach item reference returns the property path after the item name. */
    private static String itemPropertyPath(String name) {
        String path = cleanPath(name);
        return path.contains(".") ? path.substring(path.indexOf('.') + 1) : null;
    }

    /** Removes array/index markers such as {@code [0]} from a property path. */
    private static String cleanPath(String path) {
        if (path == null) return null;
        return path.replaceAll("\\[[^\\]]*\\]", "");
    }

    /**
     * Applies the types confirmed by the user. This has to update the root
     * types as well, otherwise the chosen type would not take part in the
     * generated test cases.
     */
    private void applyUserTypes(Statement st, Map<String, RootType> roots, Map<String, String> userTypes) {
        if (userTypes == null || userTypes.isEmpty()) return;
        for (ParamRef pr : st.params) {
            String override = userTypes.get(pr.name);
            if (override == null || override.trim().isEmpty()) continue;
            override = override.trim();
            pr.javaType = override;
            pr.typeSource = "USER";

            if (pr.itemOf != null) {
                applyItemType(st, roots, pr, override);
                continue;
            }

            String root = pr.root();
            RootType rt = roots.get(root);
            if (pr.name.equals(root)) {
                roots.put(root, fromJavaType(root, override, "USER"));
                continue;
            }

            // nested property: record the type on the owning class so that
            // value generation and OGNL evaluation can use it as well
            String fieldPath = cleanPath(pr.name);
            if (fieldPath.contains(".")) fieldPath = fieldPath.substring(fieldPath.indexOf('.') + 1);
            if (rt == null) {
                rt = new RootType();
                rt.name = root;
                rt.kind = RootType.POJO;
                rt.source = "USER";
                roots.put(root, rt);
            }
            String owner = rt.isPojo() ? rt.javaType : (rt.isList() ? rt.elementType : null);
            if (owner != null) {
                setFieldOverride(owner, fieldPath, override);
                if (rt.isPojo()) rt.pojoFields = new LinkedHashMap<>(fieldMap(owner));
            }
        }

        // a confirmed collection type may make foreach item types resolvable
        for (ParamRef pr : st.params) {
            if (pr.itemOf == null || userTypes.containsKey(pr.name)) continue;
            String rootName = pr.itemOf.contains(".")
                    ? pr.itemOf.substring(0, pr.itemOf.indexOf('.')) : pr.itemOf;
            RootType coll = roots.get(rootName);
            String elem = collectionElementType(coll, pr.itemOf);
            String itemPath = itemPropertyPath(pr.name);
            String jt = itemPath != null && elem != null ? fieldTypeOf(elem, itemPath) : elem;
            if (jt == null && itemPath != null && elem != null
                    && ("Map".equals(baseType(elem)) || "Object".equals(elem))) {
                jt = "Object";
            }
            if (jt != null) {
                pr.javaType = jt;
                pr.typeSource = "SOURCE";
            } else if (itemPath != null) {
                pr.javaType = null;
                pr.typeSource = "UNKNOWN";
            }
        }
    }

    private void applyItemType(Statement st, Map<String, RootType> roots, ParamRef pr, String override) {
        String collection = pr.itemOf;
        String rootName = collection.contains(".") ? collection.substring(0, collection.indexOf('.')) : collection;
        RootType rt = roots.get(rootName);
        if (rt == null) {
            rt = new RootType();
            rt.name = rootName;
            rt.kind = RootType.LIST;
            rt.source = "USER";
            roots.put(rootName, rt);
        }

        String itemPath = itemPropertyPath(pr.name);
        if (itemPath != null) {
            String elem = collectionElementType(rt, collection);
            if (elem != null) setFieldOverride(elem, itemPath, override);
            return;
        }

        if (collection.contains(".")) {
            // nested collection field such as user.ids
            String fieldPath = collection.substring(collection.indexOf('.') + 1);
            String owner = rt.isPojo() ? rt.javaType : (rt.isList() ? rt.elementType : null);
            if (owner != null) {
                String old = fieldTypeOf(owner, fieldPath);
                String base = baseType(old);
                if (base == null) base = "List";
                setFieldOverride(owner, fieldPath, base + "<" + override + ">");
                if (rt.isPojo()) rt.pojoFields = new LinkedHashMap<>(fieldMap(owner));
            }
            return;
        }

        rt.kind = RootType.LIST;
        rt.elementType = override;
        if (rt.javaType == null || rt.javaType.equals("Object")) {
            rt.javaType = "List<" + override + ">";
        }
    }

    private void setFieldOverride(String ownerType, String fieldPath, String value) {
        if (ownerType == null || fieldPath == null || fieldPath.isEmpty()) return;
        String path = cleanPath(fieldPath);
        String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        String rest = path.contains(".") ? path.substring(path.indexOf('.') + 1) : null;
        Map<String, String> fields = fieldMap(ownerType);
        if (rest == null) {
            fields.put(head, value);
            return;
        }
        String next = fields.get(head);
        if (next != null) setFieldOverride(next, rest, value);
    }

    private Map<String, String> fieldMap(String type) {
        if (type == null) return new LinkedHashMap<String, String>();
        Map<String, String> fields = ifaceScanner.pojoIndex.get(type);
        if (fields == null) fields = ifaceScanner.pojoIndex.get(baseType(type));
        if (fields == null) {
            fields = new LinkedHashMap<String, String>();
            ifaceScanner.pojoIndex.put(baseType(type), fields);
            ifaceScanner.pojoIndex.put(type, fields);
        }
        return fields;
    }

    private RootType resolveRoot(String root, ParamRef pr, List<InterfaceScanner.ParamInfo> sig, Statement st) {
        RootType rt = new RootType();
        rt.name = root;
        rt.javaType = null;

        if (sig != null) {
            // 1. exact name match against an @Param / method parameter
            for (InterfaceScanner.ParamInfo p : sig) {
                if (p.name.equals(root)) {
                    return fromJavaType(root, p.javaType, "SOURCE");
                }
            }
            // 2. single parameter: the root may be a property of that object
            if (sig.size() == 1) {
                InterfaceScanner.ParamInfo only = sig.get(0);
                String fieldType = fieldTypeOf(only.javaType, root);
                if (fieldType != null) {
                    // let fromJavaType decide whether this is a scalar, a
                    // collection or another POJO that still has to be explored
                    return fromJavaType(root, fieldType, "SOURCE");
                }
                if (!only.annotated && !isSimple(only.javaType)) {
                    // the root itself is the (only) object
                    return fromJavaType(root, only.javaType, "SOURCE");
                }
                if (only.annotated && "Map".equals(baseType(only.javaType))) {
                    return fromJavaType(root, only.javaType, "SOURCE");
                }
            }
            // 3. paramN / argN default names
            if (root.matches("param\\d+")) {
                int n = Integer.parseInt(root.substring(5));
                if (n >= 1 && n <= sig.size()) return fromJavaType(root, sig.get(n - 1).javaType, "SOURCE");
            }
        }

        // 4. heuristic from the name
        String heur = heuristicType(root, st);
        if (heur != null) {
            rt = fromJavaType(root, heur, "HEURISTIC");
            return rt;
        }
        rt.kind = RootType.UNKNOWN;
        rt.source = "UNKNOWN";
        return rt;
    }

    private String leafType(String root, String paramName, RootType rt, List<InterfaceScanner.ParamInfo> sig) {
        String path = cleanPath(paramName);
        if (!path.contains(".")) {
            return rt.javaType;
        }
        String field = path.substring(path.indexOf('.') + 1);
        if (rt.isPojo()) {
            String ft = fieldTypeOf(rt.javaType, field);
            if (ft != null) return ft;
        }
        if (rt.isList()) {
            String elem = elementTypeOf(rt);
            if (elem != null) {
                String ft = fieldTypeOf(elem, field);
                if (ft != null) return ft;
            }
        }
        if (rt.isMap()) {
            return "Object";
        }
        return null;
    }

    /** Resolves a (possibly nested) field path on a POJO type. */
    public String fieldTypeOf(String pojoType, String fieldPath) {
        if (pojoType == null || fieldPath == null) return null;
        String path = cleanPath(fieldPath);
        String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        Map<String, String> fields = ifaceScanner.pojoIndex.get(pojoType);
        if (fields == null) fields = ifaceScanner.pojoIndex.get(baseType(pojoType));
        if (fields == null) return null;
        String ft = fields.get(head);
        if (ft == null) return null;
        if (path.contains(".")) {
            String rest = path.substring(path.indexOf('.') + 1);
            String deeper = fieldTypeOf(ft, rest);
            if (deeper != null) return deeper;
        }
        return ft;
    }

    /** Builds a RootType from a raw java type string such as "Map<String,Object>". */
    public RootType fromJavaType(String name, String javaType, String source) {
        RootType rt = new RootType();
        rt.name = name;
        rt.javaType = javaType;
        rt.source = source;
        String base = baseType(javaType);
        String generic = genericArgs(javaType);
        if (base == null) {
            rt.kind = RootType.UNKNOWN;
            rt.source = RootType.UNKNOWN;
            return rt;
        }
        String trimmed = javaType.trim();
        if (trimmed.endsWith("[]") && !"byte[]".equals(trimmed)) {
            rt.kind = RootType.LIST;
            rt.elementType = trimmed.substring(0, trimmed.length() - 2);
            return rt;
        }
        if (base.equals("Map")) {
            rt.kind = RootType.MAP;
            rt.elementType = generic == null ? "Object" : generic.split(",")[generic.split(",").length - 1].trim();
            return rt;
        }
        if (base.equals("List") || base.equals("Set") || base.equals("Collection")
                || base.equals("Iterable") || base.equals("ArrayList") || base.equals("HashSet")) {
            rt.kind = RootType.LIST;
            rt.elementType = generic == null ? "Object" : generic.trim();
            return rt;
        }
        if (isSimple(base)) {
            rt.kind = RootType.SCALAR;
            return rt;
        }
        rt.kind = RootType.POJO;
        Map<String, String> fields = ifaceScanner.pojoIndex.get(javaType);
        if (fields == null) fields = ifaceScanner.pojoIndex.get(base);
        if (fields != null) rt.pojoFields = new LinkedHashMap<>(fields);
        return rt;
    }

    public static String baseType(String javaType) {
        if (javaType == null) return null;
        String t = javaType.trim();
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return t.trim();
    }

    public static String genericArgs(String javaType) {
        if (javaType == null) return null;
        int lt = javaType.indexOf('<');
        int gt = javaType.lastIndexOf('>');
        if (lt < 0 || gt < lt) return null;
        return javaType.substring(lt + 1, gt).trim();
    }

    private List<InterfaceScanner.ParamInfo> lookupSignature(Statement st) {
        Map<String, List<InterfaceScanner.ParamInfo>> idx = ifaceScanner.methodIndex;
        List<InterfaceScanner.ParamInfo> r = idx.get(st.fullId());
        if (r != null) return r;
        // loose match by simple interface name
        String simple = st.namespace;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        r = idx.get(simple + "." + st.id);
        if (r != null) return r;
        // match by method name only (last resort)
        for (Map.Entry<String, List<InterfaceScanner.ParamInfo>> e : idx.entrySet()) {
            if (e.getKey().endsWith("." + st.id)) return e.getValue();
        }
        return null;
    }

    private static boolean isSimple(String t) {
        if (t == null) return true;
        switch (baseType(t)) {
            case "String": case "Integer": case "int": case "Long": case "long":
            case "BigDecimal": case "Double": case "double": case "Float": case "float":
            case "Short": case "short": case "Boolean": case "boolean":
            case "Date": case "LocalDate": case "LocalDateTime": case "LocalTime":
            case "Timestamp": case "byte[]": case "Object": case "CharSequence":
            case "Character": case "char": case "Byte": case "byte": case "BigInteger":
                return true;
            default:
                return false;
        }
    }

    private String heuristicType(String name, Statement st) {
        // Look at how the variable is used in tests.
        for (Branch b : st.branches) {
            if (b.testExpr == null) continue;
            String e = b.testExpr;
            String root = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (!e.contains(root)) continue;
            if (e.matches(".*\\b" + java.util.regex.Pattern.quote(root) + "\\s*[<>]=?\\s*\\d+.*")) {
                return "Integer";
            }
            if (e.matches(".*\\b" + java.util.regex.Pattern.quote(root) + "\\s*(==|!=)\\s*'[^']*'.*")) {
                return "String";
            }
        }
        // name-based convention
        String lower = name.toLowerCase();
        if (lower.endsWith("id") || lower.endsWith("ids")) return "Long";
        if (lower.contains("time") || lower.contains("date")) return "Date";
        return null;
    }

    public static String jdbcToJava(String jdbc) {
        switch (jdbc.toUpperCase()) {
            case "INTEGER": case "INT": case "SMALLINT": return "Integer";
            case "BIGINT": return "Long";
            case "DECIMAL": case "NUMERIC": case "FLOAT": case "DOUBLE": case "REAL": return "BigDecimal";
            case "BOOLEAN": case "BIT": return "Boolean";
            case "DATE": case "TIME": case "TIMESTAMP": return "Date";
            case "BLOB": case "BINARY": case "VARBINARY": return "byte[]";
            case "VARCHAR": case "CHAR": case "LONGVARCHAR": case "CLOB": return "String";
            default: return "String";
        }
    }
}
