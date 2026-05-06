package org.gbif.dp.cli;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gbif.dp.analysis.DefaultDataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.DataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.analysis.api.ForeignKeyViolation;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.analysis.duckdb.DuckDbDataPackageAnalyser;
import org.gbif.dp.analysis.duckdb.DuckDbDialectRenderer;
import org.gbif.dp.analysis.duckdb.DuckDbResourceLoader;
import org.gbif.dp.descriptor.JacksonDataPackageParser;
import org.gbif.dp.duckdb.CustomDuckDbConfig;
import org.gbif.dp.validator.api.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
      printJson(result, duration);
    } else {
      printText(result, duration);
    }

    return DatapackageAnalysisResult.isValid(result) ? EXIT_VALIDATION_SUCCESS : EXIT_VALIDATION_ERROR;
  }

  private static void printJson(DatapackageAnalysisResult result, Duration duration)
    throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("result", result);
    output.put("durationSeconds", duration.toSeconds());
    output.put("valid", DatapackageAnalysisResult.isValid(result));
    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
  }

  private static void printText(DatapackageAnalysisResult result, Duration duration) {
    // ── Descriptor issues ─────────────────────────────────────────────────
    var descriptor = result.descriptorValidation();
    if (!descriptor.issues().isEmpty()) {
      System.out.println("=== Descriptor validation ===");
      for (ValidationIssue issue : descriptor.issues()) {
        String loc = issue.location() != null ? " [" + issue.location() + "]" : "";
        System.out.printf("[%s] %s%s: %s%n",
          issue.severity(), issue.code(), loc, issue.message());
      }
    }

    if (!descriptor.canProceedToDataAnalysis()) {
      System.out.println("Data analysis skipped due to blocking descriptor errors.");
      printDuration(duration);
      return;
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

    printDuration(duration);
  }

  private static void printDuration(Duration d) {
    System.out.printf("duration: %02d:%02d:%02d%n",
      d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
  }
}
