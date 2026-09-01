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

import org.gbif.dp.common.io.FileSystemDataPackageSource;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FrictionlessDescriptorValidatorTest {

  final FrictionlessDescriptorValidator validator = new FrictionlessDescriptorValidator();

  @TempDir Path tempDir;

  private FileSystemDataPackageSource source(String descriptorJson) throws IOException {
    Files.writeString(tempDir.resolve("datapackage.json"), descriptorJson);
    return new FileSystemDataPackageSource(tempDir.resolve("datapackage.json"));
  }

  @Test
  void shouldErrorWhenFileNotFound() {
    // no datapackage.json written — descriptor path still points at the tempDir
    try (var src = new FileSystemDataPackageSource(tempDir.resolve("missing.json"))) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.isValid());
      assertFalse(result.hasDataAnalysis());
      assertTrue(hasCode(result, "DESCRIPTOR_NOT_FOUND"));
    }
  }

  @Test
  void shouldErrorOnInvalidJson() throws Exception {
    try (var src = source("{ not isValid json }")) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.isValid());
      assertFalse(result.hasDataAnalysis());
      assertTrue(hasCode(result, "INVALID_JSON"));
    }
  }

  @Test
  void shouldErrorWhenResourcesArrayMissing() throws Exception {
    try (var src = source("""
        { "name": "test" }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.hasDataAnalysis());
      assertTrue(hasCode(result, "MISSING_RESOURCES"));
    }
  }

  @Test
  void shouldErrorWhenResourcesArrayEmpty() throws Exception {
    try (var src = source("""
        { "name": "test", "resources": [] }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.hasDataAnalysis());
      assertTrue(hasCode(result, "MISSING_RESOURCES"));
    }
  }

  @Test
  void shouldWarnWhenNameMissing() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    try (var src = source("""
        { "resources": [{ "name": "data", "path": "data.csv" }] }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.hasDataAnalysis());
      assertTrue(hasCode(result, "MISSING_NAME"));
      assertEquals(ValidationIssue.Severity.WARNING, severityOf(result, "MISSING_NAME"));
    }
  }

  @Test
  void shouldErrorWhenResourcePathMissing() throws Exception {
    try (var src = source("""
        {
          "name": "test",
          "resources": [{ "name": "data", "path": "nonexistent.csv" }]
        }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.isValid());
      assertTrue(hasCode(result, "PATH_NOT_FOUND"));
    }
  }

  @Test
  void shouldErrorOnPathTraversal() throws Exception {
    try (var src = source("""
        {
          "name": "test",
          "resources": [{ "name": "data", "path": "../../etc/passwd" }]
        }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertFalse(result.isValid());
      assertTrue(hasCode(result, "PATH_NOT_FOUND"));
    }
  }

  @Test
  void shouldWarnOnUnknownFieldType() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    try (var src = source("""
        {
          "name": "test",
          "resources": [{
            "name": "data",
            "path": "data.csv",
            "schema": { "fields": [{ "name": "id", "type": "uuid" }] }
          }]
        }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.hasDataAnalysis());
      assertTrue(hasCode(result, "UNKNOWN_FIELD_TYPE"));
    }
  }

  @Test
  void shouldWarnOnFkReferenceToUnknownResource() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    try (var src = source("""
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
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.hasDataAnalysis());
      assertTrue(hasCode(result, "FK_UNKNOWN_REFERENCE_RESOURCE"));
    }
  }

  @Test
  void shouldPassCleanDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id,score\n1,3.14\n");
    try (var src = source("""
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
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.isValid());
      assertTrue(result.hasDataAnalysis());
      assertTrue(result.issues().isEmpty());
    }
  }

  private boolean hasCode(DescriptorValidationResult r, String code) {
    return r.issues().stream().anyMatch(i -> i.code().equals(code));
  }

  private ValidationIssue.Severity severityOf(DescriptorValidationResult r, String code) {
    return r.issues().stream().filter(i -> i.code().equals(code))
      .map(ValidationIssue::severity).findFirst().orElseThrow();
  }
}
