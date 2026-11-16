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

import org.gbif.dp.validator.dwcdp.SchemaFetchPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCliTest {

  private static final String DWC_DP_0_1_PROFILE = "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json";

  @TempDir Path tempDir;
  private PrintStream originalOut;
  private ByteArrayOutputStream capturedOut;

  @BeforeEach
  void captureStdout() {
    originalOut = System.out;
    capturedOut = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
  }

  @AfterEach
  void restoreStdout() {
    System.setOut(originalOut);
  }

  private static SchemaFetchPolicy policyFor(String... args) {
    Config config = new Config();
    new CommandLine(config).parseArgs(args);
    return ValidationCli.schemaFetchPolicy(config);
  }

  @Test
  void schemaPolicyDefaultsToBuiltInAllowlist() {
    SchemaFetchPolicy policy = policyFor("datapackage.json");
    assertTrue(policy.allows(URI.create("https://rs.tdwg.org/dwc-dp/2.0/dwc-dp-profile.json")));
    assertFalse(policy.allows(URI.create("https://mirror.example.org/dwc-dp-profile.json")));
  }

  @Test
  void schemaHostFlagAddsToDefaults() {
    SchemaFetchPolicy policy =
      policyFor("--schema-host", "mirror.example.org", "--schema-host", "a.example,b.example", "datapackage.json");
    assertTrue(policy.allows(URI.create("https://rs.tdwg.org/dwc-dp/2.0/dwc-dp-profile.json")));
    assertTrue(policy.allows(URI.create("https://mirror.example.org/dwc-dp-profile.json")));
    assertTrue(policy.allows(URI.create("https://b.example/dwc-dp-profile.json")));
    assertFalse(policy.allows(URI.create("http://mirror.example.org/dwc-dp-profile.json")));
    assertFalse(policy.allows(URI.create("https://other.example/dwc-dp-profile.json")));
  }

  @Test
  void offlineSchemasFlagBlocksEverything() {
    SchemaFetchPolicy policy = policyFor("--offline-schemas", "--schema-host", "mirror.example.org", "datapackage.json");
    assertFalse(policy.allows(URI.create("https://rs.tdwg.org/dwc-dp/2.0/dwc-dp-profile.json")));
    assertFalse(policy.allows(URI.create("https://mirror.example.org/dwc-dp-profile.json")));
  }

  @Test
  void shouldExitZeroAndPrintPassedForValidDataset() throws Exception {
    Path tempDir = setupSmallValidDataset();

    int exitCode = ValidationCli.run(new String[]{ tempDir.resolve("datapackage.json").toString() });

    assertEquals(0, exitCode);
    assertTrue(capturedOut.toString().contains("Result: VALID"));
  }

  @Test
  void shouldExitTwoForDataTypeViolations() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,notanumber\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
            {
              "name": "invalid",
              "profile": "%s",
              "resources": [{ "name": "data", "path": "data.csv",
                "schema": { "fields": [
                  { "name": "id",    "type": "integer" },
                  { "name": "score", "type": "number"  }
                ]}}]
            }
            """.formatted(DWC_DP_0_1_PROFILE));

    int exitCode = ValidationCli.run(new String[]{ tempDir.resolve("datapackage.json").toString() });

    assertEquals(2, exitCode);
    assertTrue(capturedOut.toString().contains("Type violation"));
    assertTrue(capturedOut.toString().contains("Result: INVALID"));
  }

  @Test
  void shouldExitOneForMissingArgs() throws Exception {
    int exitCode = ValidationCli.run(new String[]{});

    assertEquals(1, exitCode);
  }

  @Test
  void shouldOutputValidJsonForValidDataset() throws Exception {
    Path tempDir = setupSmallValidDataset();

    int exitCode = ValidationCli.run(new String[]{
      tempDir.resolve("datapackage.json").toString(),
      "--output-format", "JSON"
    });

    assertEquals(0, exitCode);
    String output = capturedOut.toString();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode json = mapper.readTree(output); // throws if not isValid JSON
    assertTrue(json.has("result"));
    assertTrue(json.has("durationSeconds"));
    assertTrue(json.has("isValid"));
    assertTrue(json.get("isValid").asBoolean());
  }

  @Test
  void shouldIncludeViolationsInJsonOutput() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,asd\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
            {
              "name": "invalid",
              "profile": "%s",
              "resources": [{ "name": "data", "path": "data.csv",
                "schema": { "fields": [
                  { "name": "id",    "type": "integer" },
                  { "name": "score", "type": "number"  }
                ]}}]
            }
            """.formatted(DWC_DP_0_1_PROFILE));

    int exitCode = ValidationCli.run(new String[]{
      tempDir.resolve("datapackage.json").toString(),
      "--output-format", "JSON"
    });

    assertEquals(2, exitCode);
    JsonNode json = new ObjectMapper().readTree(capturedOut.toString());
    assertFalse(json.get("result").path("isValid").asBoolean());
    assertFalse(json.get("result").path("dataTypeViolations").isNull());
  }

  private Path setupSmallValidDataset() throws IOException {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n2,2.71\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
            {
              "name": "clean",
              "profile": "%s",
              "resources": [{ "name": "data", "path": "data.csv",
                "schema": { "fields": [
                  { "name": "id",    "type": "integer" },
                  { "name": "score", "type": "number"  }
                ]}}]
            }
            """.formatted(DWC_DP_0_1_PROFILE));
    return tempDir;
  }
}
