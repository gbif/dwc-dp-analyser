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

    String sql = "CREATE TEMP TABLE " + q(name) + " AS SELECT * FROM "
                 + dialectRenderer.buildReadQuery(resolvedLocations, dialect);
    log.debug("Running create temporary table sql: [{}]", sql);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new SQLException(String.format("Error creating temporary table sql: [%s]", sql), e);
    }
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
