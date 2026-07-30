/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.common.io.DataPackageSource;
import org.gbif.dp.common.io.ResourceLocationResolver;
import org.gbif.dp.common.io.ResourceResult;
import org.gbif.dp.descriptor.DialectDescriptor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.q;

public class DuckDbResourceLoader {

  private static final Logger log = LoggerFactory.getLogger(DuckDbResourceLoader.class);
  private final DuckDbDialectRenderer dialectRenderer;

  public DuckDbResourceLoader(DuckDbDialectRenderer dialectRenderer) {
    this.dialectRenderer = dialectRenderer;
  }

  public void createResourceTempTable(
    Connection connection, DataPackageSource source, String name, List<String> paths,
    DialectDescriptor dialect) throws SQLException, IOException {

    checkResourcesExist(source, name, paths);

    List<String> resolvedLocations = paths.stream()
      .map(path -> ResourceLocationResolver.resolve(source.rawLocation(), path))
      .map(DuckDbResourceLoader::toDuckDbLocation)
      .toList();

    log.debug("Creating temporary table [{}] for resource '{}'", name, name);
    try {
      executeCreateTempTable(connection, name, resolvedLocations, dialect, false);
    } catch (SQLException e) {
      if (!isConversionError(e)) {
        throw e;
      }
      // The sampled auto-detection guessed a type (e.g. BOOLEAN) that a value later in the file
      // doesn't fit. This is expected, unremarkable behaviour on messy real-world data — not a
      // warning sign — so it's logged at debug only. Falling back to all_varchar=true always
      // succeeds regardless of content; actual type conformance is still fully checked afterward
      // by DuckDbDataTypeValidator's TRY_CAST-based validation, so nothing is lost by loading as
      // text here.
      log.debug("Conversion Error during creation of temporary table [{}], using all_varchar", name);
      executeCreateTempTable(connection, name, resolvedLocations, dialect, true);
    }
  }

  private void executeCreateTempTable(
    Connection connection, String name, List<String> resolvedLocations,
    DialectDescriptor dialect, boolean allVarchar) throws SQLException {
    String sql = "CREATE TEMP TABLE " + q(name) + " AS SELECT * FROM "
                 + dialectRenderer.buildReadQuery(resolvedLocations, dialect, allVarchar);
    log.debug("Running create temporary table sql: [{}]", sql);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new SQLException(String.format("Error creating temporary table sql: [%s]", sql), e);
    }
  }

  /**
   * DuckDB's JDBC driver has no typed exception hierarchy — every failure surfaces as a plain
   * {@link SQLException} whose message is prefixed with DuckDB's own internal error category
   * (e.g. {@code "Conversion Error: ..."}, {@code "IO Error: ..."}). A Conversion Error can only
   * occur when a value fails to cast into a non-VARCHAR type that auto-detection inferred; text
   * itself can never fail to load. That message prefix is the stable signal to key on — not the
   * nested {@code "CSV Error"} detail, which is specific to delimited-file reads (the same prefix
   * appears regardless of delimiter, e.g. CSV vs TSV).
   */
  private static boolean isConversionError(SQLException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().startsWith("Conversion Error")) {
        return true;
      }
    }
    return false;
  }

  /**
   * DuckDB's {@code read_csv_auto}/{@code read_parquet} treat a plain string argument as a
   * local filesystem glob — they do not strip a {@code file://} scheme, so a resolved
   * {@code file:} URI must be converted back to a bare path before DuckDB sees it. Other
   * schemes ({@code hdfs://}, {@code s3://}, ...) are passed through unchanged: those are
   * exactly the form DuckDB's own extensions (httpfs, etc.) expect.
   */
  private static String toDuckDbLocation(String resolvedLocation) {
    URI uri = URI.create(resolvedLocation);
    if ("file".equals(uri.getScheme())) {
      return Path.of(uri).toString();
    }
    return resolvedLocation;
  }

  private void checkResourcesExist(DataPackageSource source, String resourceName, List<String> paths)
    throws IOException {
    for (String path : paths) {
      ResourceResult result = source.openResource(path);
      switch (result.kind()) {
        case FOUND -> {
          try {
            ((ResourceResult.Found) result).close();
          } catch (IOException e) {
            log.debug("Non-fatal: could not close existence-check stream for {}: {}",
                      path, e.getMessage());
          }
        }
        case MISSING -> throw new IOException(
          "Resource '" + resourceName + "' declares path '" + path + "' which does not exist.");
        case FAILED -> {
          ResourceResult.Failed failed = (ResourceResult.Failed) result;
          throw new IOException(
            "Resource '" + resourceName + "' declares path '" + path
            + "' which could not be opened: " + failed.cause().getMessage(), failed.cause());
        }
      }
    }
  }
}
