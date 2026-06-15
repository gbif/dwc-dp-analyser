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

public final class DuckDbConfigBuilder {
  public static final String DEFAULT_JDBC = "jdbc:duckdb:";
  public static final String DEFAULT_DB_MEMORY = "";
  public static final String DEFAULT_DB_TEMP_DIR = "";
  public static final String DEFAULT_DB_MAX_TEMP = "";
  public static final int DEFAULT_DB_MAX_THREADS = -1;

  private String jdbcUrl = DEFAULT_JDBC;
  private String dbMemory = DEFAULT_DB_MEMORY;
  private int dbThreads = DEFAULT_DB_MAX_THREADS;
  private String dbTempDir = DEFAULT_DB_TEMP_DIR;
  private String dbMaxTemp = DEFAULT_DB_MAX_TEMP;

  public static DuckDbConfigBuilder defaults() {
    return new DuckDbConfigBuilder();
  }

  public DuckDbConfigBuilder jdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
    return this;
  }

  public DuckDbConfigBuilder dbMemory(String dbMemory) {
    this.dbMemory = dbMemory;
    return this;
  }

  public DuckDbConfigBuilder dbThreads(int dbThreads) {
    this.dbThreads = dbThreads;
    return this;
  }

  public DuckDbConfigBuilder dbTempDir(String dbTempDir) {
    this.dbTempDir = dbTempDir;
    return this;
  }

  public DuckDbConfigBuilder dbMaxTemp(String dbMaxTemp) {
    this.dbMaxTemp = dbMaxTemp;
    return this;
  }

  public DuckDbConfig build() {
    return new CustomDuckDbConfig(jdbcUrl, dbMemory, dbThreads, dbTempDir, dbMaxTemp);
  }
}
