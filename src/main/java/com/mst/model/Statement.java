package com.mst.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One dynamic-SQL statement (select/insert/update/delete) found in a mapper XML.
 */
public class Statement {
    public String namespace;
    public String id;
    public String type; // select|insert|update|delete
    public String xmlFile;

    /** Unique key: namespace + "." + id */
    public String fullId() {
        return namespace + "." + id;
    }

    /** Parameters referenced (#{} / ${}) with best-effort type info. */
    public List<ParamRef> params = new ArrayList<>();

    /** Dynamic branch decision points found in this statement. */
    public List<Branch> branches = new ArrayList<>();

    /** Test cases generated to cover all branches. */
    public List<TestCase> testCases = new ArrayList<>();

    /** Resolved type information per top level parameter root. */
    public Map<String, Object> rootTypes = new LinkedHashMap<>();

    /** Coverage report: unit id -> description of the branch path. */
    public Map<String, String> coverageUnits = new LinkedHashMap<>();

    /** Units that no generated test case could satisfy (need manual input). */
    public List<Map<String, Object>> unresolvedBranches = new ArrayList<>();

    /** Non-null if the statement could not be fully analysed. */
    public String warning;
}
