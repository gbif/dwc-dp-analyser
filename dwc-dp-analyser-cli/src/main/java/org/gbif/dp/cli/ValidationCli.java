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
package org.gbif.dp.cli;

import org.gbif.dp.analysis.DefaultDataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.ColumnStatistics;
import org.gbif.dp.analysis.api.DataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.ForeignKeyViolation;
import org.gbif.dp.analysis.api.PrimaryKeyViolation;
import org.gbif.dp.analysis.api.ResourceAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.analysis.duckdb.DuckDbDataPackageAnalyser;
import org.gbif.dp.analysis.duckdb.DuckDbDialectRenderer;
import org.gbif.dp.analysis.duckdb.DuckDbResourceLoader;
import org.gbif.dp.common.descriptor.JacksonDataPackageParser;
import org.gbif.dp.duckdb.CustomDuckDbConfig;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import picocli.CommandLine;

public class ValidationCli {

  private static final Logger log = LoggerFactory.getLogger(ValidationCli.class);

  private static final int EXIT_VALIDATION_SUCCESS = 0;
  private static final int EXIT_PROGRAM_ERROR = 1;
  private static final int EXIT_VALIDATION_ERROR = 2;

  public static void main(String[] args) throws Exception {
    System.exit(run(args));
  }

  public static int run(String[] args) throws Exception {
    Config config = new Config();
    CommandLine commandLine = new CommandLine(config);
    commandLine.setCaseInsensitiveEnumValuesAllowed(true);
    int executed = commandLine.execute(args);

    if (commandLine.isUsageHelpRequested() || commandLine.isVersionHelpRequested()) {
      return EXIT_VALIDATION_SUCCESS;
    }

    if (executed != 0) {
      commandLine.usage(System.err);
      return EXIT_PROGRAM_ERROR;
    }

    int pathCheck = validateDescriptorPath(config.descriptorPath);
    if (pathCheck != EXIT_VALIDATION_SUCCESS) {
      return pathCheck;
    }

    if (config.quiet) {
      Logging.setRootLevel(Level.ERROR);
    } else if (config.verbose) {
      Logging.setRootLevel(Level.DEBUG);
    } else {
      Logging.setRootLevel(Level.INFO);
    }

    Instant start = Instant.now();

    CustomDuckDbConfig duckDbConfig = new CustomDuckDbConfig(
      config.duckdbUrl,
      config.duckdbMemory,
      config.duckdbThreads,
      config.duckdbTempDir,
      config.duckdbMaxTemp);

    DataPackageAnalysisOrchestrator orchestrator = new DefaultDataPackageAnalysisOrchestrator(
      new DuckDbDataPackageAnalyser(
        new JacksonDataPackageParser(),
        new DuckDbResourceLoader(new DuckDbDialectRenderer()),
        duckDbConfig));

    DatapackageAnalysisResult result;
    try {
      result = orchestrator.analyse(
        config.descriptorPath,
        ValidationOptions.defaults(),
        AnalysisFeature.ALL_FEATURES);
    } catch (Exception e) {
      System.err.println("Error: analysis failed: " + e.getMessage());
      if (config.verbose) {
        log.error("Full stack trace:", e);
      } else {
        System.err.println("  (run with --verbose for the full stack trace)");
      }
      return EXIT_PROGRAM_ERROR;
    }

    Duration duration = Duration.between(start, Instant.now());

    if (config.outputFormat == Config.OutputFormat.JSON) {
      printJson(result, duration, config.reportMode);
    } else {
      printText(result, duration, config.reportMode);
    }

    return DatapackageAnalysisResult.isValid(result) ? EXIT_VALIDATION_SUCCESS : EXIT_VALIDATION_ERROR;
  }

  /**
   * Sanity-checks the descriptor path before it's handed to the orchestrator,
   * so a bad path fails with a clear one-line message instead of an NPE deep
   * inside NIO/DuckDB internals.
   */
  private static int validateDescriptorPath(String descriptorPath) {
    if (descriptorPath == null || descriptorPath.isBlank()) {
      System.err.println("Error: <descriptorPath> is required (path to datapackage.json)");
      return EXIT_PROGRAM_ERROR;
    }

    Path path = Path.of(descriptorPath);

    if (!Files.exists(path)) {
      System.err.println("Error: file not found: " + descriptorPath);
      System.err.println("  hint: check the path is correct and relative to your current directory ("
                         + System.getProperty("user.dir") + ")");
      return EXIT_PROGRAM_ERROR;
    }

    if (Files.isDirectory(path)) {
      System.err.println("Error: expected a file but found a directory: " + descriptorPath);
      System.err.println("  hint: point <descriptorPath> at the datapackage.json file itself, not its containing folder");
      return EXIT_PROGRAM_ERROR;
    }

    if (!Files.isReadable(path)) {
      System.err.println("Error: file exists but is not readable (check permissions): " + descriptorPath);
      return EXIT_PROGRAM_ERROR;
    }

    if (!descriptorPath.endsWith(".json")) {
      System.err.println("Warning: expected a .json file, got: " + descriptorPath);
    }

    return EXIT_VALIDATION_SUCCESS;
  }

