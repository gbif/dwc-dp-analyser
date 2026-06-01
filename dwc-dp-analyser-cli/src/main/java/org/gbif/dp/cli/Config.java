package org.gbif.dp.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "app", mixinStandardHelpOptions = true)
public class Config implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  @Parameters(index = "0", description = "Path to datapackage.json")
  public String descriptorPath;

  @Option(names = "--log-dir", description = "Directory for logs",
    defaultValue = "${env:LOG_DIR:-./logs}")
  public String logDir;

  @Option(names = "--duckdb-url", description = "DuckDB JDBC URL",
    defaultValue = "${env:DUCKDB_URL:-jdbc:duckdb:}")
  public String duckdbUrl;

  @Option(names = "--duckdb-memory", description = "DuckDB memory limit",
    defaultValue = "${env:DUCKDB_MEMORY_LIMIT:-1500MB}")
  public String duckdbMemory;

  @Option(names = "--duckdb-threads", description = "DuckDB threads",
    defaultValue = "${env:DUCKDB_THREADS:-2}")
  public int duckdbThreads;

  @Option(names = "--duckdb-temp-dir", description = "DuckDB temp directory",
    defaultValue = "${env:DUCKDB_TEMP_DIR:-./tmp}")
  public String duckdbTempDir;

  @Option(names = "--duckdb-max-temp", description = "DuckDB max temp size",
    defaultValue = "${env:DUCKDB_MAX_TEMP_SIZE:-20GB}")
  public String duckdbMaxTemp;

  @Option(names = "--report", description = "Report sections: FULL, STATS, VERIFY",
    defaultValue = "${env:REPORT:-FULL}")
  public ReportMode reportMode;

  public enum ReportMode { FULL, STATS, VERIFY }

  @Option(names = "--verbose", description = "Enable debug logging")
  public boolean verbose;

  @Option(names = "--quiet", description = "Only show errors")
  public boolean quiet;

  @Option(names = "--output-format", description = "Output format: TEXT or JSON",
    defaultValue = "${env:OUTPUT_FORMAT:-TEXT}")
  public OutputFormat outputFormat;

  public enum OutputFormat { TEXT, JSON }

  @Override
  public void run() {
    if (verbose) {
      log.info("descriptorPath = {}", descriptorPath);
      log.info("duckdbUrl      = {}", duckdbUrl);
      log.info("duckdbMemory   = {}", duckdbMemory);
      log.info("duckdbThreads  = {}", duckdbThreads);
      log.info("duckdbTempDir  = {}", duckdbTempDir);
      log.info("duckdbMaxTemp  = {}", duckdbMaxTemp);
      log.info("outputFormat   = {}", outputFormat);
    }
  }
}
