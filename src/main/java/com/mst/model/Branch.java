package com.mst.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A dynamic branch decision point, e.g. an &lt;if test="..."&gt; or one &lt;when&gt; of a &lt;choose&gt;.
 */
public class Branch {
    /** ORIGINAL tag: if | when | otherwise | foreach */
    public String kind;
    /** OGNL test expression, null for otherwise/foreach */
    public String testExpr;
    /** The variable names this branch's expression references (full property paths). */
    public List<String> referencedVars = new ArrayList<>();
    /** Branch id unique within its statement, used for coverage tracking. */
    public String branchId;
    /** true if this branch can be absent (false-branch of an if). */
    public boolean canBeFalse = true;
    /** choose-group this branch belongs to (null outside &lt;choose&gt;). */
    public String groupId;
    /** position inside its choose-group (0, 1, 2, ...) */
    public int groupIndex;
    /** for &lt;foreach&gt;: the collection expression */
    public String collectionName;
    /** for &lt;foreach&gt;: the item variable name */
    public String itemName;

    public Branch() {}

    public Branch(String kind, String testExpr, String branchId) {
        this.kind = kind;
        this.testExpr = testExpr;
        this.branchId = branchId;
    }
}
