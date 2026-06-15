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

import org.gbif.dp.descriptor.DialectDescriptor;

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
    Connection connection, String name, List<Path> paths, DialectDescriptor dialect)
    throws SQLException {
    String sql = "CREATE TEMP TABLE " + q(name) + " AS SELECT * FROM " + dialectRenderer.buildReadQuery(paths, dialect);
    log.debug("Running create temporary table sql: [{}]", sql);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new SQLException(String.format("Error creating temporary table sql: [%s]", sql), e);
    }
  }

}
