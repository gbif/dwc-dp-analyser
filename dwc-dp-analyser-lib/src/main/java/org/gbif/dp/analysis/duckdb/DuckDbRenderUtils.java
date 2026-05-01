package org.gbif.dp.analysis.duckdb;

public class DuckDbRenderUtils {
  static String sq(String s) {
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  public static String q(String identifier) {
    if (identifier.length() > 2
      && identifier.substring(0, 1).equalsIgnoreCase("\"")
      && identifier.substring(identifier.length() - 1).equalsIgnoreCase("\"")
    ) {
      return identifier;
    }
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }
}
