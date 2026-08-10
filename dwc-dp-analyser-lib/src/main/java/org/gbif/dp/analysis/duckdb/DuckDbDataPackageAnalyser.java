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
import java.util.function.Function;
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

  /// Register the DuckDB JDBC driver, ensuring it is available
  static {
    try {
      Class.forName("org.duckdb.DuckDBDriver");
    } catch (ClassNotFoundException e) {
      throw new ExceptionInInitializerError(
        "DuckDB JDBC driver not found on classpath: " + e.getMessage());
    }
  }

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
      typeViolations.addAll(dataTypeValidator.validate(connection, resource, options.sampleSize()));
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
    return scalarCount(connection, "SELECT COUNT(*) FROM " + q(resource.name()));
  }

  private PrimaryKeyViolation findPrimaryKeyViolation(
    ValidationOptions options, ResourceDescriptor resource, Connection connection)
    throws SQLException {
    if (resource.schema().primaryKey() == null) {
      log.debug("No primary key for {}, skipping...", resource.name());
      return null;
    }

    List<String> keys = resource.schema().primaryKey().keys();
    String keyFields = keys.stream().map(DuckDbRenderUtils::q).collect(Collectors.joining(", "));
    String violationSql = "SELECT COUNT(*), " + keyFields
                          + " FROM " + q(resource.name())
                          + " GROUP BY " + keyFields + " HAVING COUNT(*) > 1";

    long duplicateCount = scalarCount(connection, "SELECT COUNT(*) FROM (" + violationSql + ")",
                                      e -> String.format("Failed to check duplicate primary keys for resource:[%s], sql:[%s], cause:[%s]",
                                                         resource.name(), violationSql, e.getMessage()));

    String missingPredicate = getMissingPredicate(resource, connection, keys);
    String missingSql = "SELECT COUNT(*) FROM " + q(resource.name()) + " WHERE " + missingPredicate;
    long missingCount = scalarCount(connection, missingSql,
                                    e -> String.format("Failed to check missing primary keys for resource:[%s], sql:[%s], cause:[%s]",
                                                       resource.name(), missingSql, e.getMessage()));

    long totalCount = duplicateCount + missingCount;
    if (totalCount == 0) {
      return null;
    }

    SampleBudget budget = allocateSampleBudget(options.sampleSize(), duplicateCount, missingCount);
    List<Map<String, Object>> samples = new ArrayList<>();
    if (budget.duplicateLimit() > 0) {
      samples.addAll(fetchSampleRows(connection, violationSql + " LIMIT " + budget.duplicateLimit()));
    }
    if (budget.missingLimit() > 0) {
      samples.addAll(fetchSampleRows(connection,
                                     "SELECT * FROM " + q(resource.name()) + " WHERE " + missingPredicate
                                     + " LIMIT " + budget.missingLimit()));
    }

    return new PrimaryKeyViolation(resource.name(), keys, totalCount, samples);
  }

  private String getMissingPredicate(ResourceDescriptor resource, Connection connection, List<String> keys) {
    String missingPredicate = keys.stream()
      .map(key -> resolveField(resource, key))
      .map(field -> {
        try {
          return buildIsMissingPredicate(
            field.name(), resolveCompatibleMissingLiterals(connection, resource.name(), field));
        } catch (SQLException e) {
          String message = String.format("SQL Exception trying to find resolve missingPredicate type, cause:[%s]", e.getMessage());
          throw new RuntimeException(message, e);
        }
      })
      .collect(Collectors.joining(" OR "));
    return missingPredicate;
  }

  private FieldDescriptor resolveField(ResourceDescriptor resource, String fieldName) {
    return resource.schema().fields().stream()
      .filter(f -> f.name().equals(fieldName))
      .findFirst()
      .orElseThrow(() -> new IllegalStateException(
        "Primary key field [" + fieldName + "] not found in schema for resource [" + resource.name() + "]"));
  }

  private static long scalarCount(Connection connection, String sql) throws SQLException {
    return scalarCount(connection, sql,
                       e -> String.format("Failed to execute count query, sql:[%s], cause:[%s]", sql, e.getMessage()));
  }

  private static long scalarCount(
    Connection connection, String sql, Function<Exception, String> errorMessage) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new SQLException(errorMessage.apply(e), e);
    }
  }

  private record SampleBudget(int duplicateLimit, int missingLimit) {}

  private static SampleBudget allocateSampleBudget(int totalLimit, long duplicateCount, long missingCount) {
    int half = totalLimit / 2;
    int wantDup = (int) Math.min(half, duplicateCount);
    int wantMissing = (int) Math.min(half, missingCount);
    int leftover = totalLimit - wantDup - wantMissing;

    if (leftover > 0) {
      int dupRoom = (int) Math.min(leftover, duplicateCount - wantDup);
      wantDup += dupRoom;
      leftover -= dupRoom;
      int missingRoom = (int) Math.min(leftover, missingCount - wantMissing);
      wantMissing += missingRoom;
    }
    return new SampleBudget(wantDup, wantMissing);
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

    String countSql = buildViolationCountSql(resource.name(), key.fields(), parent.name(), ref.fields());
    long count = scalarCount(connection, countSql,
                             e -> String.format("Failed to validate foreign key:[%s], sql:[%s], cause:[%s]",
                                                key.reference().resource(), countSql, e.getMessage()));

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

  private String buildIsMissingPredicate(String fieldName, List<String> compatibleLiterals) {
    String col = q(fieldName);
    return compatibleLiterals.isEmpty()
      ? "(" + col + " IS NULL)"
      : "(" + col + " IS NULL OR " + col + " IN (" + String.join(", ", compatibleLiterals) + "))";
  }

  private List<String> missingValueLiterals(FieldDescriptor field) {
    return field.missingValues().stream()
      .filter(mv -> !mv.rawValue().isEmpty())
      .filter(mv -> !"null".equalsIgnoreCase(mv.rawValue()))
      .filter(mv -> castable(mv.rawValue(), field.type()))
      .map(mv -> sq(mv.rawValue()))
      .toList();
  }

  private String buildMissingValueWhere(FieldDescriptor field) {
    List<String> literals = missingValueLiterals(field);
    if (literals.isEmpty()) return "";
    return " WHERE " + q(field.name()) + " NOT IN (" + String.join(", ", literals) + ")";
  }

  private String buildIsMissingPredicate(FieldDescriptor field) {
    List<String> literals = missingValueLiterals(field);
    String col = q(field.name());
    return literals.isEmpty()
      ? "(" + col + " IS NULL)"
      : "(" + col + " IS NULL OR " + col + " IN (" + String.join(", ", literals) + "))";
  }

  private String columnType(Connection connection, String resourceName, String fieldName)
    throws SQLException {
    String sql = "SELECT data_type FROM information_schema.columns"
                 + " WHERE table_name = " + sq(resourceName) + " AND column_name = " + sq(fieldName);
    try (PreparedStatement ps = connection.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("Column [" + fieldName + "] not found for resource [" + resourceName + "]");
      }
      return rs.getString(1);
    }
  }

  private List<String> resolveCompatibleMissingLiterals(
    Connection connection, String resourceName, FieldDescriptor field) throws SQLException {
    List<String> literals = missingValueLiterals(field);
    if (literals.isEmpty()) return literals;

    String columnType = columnType(connection, resourceName, field.name());
    List<String> compatible = new ArrayList<>();
    for (String literal : literals) {
      String sql = "SELECT TRY_CAST(" + literal + " AS " + columnType + ") IS NOT NULL";
      try (PreparedStatement ps = connection.prepareStatement(sql);
           ResultSet rs = ps.executeQuery()) {
        rs.next();
        if (rs.getBoolean(1)) {
          compatible.add(literal);
        } else {
          log.debug("Missing value {} incompatible with column {} ({}), skipping",
                    literal, field.name(), columnType);
        }
      }
    }
    return compatible;
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
