package com.mst.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A concrete parameter set that renders one SQL variant.
 */
public class TestCase {
    public String name;
    /** Parameter values keyed by the top-level variable name. */
    public Map<String, Object> params = new LinkedHashMap<>();
    /** Branch ids this case satisfies (for coverage reporting). */
    public List<String> coveredBranches = new ArrayList<>();
    /** Rendered SQL (filled after rendering). */
    public String renderedSql;
    /** true when the case could not be solved automatically and needs manual values. */
    public boolean manual;
    /** Why the case could not be solved automatically. */
    public String note;
    /** null = not executed yet, true = executed without error, false = failed. */
    public Boolean success;
    public String error;
    public long elapsedMs;
    /** Result columns of the last execution. */
    public List<String> columns = new ArrayList<>();
    /** Result rows of the last execution. */
    public List<List<Object>> rows = new ArrayList<>();
    /** Update count of the last execution. */
    public int updateCount;
}
