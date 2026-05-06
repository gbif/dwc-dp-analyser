package org.gbif.dp.duckdb;

/**
 * Caller-supplied DuckDB configuration, typically built from CLI arguments or env vars.
 */
public record CustomDuckDbConfig(
  String jdbcUrl,
  String dbMemory,
  int dbThreads,
  String dbTempDir,
  String dbMaxTemp) implements DuckDbConfig {}
