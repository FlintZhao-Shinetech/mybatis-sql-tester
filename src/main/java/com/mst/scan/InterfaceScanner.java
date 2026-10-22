package com.mst.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans a source tree for Mapper interfaces and builds a signature index:
 * namespace + methodName -> ordered list of (paramName, javaType).
 *
 * This is what lets us resolve #{...} types automatically from the source
 * instead of asking the user.
 */
public class InterfaceScanner {

    /** namespace.method -> List of ParamInfo (name + javaType) */
    public final Map<String, List<ParamInfo>> methodIndex = new LinkedHashMap<>();

    /** POJO class simple-name -> field name -> javaType. Only collected loosely. */
    public final Map<String, Map<String, String>> pojoIndex = new LinkedHashMap<>();

    public static class ParamInfo {
        public String name;
        public String javaType;
        public boolean annotated; // true if @Param gave the name
        public ParamInfo(String name, String javaType, boolean annotated) {
            this.name = name;
            this.javaType = javaType;
            this.annotated = annotated;
        }
    }

    public void scanProject(String rootDir) throws Exception {
        Path root = new File(rootDir).toPath();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + rootDir);
        }
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        for (Path p : javaFiles) {
            try {
                parseFile(p);
            } catch (Exception e) {
                // Skip unparseable files; source trees often contain generated code.
            }
        }
    }

    private void parseFile(Path file) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file);
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(decl -> {
            if (!decl.isInterface()) return;
            String fqn = decl.getFullyQualifiedName().orElse(decl.getNameAsString());

            // Collect whether this looks like a mapper (has @Mapper, extends BaseMapper, or
            // has methods whose params use @Param / return our entities). We index all
            // interfaces; matching against XML namespaces happens later.
            for (MethodDeclaration m : decl.getMethods()) {
                List<ParamInfo> params = new ArrayList<>();
                List<Parameter> ps = m.getParameters();
                for (int i = 0; i < ps.size(); i++) {
                    Parameter prm = ps.get(i);
                    String pname = paramName(prm, i, ps.size());
                    String jt = javaTypeName(prm.getType());
                    boolean annotated = hasParamAnnotation(prm);
                    params.add(new ParamInfo(pname, jt, annotated));
                }
                methodIndex.put(fqn + "." + m.getNameAsString(), params);
                // Also index by simple interface name for loose matching.
                methodIndex.putIfAbsent(decl.getNameAsString() + "." + m.getNameAsString(), params);
            }
        });

        // Collect POJO fields from any class in the tree (best effort).
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(decl -> {
            if (decl.isInterface()) return;
            Map<String, String> fields = new LinkedHashMap<>();
            decl.getFields().forEach(f ->
                f.getVariables().forEach(v -> fields.put(v.getNameAsString(), javaTypeName(v.getType()))));
            // getters/setters
            decl.getMethods().forEach(m -> {
                boolean isGetter = m.getNameAsString().startsWith("get") && m.getParameters().isEmpty()
                        && !m.getType().isVoidType();
                if (isGetter && m.getNameAsString().length() > 3) {
                    String prop = decap(m.getNameAsString().substring(3));
                    fields.putIfAbsent(prop, javaTypeName(m.getType()));
                }
            });
            if (!fields.isEmpty()) {
                pojoIndex.put(decl.getNameAsString(), fields);
                decl.getFullyQualifiedName().ifPresent(fqn -> pojoIndex.put(fqn, fields));
            }
        });
    }

    private static String paramName(Parameter prm, int idx, int total) {
        for (AnnotationExpr a : prm.getAnnotations()) {
            if (a.getNameAsString().equals("Param")) {
                if (a instanceof SingleMemberAnnotationExpr) {
                    return strip( ((SingleMemberAnnotationExpr) a).getMemberValue().toString());
                } else if (a instanceof NormalAnnotationExpr) {
                    for (MemberValuePair kv : ((NormalAnnotationExpr) a).getPairs()) {
                        if (kv.getNameAsString().equals("value")) {
                            return strip(kv.getValue().toString());
                        }
                    }
                }
            }
        }
        String n = prm.getNameAsString();
        // MyBatis default names when no @Param and multiple params.
        if (total > 1) {
            // keep actual java name but also expose paramN alias elsewhere
            return n;
        }
        return n;
    }

    private static boolean hasParamAnnotation(Parameter prm) {
        for (AnnotationExpr a : prm.getAnnotations()) {
            if (a.getNameAsString().equals("Param")) return true;
        }
        return false;
    }

    private static String strip(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String decap(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String javaTypeName(Type t) {
        if (t instanceof ArrayType) {
            return javaTypeName(((ArrayType) t).getComponentType()) + "[]";
        }
        if (t instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType c = (ClassOrInterfaceType) t;
            String base = c.getNameAsString();
            if (c.getTypeArguments().isPresent()) {
                List<String> args = new ArrayList<String>();
                for (Type a : c.getTypeArguments().get()) args.add(javaTypeName(a));
                return base + "<" + String.join(",", args) + ">";
            }
            return base;
        }
        return t.asString();
    }
}
