package com.mst.model;

/**
 * A parameter referenced in a mapper statement, e.g. #{userName} or #{id,jdbcType=INTEGER}.
 */
public class ParamRef {
    /** Raw reference name, e.g. "userName", "user.age", "ids[0]" */
    public String name;
    /** jdbcType declared inline in the XML, may be null */
    public String declaredJdbcType;
    /** Java type resolved from mapper interface / POJO scanning, may be null */
    public String javaType;
    /** Where the javaType came from: JDBC_TYPE, SOURCE, HEURISTIC, USER, IMPLICIT */
    public String typeSource = "NONE";
    /** Whether this is a ${} raw substitution rather than #{} */
    public boolean raw;
    /** Set when the parameter is a &lt;foreach&gt; item; holds the collection name. */
    public String itemOf;

    public ParamRef() {}

    public ParamRef(String name) {
        this.name = name;
    }

    /** The top level variable this reference belongs to ("user.age" -> "user"). */
    public String root() {
        String n = name.replaceAll("\\[[^\\]]*\\]", "");
        return n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
    }
}
