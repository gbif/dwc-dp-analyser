package org.gbif.dp.duckdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class DuckDbResourceLoader {

  private static final Logger LOG = LoggerFactory.getLogger(DuckDbResourceLoader.class);

  public void createResourceTempTable(Connection connection, String resourceName, List<Path> resourcePath)
      throws SQLException {
    String sql =
        "CREATE TEMP TABLE "
            + quotedIdentifier(resourceName)
            + " AS SELECT * FROM "
            + tableFunction(resourcePath);
    LOG.debug("Running create temporary table sql: [{}]", sql);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      String msg = String.format("Error creating temporary table sql: [%s]", sql);
      throw new SQLException(e);
    }
  }

  private enum FileType {
    CSV(".csv"), TSV(".tsv"), PARQUET(".parquet");

    private final String extension;

    FileType(String extension) {
      this.extension = extension;
    }
  }

  private static String tableFunction(List<Path> paths) {
    String joinedPaths = paths.stream()
            .map(Path::toAbsolutePath)
            .map(Object::toString)
            .map(str -> str.replace("'", "''"))
            .map(str -> "'" + str + "'")
            .collect(Collectors.joining(", "));

    String lower = paths.get(0).toAbsolutePath().toString().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".parquet")) {
      return "read_parquet([" + joinedPaths + "])";
    }
    if (lower.endsWith(".tsv")) {
      return "read_csv_auto([" + joinedPaths + "], delim='\\t', header=true, sample_size=-1)";
    }
    return "read_csv_auto([" + joinedPaths + "], header=true, sample_size=-1)";
  }

  private static String quotedIdentifier(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}

