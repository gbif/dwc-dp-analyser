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
package org.gbif.dp.analysis;

import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.ColumnStatistics;
import org.gbif.dp.analysis.api.DataAnalyser;
import org.gbif.dp.analysis.api.DataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.ResourceAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.analysis.duckdb.DuckDbDataPackageAnalyser;
import org.gbif.dp.analysis.duckdb.DuckDbDialectRenderer;
import org.gbif.dp.analysis.duckdb.DuckDbRenderUtils;
import org.gbif.dp.analysis.duckdb.DuckDbResourceLoader;
import org.gbif.dp.descriptor.JacksonDataPackageParser;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.frictionless.DescriptorValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DuckDbDataPackageAnalyser}.
 *
 * Tests asserting full result shape (isValid, violations) use
 * {@link DefaultDataPackageAnalysisOrchestrator} with no-op descriptor/EML validators
 * so structural validation does not interfere with data analysis assertions.
 *
 * Tests that only need row/column counts use {@link DataAnalyser} directly.
 */
class DuckDbDataPackageAnalyserTest {

  private static final DescriptorValidator NO_OP_DESCRIPTOR_VALIDATOR =
    path -> DescriptorValidationResult.ok();

  private static final org.gbif.dp.validator.eml.EmlValidator NO_OP_EML_VALIDATOR =
    new org.gbif.dp.validator.eml.EmlValidator() {
      @Override public EmlValidationResult validate(Path p) { return EmlValidationResult.absent(); }
    };

  @TempDir Path tempDir;
  DataAnalyser analyser;
  DataPackageAnalysisOrchestrator orchestrator;

  @BeforeEach
  void setup() {
    analyser = new DuckDbDataPackageAnalyser(
      new JacksonDataPackageParser(),
      new DuckDbResourceLoader(new DuckDbDialectRenderer()));

    orchestrator = new DefaultDataPackageAnalysisOrchestrator(
      analyser, NO_OP_DESCRIPTOR_VALIDATOR, NO_OP_EML_VALIDATOR);
  }

