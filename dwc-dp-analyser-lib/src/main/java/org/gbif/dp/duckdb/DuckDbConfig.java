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
