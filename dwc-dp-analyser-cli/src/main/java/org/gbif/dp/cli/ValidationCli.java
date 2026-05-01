package org.gbif.dp.cli;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gbif.dp.analysis.AnalysisFeature;
import org.gbif.dp.analysis.DataPackageAnalyser;
import org.gbif.dp.analysis.duckdb.DuckDbDataPackageAnalyser;
import org.gbif.dp.analysis.ValidationOptions;
import org.gbif.dp.analysis.api.DataTypeViolation;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.ForeignKeyViolation;
import org.gbif.dp.descriptor.JacksonDataPackageParser;
import org.gbif.dp.duckdb.CustomDuckDbConfig;

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
        int exitCode = run(args);
        System.exit(exitCode);
    }

    public static int run(String[] args) throws Exception {
        Config arguments = new Config();
        CommandLine commandLine = new CommandLine(arguments);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        int executed = commandLine.execute(args);
        if (executed != 0) {
            commandLine.printVersionHelp(System.err);
            return EXIT_PROGRAM_ERROR;
        }

        if (arguments.quiet) {
            Logging.setRootLevel(Level.ERROR);
        } else if (arguments.verbose) {
            Logging.setRootLevel(Level.DEBUG);
        } else {
            Logging.setRootLevel(Level.INFO);
        }

        Instant startTimer = Instant.now();

        CustomDuckDbConfig customDuckDbConfig = new CustomDuckDbConfig(
                arguments.duckdbMemory,
                arguments.duckdbThreads,
                arguments.duckdbTempDir,
                arguments.duckdbMaxTemp);

        DataPackageAnalyser validator = new DuckDbDataPackageAnalyser(
                new JacksonDataPackageParser(),
                new DuckDbResourceLoader());
        ValidationOptions defaultOptions = ValidationOptions.defaults();
        ValidationOptions validationOptions = new ValidationOptions(
                defaultOptions.sampleSize(), defaultOptions.jdbcUrl(), customDuckDbConfig);

        DatapackageAnalysisResult result = validator.analyse(
                Path.of(args[0]), validationOptions, AnalysisFeature.ALL_FEATURES);

        Duration duration = Duration.between(startTimer, Instant.now());

        if (arguments.outputFormat == Config.OutputFormat.JSON) {
            printJson(result, duration);
        } else {
            printText(result, duration);
        }

        if (!DatapackageAnalysisResult.isValid(result)) {
            return EXIT_VALIDATION_ERROR;
        }

        return EXIT_VALIDATION_SUCCESS;
    }

    private static void printJson(DatapackageAnalysisResult result, Duration duration) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        // Wrap result + duration together so duration is part of the JSON output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result);
        output.put("durationSeconds", duration.toSeconds());
        output.put("valid", DatapackageAnalysisResult.isValid(result));
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }

    private static void printText(DatapackageAnalysisResult result, Duration duration) {
        if (DatapackageAnalysisResult.isValid(result)) {
            System.out.println("All validations passed.");
        }

        for (ForeignKeyViolation v : DatapackageAnalysisResult.foreignKeyViolations(result)) {
            System.out.printf("FK violation: %s(%s) -> %s(%s), count=%d%n",
                    v.resource(), String.join(",", v.fields()),
                    v.referenceResource(), String.join(",", v.referenceFields()),
                    v.violationCount());
            for (var sample : v.sampleRows()) {
                System.out.println("  sample=" + sample);
            }
        }

        for (DataTypeViolation v : DatapackageAnalysisResult.dataTypeViolations(result)) {
            System.out.printf("Type violation: %s.%s declared as '%s', count=%d%n",
                    v.resource(), v.field(), v.declaredType(), v.violationCount());
            for (String sample : v.sampleValues()) {
                System.out.println("  bad value: " + sample);
            }
        }

        System.out.printf("duration: %02d:%02d:%02d%n",
                duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart());
    }
}