  @Test
  void shouldValidateForeignKeysFromDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("parent.csv"), "id,name\n1,earth\n2,mars\n");
    Files.writeString(tempDir.resolve("child.csv"),  "id,parent_id\n10,1\n11,999\n12,\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "sample",
          "resources": [
            { "name": "parent", "path": "parent.csv" },
            {
              "name": "child", "path": "child.csv",
              "schema": {
                "foreignKeys": [{
                  "fields": "parent_id",
                  "reference": { "resource": "parent", "fields": "id" }
                }]
              }
            }
          ]
        }
        """);

    DatapackageAnalysisResult result = orchestrator.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(), AnalysisFeature.ALL_FEATURES);

    assertFalse(DatapackageAnalysisResult.isValid(result));
    assertEquals(1, DatapackageAnalysisResult.foreignKeyViolations(result).size());
    assertEquals(1, DatapackageAnalysisResult.foreignKeyViolations(result).get(0).violationCount());
  }

  @Test
  void shouldDetectDataTypeViolations() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"),
      "id,age,active,birth_date\n1,25,true,2000-01-01\n2,notanumber,false,2001-06-15\n3,30,maybe,not-a-date\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "typed",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": { "fields": [
              { "name": "id",         "type": "integer" },
              { "name": "age",        "type": "integer" },
              { "name": "active",     "type": "boolean" },
              { "name": "birth_date", "type": "date"    }
            ]}
          }]
        }
        """);

    DatapackageAnalysisResult result = orchestrator.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(), AnalysisFeature.ALL_FEATURES);

    assertTrue(DatapackageAnalysisResult.foreignKeyViolations(result).isEmpty());
    assertFalse(DatapackageAnalysisResult.isValid(result));
    assertEquals(3, DatapackageAnalysisResult.dataTypeViolations(result).size());

    DataTypeViolation ageViolation = DatapackageAnalysisResult.dataTypeViolations(result).stream()
      .filter(v -> v.field().equals("age")).findFirst().orElseThrow();
    assertEquals(1, ageViolation.violationCount());
    assertEquals("integer", ageViolation.declaredType());
    assertTrue(ageViolation.sampleValues().contains("notanumber"));

    DataTypeViolation activeViolation = DatapackageAnalysisResult.dataTypeViolations(result).stream()
      .filter(v -> v.field().equals("active")).findFirst().orElseThrow();
    assertEquals(1, activeViolation.violationCount());

    DataTypeViolation dateViolation = DatapackageAnalysisResult.dataTypeViolations(result).stream()
      .filter(v -> v.field().equals("birth_date")).findFirst().orElseThrow();
    assertEquals(1, dateViolation.violationCount());
    assertTrue(dateViolation.sampleValues().contains("not-a-date"));
  }

  @Test
  void shouldPassWhenAllTypesAreCorrect() throws Exception {
    DatapackageAnalysisResult result = orchestrator.analyse(
      setupSmallValidDataset().resolve("datapackage.json"),
      ValidationOptions.defaults(), AnalysisFeature.ALL_FEATURES);
    assertTrue(DatapackageAnalysisResult.isValid(result));
  }

  @Test
  void shouldHaveCorrectRowAndColumnCounts() throws Exception {
    List<ResourceAnalysisResult> results = analyser.analyse(
      setupSmallValidDataset().resolve("datapackage.json"),
      ValidationOptions.defaults(),
      List.of(AnalysisFeature.COUNT, AnalysisFeature.COUNT_DISTINCT));

    ResourceAnalysisResult resource = results.stream()
      .filter(r -> r.name().equalsIgnoreCase("data")).findFirst()
      .orElseThrow(() -> new AssertionError("Resource[data] not found"));
    ColumnStatistics scoreStats = resource.columnAnalyses().stream()
      .filter(c -> c.name().equalsIgnoreCase("score")).findFirst()
      .orElseThrow(() -> new AssertionError("[data][score] not found"));

    assertEquals(3, resource.totalRows());
    assertEquals(3, scoreStats.populatedValues());
    assertEquals(2, scoreStats.uniqueValues());
  }

  @Test
  void shouldOnlyCalculateChosenFeatures() throws Exception {
    List<ResourceAnalysisResult> results = analyser.analyse(
      setupSmallValidDataset().resolve("datapackage.json"),
      ValidationOptions.defaults(),
      List.of(AnalysisFeature.DATA_TYPE_CONSTRAINT));
    assertTrue(results.stream().allMatch(r -> r.columnAnalyses().isEmpty()),
      "No counting etc for any of the data files");
  }

  @Test
  void shouldFailOnDuplicatePrimaryKeys() throws IOException, SQLException {
    Files.writeString(tempDir.resolve("data.csv"), """
      id,score
      1,2
      2,3
      2,5
      """);
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "duplicate-primary-key",
          "resources": [{
            "name": "data",
            "path": "data.csv",
            "schema": { "fields": [
              { "name": "id",    "type": "integer", "missingValues": ["-1"] },
              { "name": "score", "type": "number",  "missingValues": ["-1"] }
            ]},
            "primaryKey": "id"
          }]
        }
    """);

    List<ResourceAnalysisResult> results = analyser.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(),
      List.of(AnalysisFeature.PRIMARY_KEY_UNIQUE));

    assertFalse(results.isEmpty());
    assertEquals(1, results.size());
    assertNotNull(results.get(0).primaryKeyViolation());
    assertEquals("data", results.get(0).primaryKeyViolation().resource());
    assertEquals(1, results.get(0).primaryKeyViolation().violationCount());
  }

  @Test
  void shouldNotReQuoteAlreadyQuoted() {
    String value = "\"field\"";
    assertEquals(value, DuckDbRenderUtils.q(value),
      "Multiple invocations of quoting should have no effect");
  }

  @Test
  void shouldNotCountFieldLevelMissingValuesAsPopulated() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n-1,2.71\n3,-1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-field",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": { "fields": [
              { "name": "id",    "type": "integer", "missingValues": ["-1"] },
              { "name": "score", "type": "number",  "missingValues": ["-1"] }
            ]}
          }]
        }
        """);

    List<ResourceAnalysisResult> results = analyser.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(),
      List.of(AnalysisFeature.COUNT, AnalysisFeature.COUNT_DISTINCT));

    ColumnStatistics idStats    = getColumnStats(results, "data", "id");
    ColumnStatistics scoreStats = getColumnStats(results, "data", "score");
    assertEquals(2, idStats.populatedValues(),    "id: '-1' is missing, so only rows 1 and 3 count");
    assertEquals(2, scoreStats.populatedValues(), "score: '-1' is missing, so only rows 1 and 2 count");
    assertEquals(2, idStats.uniqueValues(),       "id: 1 and 3");
    assertEquals(2, scoreStats.uniqueValues(),    "score: 3.14 and 2.71");
  }

  @Test
  void shouldFallBackToSchemaMissingValuesWhenFieldDoesNotDefineOwn() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n2,-1\n3,2.71\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-schema",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": {
              "missingValues": ["-1"],
              "fields": [
                { "name": "id",    "type": "integer" },
                { "name": "score", "type": "number"  }
              ]
            }
          }]
        }
        """);

    List<ResourceAnalysisResult> results = analyser.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(),
      List.of(AnalysisFeature.COUNT, AnalysisFeature.COUNT_DISTINCT));

    ColumnStatistics scoreStats = getColumnStats(results, "data", "score");
    assertEquals(2, scoreStats.populatedValues(), "score: '-1' inherited from schema counts as missing");
    assertEquals(2, scoreStats.uniqueValues());
  }

  @Test
  void shouldPreferFieldMissingValuesOverSchemaMissingValues() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n2,-1\n3,-999\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-precedence",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": {
              "missingValues": ["-999"],
              "fields": [
                { "name": "id",    "type": "integer" },
                { "name": "score", "type": "number", "missingValues": ["-1"] }
              ]
            }
          }]
        }
        """);

    List<ResourceAnalysisResult> results = analyser.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(),
      List.of(AnalysisFeature.COUNT, AnalysisFeature.COUNT_DISTINCT));

    ColumnStatistics idStats    = getColumnStats(results, "data", "id");
    ColumnStatistics scoreStats = getColumnStats(results, "data", "score");
    assertEquals(3, idStats.populatedValues(),    "id: '-999' is missing via schema fallback");
    assertEquals(2, scoreStats.populatedValues(), "score: '-1' is missing, but '-999' is a real value");
    assertEquals(2, scoreStats.uniqueValues(),    "score: 3.14 and -999 are the two populated distinct values");
  }

  @Test
  void shouldUseDefaultMissingValueOfEmptyStringWhenNeitherFieldNorSchemaDefinesMissingValues()
    throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n2,-1\n3,\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-default",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": { "fields": [
              { "name": "id",    "type": "integer" },
              { "name": "score", "type": "number"  }
            ]}
          }]
        }
        """);

    List<ResourceAnalysisResult> results = analyser.analyse(
      tempDir.resolve("datapackage.json"), ValidationOptions.defaults(),
      List.of(AnalysisFeature.COUNT, AnalysisFeature.COUNT_DISTINCT));

    assertEquals(2, getColumnStats(results, "data", "score").populatedValues(),
      "score: empty cell is null, '-1' is a real (if invalid) value");
  }

  @Test
  void shouldReadTsvViaDialect() throws Exception {
    Files.writeString(tempDir.resolve("data.tsv"), "id\tname\n1\tearth\n2\tmars\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "tsv-test", "resources": [{
          "name": "data", "path": "data.tsv", "mediatype": "text/csv",
          "dialect": { "delimiter": "\\t" },
          "schema": { "fields": [{ "name": "id", "type": "integer" }, { "name": "name", "type": "string" }]}
        }]}
        """);
    assertEquals(2, analyser.analyse(tempDir.resolve("datapackage.json"),
      ValidationOptions.defaults(), List.of(AnalysisFeature.COUNT)).get(0).totalRows());
  }

  @Test
  void shouldReadTsvViaExtensionFallback() throws Exception {
    Files.writeString(tempDir.resolve("data.tsv"), "id\tname\n1\tearth\n2\tmars\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "tsv-ext-test", "resources": [{
          "name": "data", "path": "data.tsv", "mediatype": "text/csv",
          "schema": { "fields": [{ "name": "id", "type": "integer" }, { "name": "name", "type": "string" }]}
        }]}
        """);
    assertEquals(2, analyser.analyse(tempDir.resolve("datapackage.json"),
      ValidationOptions.defaults(), List.of(AnalysisFeature.COUNT)).get(0).totalRows());
  }

  @Test
  void shouldReadSemicolonDelimitedFile() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id;name\n1;earth\n2;mars\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "semicolon-test", "resources": [{
          "name": "data", "path": "data.csv", "mediatype": "text/csv",
          "dialect": { "delimiter": ";" },
          "schema": { "fields": [{ "name": "id", "type": "integer" }, { "name": "name", "type": "string" }]}
        }]}
        """);
    assertEquals(2, analyser.analyse(tempDir.resolve("datapackage.json"),
      ValidationOptions.defaults(), List.of(AnalysisFeature.COUNT)).get(0).totalRows());
  }

  @Test
  void shouldHandleQuotedFieldsContainingDelimiter() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,name\n1,\"earth, the planet\"\n2,mars\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "quoted-test", "resources": [{
          "name": "data", "path": "data.csv", "mediatype": "text/csv",
          "dialect": { "delimiter": ",", "quoteChar": "\\"" },
          "schema": { "fields": [{ "name": "id", "type": "integer" }, { "name": "name", "type": "string" }]}
        }]}
        """);
    assertEquals(2, analyser.analyse(tempDir.resolve("datapackage.json"),
      ValidationOptions.defaults(), List.of(AnalysisFeature.COUNT)).get(0).totalRows());
  }

  private ColumnStatistics getColumnStats(
    List<ResourceAnalysisResult> results, String resource, String column) {
    return results.stream()
      .filter(r -> r.name().equalsIgnoreCase(resource)).findFirst()
      .orElseThrow(() -> new AssertionError("Resource not found: " + resource))
      .columnAnalyses().stream()
      .filter(c -> c.name().equalsIgnoreCase(column)).findFirst()
      .orElseThrow(() -> new AssertionError("Column not found: " + column));
  }

  private Path setupSmallValidDataset() throws IOException {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n2,2.71\n3,2.71\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "clean",
          "resources": [{
            "name": "data", "path": "data.csv",
            "schema": { "fields": [
              { "name": "id",    "type": "integer" },
              { "name": "score", "type": "number"  }
            ]}
          }]
        }
        """);
    return tempDir;
  }
}
