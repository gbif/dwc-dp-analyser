package org.gbif.dp.duckdb;

/**
 * DuckDB-specific configuration supplied to {@code DuckDbDataPackageAnalyser} at construction.
 * Kept in {@code dwc-dp-analyser-lib} — it is an implementation detail, not part of the
 * {@code DataAnalyser} interface contract.
 */
public interface DuckDbConfig {
  /** JDBC connection URL, e.g. {@code "jdbc:duckdb:"}. */
  String jdbcUrl();
  /** Memory limit passed to DuckDB, e.g. {@code "1500MB"}. Empty string = use DuckDB default. */
  String dbMemory();
  /** Number of DuckDB threads. -1 = use DuckDB default. */
  int dbThreads();
  /** DuckDB temp directory path. Empty string = use DuckDB default. */
  String dbTempDir();
  /** DuckDB max temp size. Empty string = use DuckDB default. */
  String dbMaxTemp();
}
