package org.apache.solr;

import org.apache.solr.common.util.StrUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests simple object graphs, like those generated by the noggit JSON parser
 */
class CollectionTester {
  public Object valRoot;
  public Object val;
  public Object expectedRoot;
  public Object expected;
  public double delta;
  public List<Object> path;
  public String err;

  public CollectionTester(Object val, double delta) {
    this.val = val;
    this.valRoot = val;
    this.delta = delta;
    path = new ArrayList<>();
  }

  public CollectionTester(Object val) {
    this(val, JSONTestUtil.DEFAULT_DELTA);
  }

  public String getPath() {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Object seg : path) {
      if (seg == null) break;
      if (!first) sb.append('/');
      else first = false;

      if (seg instanceof Integer) {
        sb.append('[');
        sb.append(seg);
        sb.append(']');
      } else {
        sb.append(seg.toString());
      }
    }
    return sb.toString();
  }

  void setPath(Object lastSeg) {
    path.set(path.size() - 1, lastSeg);
  }

  Object popPath() {
    return path.remove(path.size() - 1);
  }

  void pushPath(Object lastSeg) {
    path.add(lastSeg);
  }

  void setErr(String msg) {
    err = msg;
  }

  public boolean match(Object expected) {
    this.expectedRoot = expected;
    this.expected = expected;
    return match();
  }

  boolean match() {
    if (expected == val) {
      return true;
    }
    if (expected == null || val == null) {
      setErr("mismatch: '" + expected + "'!='" + val + "'");
      return false;
    }
    if (expected instanceof List) {
      return matchList();
    }
    if (expected instanceof Map) {
      return matchMap();
    }

    // generic fallback
    if (!expected.equals(val)) {

      if (expected instanceof String) {
        String str = (String) expected;
        if (str.length() > 6 && str.startsWith("///") && str.endsWith("///")) {
          return handleSpecialString(str);
        }
      }

      // make an exception for some numerics
      if ((expected instanceof Integer && val instanceof Long
          || expected instanceof Long && val instanceof Integer)
          && ((Number) expected).longValue() == ((Number) val).longValue()) {
        return true;
      } else if ((expected instanceof Double || expected instanceof Float) && (
          val instanceof Double || val instanceof Float)) {
        double a = ((Number) expected).doubleValue();
        double b = ((Number) val).doubleValue();
        if (Double.compare(a, b) == 0) return true;
        if (Math.abs(a - b) < delta) return true;
      }
      setErr("mismatch: '" + expected + "'!='" + val + "'");
      return false;
    }

    // setErr("unknown expected type " + expected.getClass().getName());
    return true;
  }

  private boolean handleSpecialString(String str) {
    String code = str.substring(3, str.length() - 3);
    if ("ignore".equals(code)) {
      return true;
    } else if (code.startsWith("regex:")) {
      String regex = code.substring("regex:".length());
      if (!(val instanceof String)) {
        setErr("mismatch: '" + expected + "'!='" + val
            + "', value is not a string");
        return false;
      }
      Pattern pattern = Pattern.compile(regex);
      Matcher matcher = pattern.matcher((String) val);
      if (matcher.find()) {
        return true;
      }
      setErr(
          "mismatch: '" + expected + "'!='" + val + "', regex does not match");
      return false;
    }

    setErr("mismatch: '" + expected + "'!='" + val + "'");
    return false;
  }

  boolean matchList() {
    List expectedList = (List) expected;
    List v = asList();
    if (v == null) return false;
    int a = 0;
    int b = 0;
    pushPath(null);
    for (; ; ) {
      if (a >= expectedList.size() && b >= v.size()) {
        break;
      }

      if (a >= expectedList.size() || b >= v.size()) {
        popPath();
        setErr("List size mismatch");
        return false;
      }

      expected = expectedList.get(a);
      val = v.get(b);
      setPath(b);
      if (!match()) return false;

      a++;
      b++;
    }

    popPath();
    return true;
  }

  private static Set<String> reserved = new HashSet<>(
      Arrays.asList("_SKIP_", "_MATCH_", "_ORDERED_", "_UNORDERED_"));

  boolean matchMap() {
    Map<String,Object> expectedMap = (Map<String,Object>) expected;
    Map<String,Object> v = asMap();
    if (v == null) return false;

    boolean ordered = false;
    String skipList = (String) expectedMap.get("_SKIP_");
    String matchList = (String) expectedMap.get("_MATCH_");
    Object orderedStr = expectedMap.get("_ORDERED_");
    Object unorderedStr = expectedMap.get("_UNORDERED_");

    if (orderedStr != null) ordered = true;
    if (unorderedStr != null) ordered = false;

    Set<String> match = null;
    if (matchList != null) {
      match = new HashSet(StrUtils.splitSmart(matchList, ",", false));
    }

    Set<String> skips = null;
    if (skipList != null) {
      skips = new HashSet(StrUtils.splitSmart(skipList, ",", false));
    }

    Set<String> keys = match != null ? match : expectedMap.keySet();
    Set<String> visited = new HashSet<>();

    Iterator<Map.Entry<String,Object>> iter = ordered ?
        v.entrySet().iterator() :
        null;

    int numExpected = 0;

    pushPath(null);
    for (String expectedKey : keys) {
      if (reserved.contains(expectedKey)) continue;
      numExpected++;

      setPath(expectedKey);
      if (!v.containsKey(expectedKey)) {
        popPath();
        setErr("expected key '" + expectedKey + "'");
        return false;
      }

      expected = expectedMap.get(expectedKey);

      if (ordered) {
        Map.Entry<String,Object> entry;
        String foundKey;
        for (; ; ) {
          if (!iter.hasNext()) {
            popPath();
            setErr("expected key '" + expectedKey + "' in ordered map");
            return false;
          }
          entry = iter.next();
          foundKey = entry.getKey();
          if (skips != null && skips.contains(foundKey)) continue;
          if (match != null && !match.contains(foundKey)) continue;
          break;
        }

        if (!entry.getKey().equals(expectedKey)) {
          popPath();
          setErr(
              "expected key '" + expectedKey + "' instead of '" + entry.getKey()
                  + "' in ordered map");
          return false;
        }
        val = entry.getValue();
      } else {
        if (skips != null && skips.contains(expectedKey)) continue;
        val = v.get(expectedKey);
      }

      if (!match()) return false;
    }

    popPath();

    // now check if there were any extra keys in the value (as long as there wasn't a specific list to include)
    if (match == null) {
      int skipped = 0;
      if (skips != null) {
        for (String skipStr : skips)
          if (v.containsKey(skipStr)) skipped++;
      }
      if (numExpected != (v.size() - skipped)) {
        HashSet<String> set = new HashSet<>(v.keySet());
        set.removeAll(expectedMap.keySet());
        setErr("unexpected map keys " + set);
        return false;
      }
    }

    return true;
  }

  public boolean seek(String seekPath) {
    if (path == null) return true;
    if (seekPath.startsWith("/")) {
      seekPath = seekPath.substring(1);
    }
    if (seekPath.endsWith("/")) {
      seekPath = seekPath.substring(0, seekPath.length() - 1);
    }
    List<String> pathList = StrUtils.splitSmart(seekPath, "/", false);
    return seek(pathList);
  }

  List asList() {
    // TODO: handle native arrays
    if (val instanceof List) {
      return (List) val;
    }
    setErr("expected List");
    return null;
  }

  Map<String,Object> asMap() {
    // TODO: handle NamedList
    if (val instanceof Map) {
      return (Map<String,Object>) val;
    }
    setErr("expected Map");
    return null;
  }

  public boolean seek(List<String> seekPath) {
    if (seekPath.size() == 0) return true;
    String seg = seekPath.get(0);

    if (seg.charAt(0) == '[') {
      List listVal = asList();
      if (listVal == null) return false;

      int arrIdx = Integer.parseInt(seg.substring(1, seg.length() - 1));

      if (arrIdx >= listVal.size()) return false;

      val = listVal.get(arrIdx);
      pushPath(arrIdx);
    } else {
      Map<String,Object> mapVal = asMap();
      if (mapVal == null) return false;

      // use containsKey rather than get to handle null values
      if (!mapVal.containsKey(seg)) return false;

      val = mapVal.get(seg);
      pushPath(seg);
    }

    // recurse after removing head of the path
    return seek(seekPath.subList(1, seekPath.size()));
  }

}