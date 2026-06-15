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
import org.gbif.dp.analysis.api.ResourceAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.analysis.duckdb.DuckDbDataPackageAnalyser;
import org.gbif.dp.analysis.duckdb.DuckDbDialectRenderer;
import org.gbif.dp.analysis.duckdb.DuckDbResourceLoader;
import org.gbif.dp.descriptor.JacksonDataPackageParser;
import org.gbif.dp.duckdb.CustomDuckDbConfig;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
    if (executed != 0) {
      commandLine.printVersionHelp(System.err);
      return EXIT_PROGRAM_ERROR;
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

    DatapackageAnalysisResult result = orchestrator.analyse(
      Path.of(config.descriptorPath),
      ValidationOptions.defaults(),
      AnalysisFeature.ALL_FEATURES);

    Duration duration = Duration.between(start, Instant.now());

    if (config.outputFormat == Config.OutputFormat.JSON) {
      printJson(result, duration, config.reportMode);
    } else {
      printText(result, duration, config.reportMode);
    }

    return DatapackageAnalysisResult.isValid(result) ? EXIT_VALIDATION_SUCCESS : EXIT_VALIDATION_ERROR;
  }

  private static void printJson(DatapackageAnalysisResult result, Duration duration, Config.ReportMode mode)
    throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("durationSeconds", duration.toSeconds());
    output.put("valid", DatapackageAnalysisResult.isValid(result));
    if (mode == Config.ReportMode.STATS && result.descriptorValidation().canProceedToDataAnalysis()) {
      List<ResourceAnalysisResult> onlyStats = result.resourceAnalysisResults().stream()
        .map(resourceResult -> new ResourceAnalysisResult(
          resourceResult.name(),
          List.of(),
          null,
          List.of(),
          resourceResult.columnAnalyses(),
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
    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
  }

  private static void printText(DatapackageAnalysisResult result, Duration duration, Config.ReportMode mode) {
    // ── Descriptor issues ─────────────────────────────────────────────────
    var descriptor = result.descriptorValidation();
    if (!descriptor.canProceedToDataAnalysis()) {
      System.out.println("Data analysis skipped due to blocking descriptor errors.");
      printDuration(duration);
      return;
    }

    if (Set.of(Config.ReportMode.FULL, Config.ReportMode.VERIFY).contains(mode)) {
      printValidationResults(result, descriptor);
    }
    if (Set.of(Config.ReportMode.FULL, Config.ReportMode.STATS).contains(mode)) {
      printStatisticsTable(result.resourceAnalysisResults());
    }
    printDuration(duration);
  }

  private static void printValidationResults(DatapackageAnalysisResult result, DescriptorValidationResult descriptor) {
    if (!descriptor.issues().isEmpty()) {
      System.out.println("=== Descriptor validation ===");
      for (ValidationIssue issue : descriptor.issues()) {
        String loc = issue.location() != null ? " [" + issue.location() + "]" : "";
        System.out.printf("[%s] %s%s: %s%n",
          issue.severity(), issue.code(), loc, issue.message());
      }
    }


    // ── EML issues ────────────────────────────────────────────────────────
    var eml = result.emlValidation();
    if (eml.emlPresent() && !eml.issues().isEmpty()) {
      System.out.println("=== EML validation ===");
      for (ValidationIssue issue : eml.issues()) {
        String loc = issue.location() != null ? " [" + issue.location() + "]" : "";
        System.out.printf("[%s] %s%s: %s%n",
          issue.severity(), issue.code(), loc, issue.message());
      }
    }

    // ── Data violations ───────────────────────────────────────────────────
    if (DatapackageAnalysisResult.isValid(result)) {
      System.out.println("All validations passed.");
    }

    for (ForeignKeyViolation v : DatapackageAnalysisResult.foreignKeyViolations(result)) {
      System.out.printf("FK violation: %s(%s) -> %s(%s), count=%d%n",
        v.resource(), String.join(",", v.fields()),
        v.referenceResource(), String.join(",", v.referenceFields()),
        v.violationCount());
      v.sampleRows().forEach(row -> System.out.println("  sample=" + row));
    }

    for (DataTypeViolation v : DatapackageAnalysisResult.dataTypeViolations(result)) {
      System.out.printf("Type violation: %s.%s declared as '%s', count=%d%n",
        v.resource(), v.field(), v.declaredType(), v.violationCount());
      v.sampleValues().forEach(val -> System.out.println("  bad value: " + val));
    }
  }

  private static void printDuration(Duration d) {
    System.out.printf("duration: %02d:%02d:%02d%n",
      d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
  }

  private static void printStatisticsTable(List<ResourceAnalysisResult> resources) {
    for (ResourceAnalysisResult resource : resources) {
      if (resource.columnAnalyses() == null || resource.columnAnalyses().isEmpty()) continue;

      int nameWidth = resource.columnAnalyses().stream()
        .mapToInt(c -> c.name().length()).max().orElse(4);
      nameWidth = Math.max(nameWidth, 5);

      long maxPop = resource.columnAnalyses().stream()
        .mapToLong(ColumnStatistics::populatedValues).max().orElse(0);
      long maxUniq = resource.columnAnalyses().stream()
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

      for (ColumnStatistics col : resource.columnAnalyses()) {
        String pct = resource.totalRows() > 0
          ? String.format("%.1f%%", 100.0 * col.populatedValues() / resource.totalRows())
          : "N/A";
        System.out.printf(fmt, col.name(), resource.totalRows(), col.populatedValues(), col.uniqueValues(), pct);
      }

      System.out.println(divider);
      System.out.println();
    }
  }
}
