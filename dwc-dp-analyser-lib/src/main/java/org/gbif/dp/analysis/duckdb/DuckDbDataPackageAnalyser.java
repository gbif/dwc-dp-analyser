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

import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.ColumnStatistics;
import org.gbif.dp.analysis.api.DataAnalyser;
import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.analysis.api.ForeignKeyViolation;
import org.gbif.dp.analysis.api.PrimaryKeyViolation;
import org.gbif.dp.analysis.api.ResourceAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.common.io.DataPackageSource;
import org.gbif.dp.common.io.DataPackageSources;
import org.gbif.dp.descriptor.*;
import org.gbif.dp.duckdb.DuckDbConfig;
import org.gbif.dp.duckdb.DuckDbConfigBuilder;

import java.io.IOException;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.q;
import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.sq;

/**
 * DuckDB-backed implementation of {@link DataAnalyser}.
 *
 * <p>Responsible only for data-level checks: foreign keys, primary keys, data type
 * constraints, and column statistics. Descriptor and EML validation are entirely out
 * of scope — see {@link org.gbif.dp.analysis.DefaultDataPackageAnalysisOrchestrator}.
 *
 * <p>DuckDB configuration (JDBC URL, memory limits, thread count, temp directory) is
 * supplied at construction via {@link DuckDbConfig} — it is not part of the
 * {@link DataAnalyser} interface contract.
 */
public class DuckDbDataPackageAnalyser implements DataAnalyser {

  private static final Logger log = LoggerFactory.getLogger(DuckDbDataPackageAnalyser.class);

  private final DataPackageParser parser;
  private final DuckDbResourceLoader resourceLoader;
  private final DuckDbDataTypeValidator dataTypeValidator;
  private final DuckDbConfig config;

  /**
   * Convenience constructor using default in-memory DuckDB config.
   */
  public DuckDbDataPackageAnalyser(DataPackageParser parser, DuckDbResourceLoader resourceLoader) {
    this(parser, resourceLoader, new DuckDbDataTypeValidator(), DuckDbConfigBuilder.defaults().build());
  }

  public DuckDbDataPackageAnalyser(
    DataPackageParser parser,
    DuckDbResourceLoader resourceLoader,
    DuckDbConfig config) {
    this(parser, resourceLoader, new DuckDbDataTypeValidator(), config);
  }

  public DuckDbDataPackageAnalyser(
    DataPackageParser parser,
    DuckDbResourceLoader resourceLoader,
    DuckDbDataTypeValidator dataTypeValidator,
    DuckDbConfig config) {

    this.parser = parser;
    this.resourceLoader = resourceLoader;
    this.dataTypeValidator = dataTypeValidator;
    this.config = config;
  }

  @Override
  public List<ResourceAnalysisResult> analyse(
    String descriptorLocation, ValidationOptions options, List<AnalysisFeature> features)
    throws IOException, SQLException {

    try (DataPackageSource source = DataPackageSources.open(descriptorLocation);
         Connection connection = DriverManager.getConnection(config.jdbcUrl())) {

      DataPackageDescriptor descriptor = parser.parse(source.readDescriptor());
      applyConfig(connection);

      for (ResourceDescriptor resource : descriptor.resources()) {
        log.debug("Creating temp table for {} -> {}", resource.name(), resource.paths());
        resourceLoader.createResourceTempTable(
          connection, source, resource.name(), resource.paths(), resource.dialect());
      }

      log.debug("Running data analysis for: [{}]", descriptor.name());
      return analyseEachResource(options, features, descriptor, connection);
    }
  }

