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

import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.descriptor.FieldDescriptor;
import org.gbif.dp.descriptor.ResourceDescriptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates that column values conform to the Frictionless type declared in the schema.
 * <p>
 * Uses DuckDB {@code TRY_CAST} so the entire check runs inside the engine — no data
 * is loaded into JVM memory except the small sample of failing values.
 */
public class DuckDbDataTypeValidator {

  /**
   * Maps Frictionless Data Package types to DuckDB cast targets.
   * Types not present here are skipped (e.g. "string" always passes,
   * "geopoint" / "geojson" need custom logic).
   */
  public static final Map<String, String> FRICTIONLESS_TO_DUCKDB = Map.ofEntries(
      Map.entry("integer",  "BIGINT"),
      Map.entry("number",   "DOUBLE"),
      Map.entry("boolean",  "BOOLEAN"),
      Map.entry("date",     "DATE"),
      Map.entry("datetime", "TIMESTAMP"),
      Map.entry("time",     "TIME"),
      Map.entry("year",     "INTEGER"),
      Map.entry("object",   "JSON"),
      Map.entry("array",    "JSON")
  );

  /**
   * Validates every typed field in the given resource.
   *
   * @return list of violations (empty when all values match)
   */
  public List<DataTypeViolation> validate(
      Connection connection, ResourceDescriptor resource, int sampleSize) throws SQLException {

    List<DataTypeViolation> violations = new ArrayList<>();

    for (FieldDescriptor field : resource.schema().fields()) {
      String duckDbType = FRICTIONLESS_TO_DUCKDB.get(field.type().toLowerCase());
      if (duckDbType == null) {
        // "string" or unsupported custom types — nothing to cast-check
        continue;
      }

      long count = countCastFailures(connection, resource.name(), field.name(), duckDbType);
      if (count > 0) {
        List<String> samples =
            fetchFailingSamples(connection, resource.name(), field.name(), duckDbType, sampleSize);
        violations.add(
            new DataTypeViolation(resource.name(), field.name(), field.type(), count, samples));
      }
    }

    return violations;
  }

  // ---- SQL helpers --------------------------------------------------------

  /**
   * Counts rows where the column is non-null but TRY_CAST to the target type returns null.
   */
  private static long countCastFailures(
      Connection connection, String resource, String column, String duckDbType)
      throws SQLException {

    String sql =
        "SELECT COUNT(*) FROM " + q(resource)
            + " WHERE " + q(column) + " IS NOT NULL"
            + " AND TRY_CAST(" + q(column) + " AS " + duckDbType + ") IS NULL";

    try (PreparedStatement stmt = connection.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Returns up to {@code limit} raw string values that fail the cast.
   */
  private static List<String> fetchFailingSamples(
      Connection connection, String resource, String column, String duckDbType, int limit)
      throws SQLException {

    String sql =
        "SELECT CAST(" + q(column) + " AS VARCHAR) AS bad_value FROM " + q(resource)
            + " WHERE " + q(column) + " IS NOT NULL"
            + " AND TRY_CAST(" + q(column) + " AS " + duckDbType + ") IS NULL"
            + " LIMIT " + Math.max(limit, 1);

    List<String> samples = new ArrayList<>();
    try (PreparedStatement stmt = connection.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      while (rs.next()) {
        samples.add(rs.getString(1));
      }
    }
    return List.copyOf(samples);
  }

  private static String q(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }
}