  private static void printJson(DatapackageAnalysisResult result, Duration duration, Config.ReportMode mode)
    throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("durationSeconds", duration.toSeconds());
    output.put("isValid", DatapackageAnalysisResult.isValid(result));
    if (mode == Config.ReportMode.STATS && result.descriptorValidation().hasDataAnalysis()) {
      List<ResourceAnalysisResult> onlyStats = result.resourceAnalysisResults().stream()
        .map(resourceResult -> new ResourceAnalysisResult(
          resourceResult.name(),
          List.of(),
          null,
          List.of(),
          resourceResult.columnStatistics(),
          resourceResult.totalRows()
        ))
        .toList();
      result = new DatapackageAnalysisResult(null, null, onlyStats);
    }
    if (mode == Config.ReportMode.VERIFY) {
      List<ResourceAnalysisResult> onlyValidation = result.resourceAnalysisResults().stream()
        .map(resourceResult -> new ResourceAnalysisResult(
          resourceResult.name(),
          resourceResult.foreignKeyViolations(),
          resourceResult.primaryKeyViolation(),
          resourceResult.dataTypeViolations(),
          List.of(),
          resourceResult.totalRows()
        ))
        .toList();
      result = new DatapackageAnalysisResult(result.descriptorValidation(), result.emlValidation(), onlyValidation);
    }
    output.put("result", result);
    output.put("report-mode", mode.name());
    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
  }

  private static void printText(DatapackageAnalysisResult result, Duration duration, Config.ReportMode mode) {
    System.out.println("Printing " + mode.name() + " report.");
    var descriptor = result.descriptorValidation();
    if (!descriptor.hasDataAnalysis()) {
      System.out.println("Data analysis skipped due to blocking descriptor errors.");
      printDescriptorIssues(descriptor);
      printValidity(result);
      printDuration(duration);
      return;
    }

    if (Set.of(Config.ReportMode.FULL, Config.ReportMode.VERIFY).contains(mode)) {
      printValidationResults(result);
    }
    if (Set.of(Config.ReportMode.FULL, Config.ReportMode.STATS).contains(mode)) {
      printStatisticsTable(result.resourceAnalysisResults());
    }
    printViolationSummary(result.resourceAnalysisResults());

    printValidity(result);
    printDuration(duration);
  }

  private static void printValidationResults(DatapackageAnalysisResult result) {
    printDescriptorIssues(result.descriptorValidation());
    printEmlIssues(result);
    printResourceViolations(result);
  }

  private static void printDescriptorIssues(DescriptorValidationResult descriptor) {
    if (!descriptor.issues().isEmpty()) {
      System.out.println("=== Descriptor validation ===");
      for (ValidationIssue issue : descriptor.issues()) {
        String loc = issue.location() != null ? " [" + issue.location() + "]" : "";
        System.out.printf("[%s] %s%s: %s%n",
                          issue.severity(), issue.code(), loc, issue.message());
      }
    }
  }

  private static void printEmlIssues(DatapackageAnalysisResult result) {
    EmlValidationResult eml = result.emlValidation();
    if (!eml.isPresent() || eml.issues().isEmpty()) {
      return;
    }

    System.out.println("=== EML validation ===");
    for (ValidationIssue issue : eml.issues()) {
      String loc = issue.location() != null ? " [" + issue.location() + "]" : "";
      System.out.printf("[%s] %s%s: %s%n",
                        issue.severity(), issue.code(), loc, issue.message());
    }
  }

  private static void printResourceViolations(DatapackageAnalysisResult result) {
    for (ResourceAnalysisResult resource : result.resourceAnalysisResults()) {
      PrimaryKeyViolation pkViolation = resource.primaryKeyViolation();
      List<ForeignKeyViolation> fkViolations = resource.foreignKeyViolations();
      List<DataTypeViolation> typeViolations = resource.dataTypeViolations();

      boolean hasFkViolations = fkViolations != null && !fkViolations.isEmpty();
      boolean hasTypeViolations = typeViolations != null && !typeViolations.isEmpty();

      if (pkViolation == null && !hasFkViolations && !hasTypeViolations) {
        continue;
      }

      System.out.println("--- " + resource.name() + " ---");

      if (pkViolation != null) {
        System.out.printf("PK violation: %s(%s), count=%d%n",
                          pkViolation.resource(), String.join(",", pkViolation.fields()),
                          pkViolation.violationCount());
        pkViolation.sampleRows().forEach(row -> System.out.println("  sample=" + row));
      }

      if (hasFkViolations) {
        for (ForeignKeyViolation v : fkViolations) {
          System.out.printf("FK violation: %s(%s) -> %s(%s), count=%d%n",
                            v.resource(), String.join(",", v.fields()),
                            v.referenceResource(), String.join(",", v.referenceFields()),
                            v.violationCount());
          v.sampleRows().forEach(row -> System.out.println("  sample=" + row));
        }
      }

      if (hasTypeViolations) {
        for (DataTypeViolation v : typeViolations) {
          System.out.printf("Type violation: %s.%s declared as '%s', count=%d%n",
                            v.resource(), v.field(), v.declaredType(), v.violationCount());
          v.sampleValues().forEach(val -> System.out.println("  bad value: " + val));
        }
      }
    }
  }

  private static void printValidity(DatapackageAnalysisResult result) {
    System.out.println(DatapackageAnalysisResult.isValid(result) ? "Result: VALID" : "Result: INVALID");
  }

  private static void printDuration(Duration d) {
    System.out.printf("Duration: %02d:%02d:%02d%n",
                      d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
  }

  private static void printStatisticsTable(List<ResourceAnalysisResult> resources) {
    for (ResourceAnalysisResult resource : resources) {
      if (resource.columnStatistics() == null || resource.columnStatistics().isEmpty()) continue;

      int nameWidth = resource.columnStatistics().stream()
        .mapToInt(c -> c.name().length()).max().orElse(4);
      nameWidth = Math.max(nameWidth, 5);

      long maxPop = resource.columnStatistics().stream()
        .mapToLong(ColumnStatistics::populatedValues).max().orElse(0);
      long maxUniq = resource.columnStatistics().stream()
        .mapToLong(ColumnStatistics::uniqueValues).max().orElse(0);
      int popWidth  = Math.max(String.valueOf(maxPop).length(),  9);
      int uniqWidth = Math.max(String.valueOf(maxUniq).length(), 6);
      int totalWidth = Math.max(String.valueOf(resource.totalRows()).length(), 5);
      int pctWidth  = 6;

      String fmt = "| %-" + nameWidth + "s | %" + totalWidth + "s | %" + popWidth + "s | %" + uniqWidth + "s | %" + pctWidth + "s |%n";
      String divider = "+-" + "-".repeat(nameWidth) + "-+-" + "-".repeat(totalWidth) + "-+-"
                       + "-".repeat(popWidth) + "-+-" + "-".repeat(uniqWidth) + "-+-" + "-".repeat(pctWidth) + "-+";

      System.out.println("=== " + resource.name() + " (" + resource.totalRows() + " rows) ===");
      System.out.println(divider);
      System.out.printf(fmt, "Field", "Total", "Populated", "Unique", "Fill%");
      System.out.println(divider);

      for (ColumnStatistics col : resource.columnStatistics()) {
        String pct = resource.totalRows() > 0
          ? String.format("%.1f%%", 100.0 * col.populatedValues() / resource.totalRows())
          : "N/A";
        System.out.printf(fmt, col.name(), resource.totalRows(), col.populatedValues(), col.uniqueValues(), pct);
      }

      System.out.println(divider);
      System.out.println();
    }
  }

  private record ViolationSummaryRow(String resource, String type, String field, long count) {}

  private static void printViolationSummary(List<ResourceAnalysisResult> resources) {
    List<ViolationSummaryRow> rows = new ArrayList<>();

    for (ResourceAnalysisResult resource : resources) {
      PrimaryKeyViolation pkViolation = resource.primaryKeyViolation();
      if (pkViolation != null) {
        rows.add(new ViolationSummaryRow(
          resource.name(), "PK", String.join(",", pkViolation.fields()), pkViolation.violationCount()));
      }

      List<ForeignKeyViolation> fkViolations = resource.foreignKeyViolations();
      if (fkViolations != null) {
        for (ForeignKeyViolation v : fkViolations) {
          rows.add(new ViolationSummaryRow(
            resource.name(), "FK", String.join(",", v.fields()), v.violationCount()));
        }
      }

      List<DataTypeViolation> typeViolations = resource.dataTypeViolations();
      if (typeViolations != null) {
        for (DataTypeViolation v : typeViolations) {
          rows.add(new ViolationSummaryRow(resource.name(), "Type", v.field(), v.violationCount()));
        }
      }
    }

    if (rows.isEmpty()) {
      return;
    }

    int resourceWidth = Math.max(rows.stream().mapToInt(r -> r.resource().length()).max().orElse(0), 8);
    int typeWidth = Math.max(rows.stream().mapToInt(r -> r.type().length()).max().orElse(0), 4);
    int fieldWidth = Math.max(rows.stream().mapToInt(r -> r.field().length()).max().orElse(0), 5);
    int countWidth = Math.max(rows.stream().mapToInt(r -> String.valueOf(r.count()).length()).max().orElse(0), 5);

    String fmt = "| %-" + resourceWidth + "s | %-" + typeWidth + "s | %-" + fieldWidth + "s | %" + countWidth + "s |%n";
    String divider = "+-" + "-".repeat(resourceWidth) + "-+-" + "-".repeat(typeWidth) + "-+-"
                     + "-".repeat(fieldWidth) + "-+-" + "-".repeat(countWidth) + "-+";

    System.out.println("=== Violation summary ===");
    System.out.println(divider);
    System.out.printf(fmt, "Resource", "Type", "Field", "Count");
    System.out.println(divider);

    for (ViolationSummaryRow r : rows) {
      System.out.printf(fmt, r.resource(), r.type(), r.field(), r.count());
    }

    System.out.println(divider);
    System.out.println();
  }
}
