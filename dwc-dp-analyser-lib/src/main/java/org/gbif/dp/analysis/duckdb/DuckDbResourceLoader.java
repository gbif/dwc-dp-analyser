package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.descriptor.DialectDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.q;

public class DuckDbResourceLoader {

  private static final Logger log = LoggerFactory.getLogger(DuckDbResourceLoader.class);
  private final DuckDbDialectRenderer dialectRenderer;

  public DuckDbResourceLoader(DuckDbDialectRenderer dialectRenderer) {
    this.dialectRenderer = dialectRenderer;
  }

  public void createResourceTempTable(
    Connection connection, String name, List<Path> paths, DialectDescriptor dialect)
    throws SQLException {
    String sql = "CREATE TEMP TABLE " + q(name) + " AS SELECT * FROM " + tableFunction(paths, dialect);
    log.debug("Running create temporary table sql: [{}]", sql);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new SQLException(String.format("Error creating temporary table sql: [%s]", sql), e);
    }
  }

  private String tableFunction(List<Path> paths, DialectDescriptor dialect) {
    String joinedPaths = paths.stream()
      .map(Path::toAbsolutePath)
      .map(Object::toString)
      .map(str -> str.replace("'", "''"))
      .map(str -> "'" + str + "'")
      .collect(Collectors.joining(", "));

    String firstName = paths.get(0).toAbsolutePath().toString();

    if (firstName.toLowerCase(Locale.ROOT).endsWith(".parquet")) {
      return "read_parquet([" + joinedPaths + "])";
    }

    return "read_csv_auto([" + joinedPaths + "], " + dialectRenderer.toReadCsvArgs(dialect, firstName) + ")";
  }
}
