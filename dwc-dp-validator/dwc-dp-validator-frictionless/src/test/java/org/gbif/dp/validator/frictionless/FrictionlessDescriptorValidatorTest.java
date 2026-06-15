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
package org.gbif.dp.validator.frictionless;

import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FrictionlessDescriptorValidatorTest {

  final FrictionlessDescriptorValidator validator = new FrictionlessDescriptorValidator();

  @TempDir Path tempDir;

  @Test
  void shouldErrorWhenFileNotFound() {
    DescriptorValidationResult result = validator.validate(tempDir.resolve("missing.json"));

    assertFalse(result.valid());
    assertFalse(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "DESCRIPTOR_NOT_FOUND"));
  }

  @Test
  void shouldErrorOnInvalidJson() throws Exception {
    Files.writeString(tempDir.resolve("datapackage.json"), "{ not valid json }");

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertFalse(result.valid());
    assertFalse(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "INVALID_JSON"));
  }

  @Test
  void shouldErrorWhenResourcesArrayMissing() throws Exception {
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "test" }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertFalse(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "MISSING_RESOURCES"));
  }

  @Test
  void shouldErrorWhenResourcesArrayEmpty() throws Exception {
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "name": "test", "resources": [] }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertFalse(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "MISSING_RESOURCES"));
  }

  @Test
  void shouldWarnWhenNameMissing() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        { "resources": [{ "name": "data", "path": "data.csv" }] }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "MISSING_NAME"));
    assertEquals(ValidationIssue.Severity.WARNING, severityOf(result, "MISSING_NAME"));
  }

  @Test
  void shouldErrorWhenResourcePathMissing() throws Exception {
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{ "name": "data", "path": "nonexistent.csv" }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertFalse(result.valid());
    assertTrue(hasCode(result, "PATH_NOT_FOUND"));
  }

  @Test
  void shouldWarnOnUnknownFieldType() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "data",
            "path": "data.csv",
            "schema": { "fields": [{ "name": "id", "type": "uuid" }] }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "UNKNOWN_FIELD_TYPE"));
  }

  @Test
  void shouldWarnOnFkReferenceToUnknownResource() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "data",
            "path": "data.csv",
            "schema": {
              "foreignKeys": [{
                "fields": ["id"],
                "reference": { "resource": "ghost", "fields": ["id"] }
              }]
            }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.canProceedToDataAnalysis());
    assertTrue(hasCode(result, "FK_UNKNOWN_REFERENCE_RESOURCE"));
  }

  @Test
  void shouldPassCleanDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "clean",
          "resources": [{
            "name": "data",
            "path": "data.csv",
            "schema": {
              "fields": [
                { "name": "id",    "type": "integer" },
                { "name": "score", "type": "number"  }
              ]
            }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.valid());
    assertTrue(result.canProceedToDataAnalysis());
    assertTrue(result.issues().isEmpty());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private boolean hasCode(DescriptorValidationResult r, String code) {
    return r.issues().stream().anyMatch(i -> i.code().equals(code));
  }

  private ValidationIssue.Severity severityOf(DescriptorValidationResult r, String code) {
    return r.issues().stream().filter(i -> i.code().equals(code))
      .map(ValidationIssue::severity).findFirst().orElseThrow();
  }
}