  private void applyConfig(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      if (!config.dbMemory().isBlank()) {
        st.execute("SET memory_limit = " + sq(config.dbMemory()));
      }
      if (config.dbThreads() > 0) {
        st.execute("SET threads TO " + config.dbThreads());
      }
      if (!config.dbTempDir().isBlank()) {
        st.execute("SET temp_directory = " + sq(config.dbTempDir()));
      }
      if (log.isDebugEnabled()) {
        ResultSet rs = st.executeQuery("SELECT * FROM duckdb_settings()");
        Map<String, String> settings = new HashMap<>();
        while (rs.next()) settings.put(rs.getString("name"), rs.getString("value"));
        log.debug("DuckDB settings: [{}]", settings.entrySet().stream()
          .map(e -> "'" + e.getKey() + "'='" + e.getValue() + "'")
          .collect(Collectors.joining(", ")));
      }
    }
  }

  private List<ResourceAnalysisResult> analyseEachResource(
    ValidationOptions options, List<AnalysisFeature> features,
    DataPackageDescriptor descriptor, Connection connection) throws SQLException {
    List<ResourceAnalysisResult> results = new ArrayList<>();
    for (ResourceDescriptor resource : descriptor.resources()) {
      results.add(analyseResource(options, features, resource, descriptor, connection));
    }
    return results;
  }

  private ResourceAnalysisResult analyseResource(
    ValidationOptions options, List<AnalysisFeature> features,
    ResourceDescriptor resource, DataPackageDescriptor descriptor,
    Connection connection) throws SQLException {

    List<ForeignKeyViolation> fkViolations = new ArrayList<>();
    List<DataTypeViolation> typeViolations = new ArrayList<>();
    List<ColumnStatistics> columnStats = new ArrayList<>();
    PrimaryKeyViolation pkViolation = null;
    long rowCount = countRows(connection, resource);

    if (features.contains(AnalysisFeature.FOREIGN_KEY_CONSTRAINT)) {
      fkViolations.addAll(findForeignKeyViolations(options, resource, connection, descriptor));
    }
    if (features.contains(AnalysisFeature.PRIMARY_KEY_UNIQUE)) {
      pkViolation = findPrimaryKeyViolation(options, resource, connection);
    }
    if (features.contains(AnalysisFeature.DATA_TYPE_CONSTRAINT)) {
      for (ResourceDescriptor r : descriptor.resources()) {
        typeViolations.addAll(dataTypeValidator.validate(connection, r, options.sampleSize()));
      }
    }
    if (features.contains(AnalysisFeature.COUNT)
      || features.contains(AnalysisFeature.COUNT_DISTINCT)) {
      for (FieldDescriptor field : resource.schema().fields()) {
        columnStats.add(analyseColumn(connection, field, resource));
      }
    }

    return new ResourceAnalysisResult(
      resource.name(), fkViolations, pkViolation, typeViolations, columnStats, rowCount);
  }

  private long countRows(Connection connection, ResourceDescriptor resource) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
      "SELECT COUNT(*) FROM " + q(resource.name()));
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private PrimaryKeyViolation findPrimaryKeyViolation(
    ValidationOptions options, ResourceDescriptor resource, Connection connection)
    throws SQLException {
    if (resource.schema().primaryKey() == null) {
      return null;
    }

    String keyFields = resource.schema().primaryKey().keys().stream()
      .map(DuckDbRenderUtils::q).collect(Collectors.joining(", "));
    String violationSql = "SELECT COUNT(*), " + keyFields
      + " FROM " + q(resource.name())
      + " GROUP BY " + keyFields + " HAVING COUNT(*) > 1";
    String countSql = "SELECT COUNT(*) FROM (" + violationSql + ")";

    long count;
    try (PreparedStatement ps = connection.prepareStatement(countSql);
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      count = rs.getLong(1);
    }

    if (count == 0) {
      return null;
    }
    List<Map<String, Object>> samples =
      fetchSampleRows(connection, violationSql + " LIMIT " + options.sampleSize());

    return new PrimaryKeyViolation(resource.name(), resource.schema().primaryKey().keys(), count, samples);
  }

  private List<ForeignKeyViolation> findForeignKeyViolations(
    ValidationOptions options, ResourceDescriptor resource,
    Connection connection, DataPackageDescriptor descriptor) throws SQLException {
    List<ForeignKeyViolation> violations = new ArrayList<>();

    for (ForeignKeyDescriptor key : resource.schema().foreignKeys()) {
      log.debug("Checking FK {}[{}] -> {}[{}]", resource.name(),
        String.join(",", key.fields()),
        key.reference().resource(), String.join(",", key.reference().fields()));
      ForeignKeyViolation v =
        validateForeignKey(connection, descriptor, resource, key, options.sampleSize());
      if (v.violationCount() > 0) violations.add(v);
    }
    return violations;
  }

  private ForeignKeyViolation validateForeignKey(
    Connection connection, DataPackageDescriptor descriptor,
    ResourceDescriptor resource, ForeignKeyDescriptor key, int sampleSize)
    throws SQLException {
    ReferenceDescriptor ref = key.reference();
    String parentName = ref.resource().isBlank() ? resource.name() : ref.resource();
    ResourceDescriptor parent = descriptor.resources().stream()
      .filter(r -> r.name().equals(parentName)).findFirst().orElse(null);
    if (parent == null) {
      return new ForeignKeyViolation(
        resource.name(), key.fields(), parentName, ref.fields(), 0L, List.of());
    }

    String countSql =
      buildViolationCountSql(resource.name(), key.fields(), parent.name(), ref.fields());
    long count;
    try (PreparedStatement ps = connection.prepareStatement(countSql);
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      count = rs.getLong(1);
    } catch (Exception e) {
      String message = String.format("Failed to validate foreign key:[%s], sql:[%s], cause:[%s]",
        key.reference().resource(),
        countSql,
        e.getMessage()
        );
      throw new SQLException(message, e);
    }

    List<Map<String, Object>> samples = count == 0 ? List.of()
      : fetchSampleRows(connection,
      buildSampleSql(resource.name(), key.fields(), parent.name(), ref.fields(), sampleSize));
    return new ForeignKeyViolation(
      resource.name(), key.fields(), parent.name(), ref.fields(), count, samples);
  }

  private ColumnStatistics analyseColumn(
    Connection connection, FieldDescriptor field, ResourceDescriptor resource)
    throws SQLException {
    String where = buildMissingValueWhere(field);
    String sql = "SELECT COUNT(" + q(field.name()) + "), COUNT(DISTINCT " + q(field.name()) + ")"
      + " FROM " + q(resource.name()) + where;
    try (PreparedStatement ps = connection.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return new ColumnStatistics(field.name(), rs.getLong(1), rs.getLong(2));
    } catch (SQLException e) {
      String message = String.format("Failed to analyse column statistics for field [%s], sql was [%s], nested cause: [%s]",
        field.name(), sql, e.getMessage());
      throw new SQLException(message, e);
    }
  }

  private String buildMissingValueWhere(FieldDescriptor field) {
    List<String> castable = field.missingValues().stream()
      .filter(mv -> !mv.rawValue().isEmpty())
      .filter(mv -> !"null".equalsIgnoreCase(mv.rawValue()))
      .filter(mv -> castable(mv.rawValue(), field.type()))
      .map(mv -> sq(mv.rawValue()))
      .toList();
    if (castable.isEmpty()) return "";
    return " WHERE " + q(field.name()) + " NOT IN (" + String.join(", ", castable) + ")";
  }

  private boolean castable(String value, String type) {
    if (value == null || value.isEmpty()) return true;
    return switch (type) {
      case "integer", "year" -> {
        try {
          Long.parseLong(value);
          yield true;
        } catch (NumberFormatException e) {
          yield false;
        }
      }
      case "number" -> {
        try {
          Double.parseDouble(value);
          yield true;
        } catch (NumberFormatException e) {
          yield false;
        }
      }
      case "boolean" -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
      case "date" -> {
        try {
          LocalDate.parse(value);
          yield true;
        } catch (DateTimeParseException e) {
          yield false;
        }
      }
      case "datetime" -> {
        try {
          LocalDateTime.parse(value);
          yield true;
        } catch (DateTimeParseException e) {
          yield false;
        }
      }
      case "time" -> {
        try {
          LocalTime.parse(value);
          yield true;
        } catch (DateTimeParseException e) {
          yield false;
        }
      }
      case "object", "array" -> {
        String v = value.trim();
        yield (type.equals("object") && v.startsWith("{") && v.endsWith("}")) || (type.equals("array") && v.startsWith("[") && v.endsWith("]"));
      }
      default -> true;
    };
  }

  private static List<Map<String, Object>> fetchSampleRows(Connection connection, String sql)
    throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (PreparedStatement ps = connection.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      ResultSetMetaData meta = rs.getMetaData();
      while (rs.next()) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnName(i), rs.getObject(i));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private static String buildViolationCountSql(
    String child, List<String> cf, String parent, List<String> pf) {
    return
      "SELECT COUNT(*)" +
        " FROM " + q(child) + " c" +
        " WHERE " + nullPredicate(cf)
      + " AND NOT EXISTS (" +
        "    SELECT 1" +
        "    FROM " + q(parent) + " p" +
        "    WHERE " + joinPredicate(cf, pf) + ")";
  }

  private static String buildSampleSql(
    String child, List<String> cf, String parent, List<String> pf, int limit) {
    return "SELECT " + selectCols(cf) + " FROM " + q(child) + " c WHERE " + nullPredicate(cf)
      + " AND NOT EXISTS (SELECT 1 FROM " + q(parent) + " p WHERE " + joinPredicate(cf, pf)
      + ") LIMIT " + Math.max(limit, 1);
  }

  private static String nullPredicate(List<String> fields) {
    StringJoiner j = new StringJoiner(" AND ");
    fields.forEach(f -> j.add("c." + q(f) + " IS NOT NULL"));
    return j.toString();
  }

  private static String joinPredicate(List<String> cf, List<String> pf) {
    StringJoiner j = new StringJoiner(" AND ");
    for (int i = 0; i < cf.size(); i++) {
      j.add("c." + q(cf.get(i)) + " = p." + q(pf.get(i)));
    }
    return j.toString();
  }

  private static String selectCols(List<String> fields) {
    StringJoiner j = new StringJoiner(", ");
    fields.forEach(f -> j.add("c." + q(f) + " AS " + q(f)));
    return j.toString();
  }
}
