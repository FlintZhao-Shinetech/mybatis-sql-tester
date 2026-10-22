package com.mst.scan;

import com.mst.model.Branch;
import com.mst.model.ParamRef;
import com.mst.model.Statement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans a source tree for MyBatis mapper XML files and extracts statements,
 * parameter references, and dynamic branch decision points.
 *
 * <p>Fragments pulled in with {@code <include refid="..."/>} are expanded while
 * walking, so parameters and branches that live inside shared SQL fragments are
 * discovered as well.</p>
 */
public class XmlScanner {

    private static final Pattern PARAM = Pattern.compile("[#$]\\{\\s*([^}]+?)\\s*\\}");
    private static final Pattern TEST_VAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    public List<Statement> statements = new ArrayList<>();
    public List<String> xmlFiles = new ArrayList<>();

    /** namespace -> (refid -> &lt;sql&gt; element) */
    private final Map<String, Map<String, Element>> fragments = new LinkedHashMap<>();
    /** parsed documents, kept so fragments can be looked up across files */
    private final Map<String, String> namespaceOfFile = new LinkedHashMap<>();

    public void scanProject(String rootDir) throws Exception {
        Path root = new File(rootDir).toPath();
        List<Path> xmls = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".xml")).forEach(xmls::add);
        }
        // first pass: register all <sql> fragments so <include> can be resolved
        for (Path p : xmls) {
            try {
                collectFragments(p.toFile());
            } catch (Exception e) {
                // not a mapper xml or malformed; ignore
            }
        }
        for (Path p : xmls) {
            try {
                scanXml(p.toFile());
            } catch (Exception e) {
                // not a mapper xml or malformed; ignore
            }
        }
    }

    private Document parse(File file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setNamespaceAware(false);
        DocumentBuilder db = f.newDocumentBuilder();
        db.setEntityResolver((pub, sys) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        return db.parse(file);
    }

    private void collectFragments(File file) throws Exception {
        Document doc;
        try {
            doc = parse(file);
        } catch (Exception e) {
            return;
        }
        Element rootEl = doc.getDocumentElement();
        if (rootEl == null || !"mapper".equals(rootEl.getNodeName())) return;
        String namespace = rootEl.getAttribute("namespace");
        Map<String, Element> map = fragments.computeIfAbsent(namespace, k -> new LinkedHashMap<>());
        NodeList children = rootEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("sql".equals(el.getNodeName())) {
                map.put(el.getAttribute("id"), el);
            }
        }
    }

    private void scanXml(File file) throws Exception {
        Document doc;
        try {
            doc = parse(file);
        } catch (Exception e) {
            return;
        }
        Element rootEl = doc.getDocumentElement();
        if (rootEl == null || !"mapper".equals(rootEl.getNodeName())) {
            return;
        }
        String namespace = rootEl.getAttribute("namespace");
        xmlFiles.add(file.getAbsolutePath());
        namespaceOfFile.put(file.getAbsolutePath(), namespace);

        NodeList children = rootEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("select".equals(el.getNodeName()) || "insert".equals(el.getNodeName())
                    || "update".equals(el.getNodeName()) || "delete".equals(el.getNodeName())) {
                Statement st = new Statement();
                st.namespace = namespace;
                st.id = el.getAttribute("id");
                st.type = el.getNodeName();
                st.xmlFile = file.getAbsolutePath();
                collectParamsAndBranches(el, st);
                statements.add(st);
            }
        }
    }

    private void collectParamsAndBranches(Element stmtEl, Statement st) {
        AtomicInteger branchSeq = new AtomicInteger(0);
        StringBuilder sb = new StringBuilder();
        Deque<String> foreachItems = new ArrayDeque<>();
        Map<String, String> itemCollections = new LinkedHashMap<>();
        walk(stmtEl, st, branchSeq, sb, foreachItems, itemCollections, namespaceOfFile.get(st.xmlFile), new HashSet<>());
        extractParams(sb.toString(), st, itemCollections);
        // Also add variables referenced in branch tests that aren't bound via #{}
        // (e.g. @Param Map and accessing params.key)
        Set<String> seen = new HashSet<>();
        for (ParamRef p : st.params) seen.add(p.name);
        for (Branch b : st.branches) {
            for (String v : b.referencedVars) {
                String root = v.contains(".") ? v.substring(0, v.indexOf('.')) : v;
                if (seen.add(root)) {
                    ParamRef pr = new ParamRef(root);
                    pr.typeSource = "IMPLICIT";
                    st.params.add(pr);
                }
            }
        }
    }

    private void walk(Node node, Statement st, AtomicInteger branchSeq, StringBuilder sb,
                      Deque<String> foreachItems, Map<String, String> itemCollections,
                      String namespace, Set<String> includeStack) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
                continue;
            }
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = n.getNodeName();

            if ("if".equals(tag)) {
                Element el = (Element) n;
                String test = el.getAttribute("test");
                String bid = st.fullId() + "#if" + branchSeq.incrementAndGet();
                Branch b = new Branch("if", test, bid);
                b.referencedVars = extractVars(test);
                st.branches.add(b);
                walk(n, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
            } else if ("choose".equals(tag)) {
                String groupId = st.fullId() + "#choose" + branchSeq.incrementAndGet();
                NodeList ws = n.getChildNodes();
                int whenIdx = 0;
                for (int j = 0; j < ws.getLength(); j++) {
                    Node wn = ws.item(j);
                    if (wn.getNodeType() != Node.ELEMENT_NODE) continue;
                    String wt = wn.getNodeName();
                    if ("when".equals(wt)) {
                        String test = ((Element) wn).getAttribute("test");
                        String bid = groupId + "#when" + (whenIdx + 1);
                        Branch b = new Branch("when", test, bid);
                        b.referencedVars = extractVars(test);
                        b.groupId = groupId;
                        b.groupIndex = whenIdx++;
                        st.branches.add(b);
                        walk(wn, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
                    } else if ("otherwise".equals(wt)) {
                        String bid = groupId + "#otherwise";
                        Branch b = new Branch("otherwise", null, bid);
                        b.canBeFalse = false;
                        b.groupId = groupId;
                        b.groupIndex = whenIdx++;
                        st.branches.add(b);
                        walk(wn, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
                    }
                }
            } else if ("foreach".equals(tag)) {
                Element el = (Element) n;
                String coll = el.getAttribute("collection");
                String item = el.getAttribute("item");
                String bid = st.fullId() + "#foreach" + branchSeq.incrementAndGet();
                Branch b = new Branch("foreach", coll, bid);
                b.referencedVars = new ArrayList<>();
                if (!coll.isEmpty()) b.referencedVars.add(coll);
                b.canBeFalse = true; // empty collection vs non-empty
                b.collectionName = coll;
                b.itemName = item;
                st.branches.add(b);
                // the item becomes a bound name inside the body
                if (item != null && !item.isEmpty()) {
                    foreachItems.push(item);
                    if (!coll.isEmpty() && !itemCollections.containsKey(item)) {
                        itemCollections.put(item, coll);
                    }
                }
                walk(n, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
                if (item != null && !item.isEmpty()) foreachItems.pop();
            } else if ("include".equals(tag)) {
                String refid = ((Element) n).getAttribute("refid");
                Element frag = resolveFragment(refid, namespace);
                if (frag != null && includeStack.add(refid)) {
                    walk(frag, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
                    includeStack.remove(refid);
                }
            } else {
                // where/set/trim/bind/selectKey etc: transparent, recurse
                walk(n, st, branchSeq, sb, foreachItems, itemCollections, namespace, includeStack);
            }
        }
    }

    private Element resolveFragment(String refid, String namespace) {
        if (refid == null || refid.isEmpty()) return null;
        String ns = namespace;
        String id = refid;
        if (refid.contains(".")) {
            int dot = refid.lastIndexOf('.');
            ns = refid.substring(0, dot);
            id = refid.substring(dot + 1);
        }
        Map<String, Element> map = fragments.get(ns);
        if (map == null) map = fragments.get(namespace);
        if (map == null) return null;
        return map.get(id);
    }

    private void extractParams(String text, Statement st, Map<String, String> itemCollections) {
        Set<String> seen = new HashSet<>();
        Matcher m = PARAM.matcher(text);
        while (m.find()) {
            String inner = m.group(1).trim();
            boolean raw = m.group(0).startsWith("$");
            String name = inner;
            String jdbcType = null;
            // strip jdbcType=... and other attributes
            int comma = inner.indexOf(',');
            if (comma >= 0) {
                name = inner.substring(0, comma).trim();
                String rest = inner.substring(comma + 1);
                Matcher jm = Pattern.compile("jdbcType\\s*=\\s*([A-Za-z]+)").matcher(rest);
                if (jm.find()) jdbcType = jm.group(1);
            }
            if (name.isEmpty()) continue;
            if (!seen.add(name)) continue;
            ParamRef pr = new ParamRef(name);
            pr.declaredJdbcType = jdbcType;
            pr.raw = raw;
            // is this a foreach item? ({@code #{item}} or {@code #{item[0]}})
            String itemOf = null;
            String itemName = name;
            int bracket = itemName.indexOf('[');
            if (bracket > 0) itemName = itemName.substring(0, bracket);
            if (itemCollections.containsKey(itemName)) {
                itemOf = itemCollections.get(itemName);
            }
            pr.itemOf = itemOf;
            st.params.add(pr);
        }
    }

    /**
     * Extract variable names referenced in an OGNL test expression.
     * Full property paths are returned (e.g. {@code params.status}) so that
     * Map/POJO parameters can be built as nested structures.
     */
    public static List<String> extractVars(String test) {
        List<String> vars = new ArrayList<>();
        if (test == null || test.isEmpty()) return vars;
        // remove string literals, their content is never a variable
        String stripped = test.replaceAll("'[^']*'", " ").replaceAll("\"[^\"]*\"", " ");
        Matcher m = TEST_VAR.matcher(stripped);
        Set<String> kw = new HashSet<>(Arrays.asList(
                "null", "true", "false", "and", "or", "not", "in", "eq", "neq", "gt", "lt", "gte", "lte",
                "length", "size", "isEmpty", "contains", "startsWith", "endsWith", "matches", "trim",
                "String", "Integer", "Long", "Double", "Boolean", "BigDecimal", "Date", "Object"));
        while (m.find()) {
            String v = m.group();
            String head = v.contains(".") ? v.substring(0, v.indexOf('.')) : v;
            if (kw.contains(head)) continue;
            if (Character.isUpperCase(head.charAt(0)) && !v.contains(".")) continue;
            if (!vars.contains(v)) vars.add(v);
        }
        return vars;
    }
}
