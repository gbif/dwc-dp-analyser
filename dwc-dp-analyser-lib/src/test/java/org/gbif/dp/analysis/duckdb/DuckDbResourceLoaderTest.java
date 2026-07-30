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
package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.common.io.FileSystemDataPackageSource;
import org.gbif.dp.descriptor.DialectDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sample-first, all_varchar-fallback behaviour in
 * {@link DuckDbResourceLoader#createResourceTempTable}.
 *
 * <p>Regression context: a full-file sniff ({@code sample_size=-1}) used to be forced on every
 * load to guarantee CSV loads never fail on a value the sampled sniffer guessed wrong about —
 * at the cost of reading every resource's bytes twice. These tests confirm the cheaper strategy
 * (try the default sampled auto-detection first, catch a Conversion Error, retry that one
 * resource with {@code all_varchar=true}) still guarantees a successful load, and that the
 * fallback is logged at debug (not warn) since it's expected, unremarkable behaviour on messy
 * real-world data.
 */
class DuckDbResourceLoaderTest {

  private final DuckDbResourceLoader loader = new DuckDbResourceLoader(new DuckDbDialectRenderer());
  private ListAppender<ILoggingEvent> logAppender;
  private Logger loaderLogger;

  @TempDir
  Path tempDir;

  @BeforeEach
  void attachLogAppender() {
    loaderLogger = (Logger) LoggerFactory.getLogger(DuckDbResourceLoader.class);
    loaderLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    loaderLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    loaderLogger.detachAppender(logAppender);
  }

  @Test
  void shouldLoadCleanlyOnFirstAttemptWithoutFallback() throws Exception {
    Files.writeString(tempDir.resolve("clean.csv"), "id,flag\n1,true\n2,false\n3,true\n");

    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
      loader.createResourceTempTable(
        connection,
        new FileSystemDataPackageSource(tempDir.resolve("datapackage.json")),
        "clean_resource",
        List.of("clean.csv"),
        DialectDescriptor.defaults());

      try (ResultSet rs = connection.createStatement()
        .executeQuery("SELECT count(*) FROM \"clean_resource\"")) {
        rs.next();
        assertEquals(3, rs.getInt(1));
      }
    }

    assertTrue(
      logAppender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Creating temporary table")),
      "should log the creation attempt at debug");
    assertFalse(
      logAppender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Conversion Error")),
      "a cleanly-typed resource should never trigger the all_varchar fallback");
  }

  @Test
  void shouldFallBackToAllVarcharWhenSampledAutoDetectionGuessesWrong() throws Exception {
    // A value that contradicts the sampled auto-detected type, placed well past DuckDB's
    // default sniff sample (20,480 rows), so the *first* attempt is guaranteed to fail with a
    // Conversion Error and only the all_varchar=true retry can succeed.
    StringBuilder csv = new StringBuilder("id,flag\n");
    for (int i = 0; i < 21000; i++) {
      csv.append(i).append(",true\n");
    }
    csv.append("21000,Not Reported\n");
    for (int i = 21001; i < 21100; i++) {
      csv.append(i).append(",false\n");
    }
    Files.writeString(tempDir.resolve("messy.csv"), csv.toString());

    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
      loader.createResourceTempTable(
        connection,
        new FileSystemDataPackageSource(tempDir.resolve("datapackage.json")),
        "messy_resource",
        List.of("messy.csv"),
        DialectDescriptor.defaults());

      try (ResultSet rs = connection.createStatement()
        .executeQuery("SELECT count(*) FROM \"messy_resource\"")) {
        rs.next();
        assertEquals(21100, rs.getInt(1), "every row must load, including the offending one");
      }
      try (ResultSet rs = connection.createStatement()
        .executeQuery("SELECT flag FROM \"messy_resource\" WHERE id = 21000")) {
        rs.next();
        assertEquals("Not Reported", rs.getString(1));
      }
    }

    boolean fallbackLogged = logAppender.list.stream().anyMatch(e ->
      e.getLevel() == Level.DEBUG
        && e.getFormattedMessage().contains("Conversion Error")
        && e.getFormattedMessage().contains("all_varchar"));
    assertTrue(fallbackLogged, "the fallback must be logged at debug, not warn/error");

    boolean anyWarnOrError = logAppender.list.stream()
      .anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    assertFalse(anyWarnOrError, "this is expected, unremarkable behaviour and must not log above debug");
  }
}
