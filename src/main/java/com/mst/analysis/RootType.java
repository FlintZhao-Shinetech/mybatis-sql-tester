package com.mst.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved type information for one top level parameter of a statement.
 */
public class RootType {

    public static final String SCALAR = "SCALAR";
    public static final String MAP = "MAP";
    public static final String LIST = "LIST";
    public static final String POJO = "POJO";
    public static final String UNKNOWN = "UNKNOWN";

    /** Parameter name as used in the mapper XML. */
    public String name;
    /** Raw java type, e.g. "String", "Map<String,Object>", "List<Long>", "User" */
    public String javaType;
    /** SCALAR | MAP | LIST | POJO | UNKNOWN */
    public String kind = UNKNOWN;
    /** Element type for collections, e.g. "Long". */
    public String elementType;
    /** Field name -> java type for POJO parameters. */
    public Map<String, String> pojoFields = new LinkedHashMap<>();
    /** Where the information came from: JDBC_TYPE, SOURCE, HEURISTIC, USER, UNKNOWN */
    public String source = UNKNOWN;

    public boolean isMap() { return MAP.equals(kind); }
    public boolean isList() { return LIST.equals(kind); }
    public boolean isPojo() { return POJO.equals(kind); }
    public boolean isUnknown() { return UNKNOWN.equals(kind); }

    @Override
    public String toString() {
        return name + ":" + kind + (javaType == null ? "" : "(" + javaType + ")");
    }
}
