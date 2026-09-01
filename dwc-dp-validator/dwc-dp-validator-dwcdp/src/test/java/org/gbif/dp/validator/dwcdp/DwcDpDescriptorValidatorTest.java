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
package org.gbif.dp.validator.dwcdp;

import org.gbif.dp.common.io.FileSystemDataPackageSource;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DwcDpDescriptorValidatorTest {

  final DwcDpDescriptorValidator validator = new DwcDpDescriptorValidator();

  @TempDir Path tempDir;

  private static final String PROFILE_0_1 = "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json";

  private FileSystemDataPackageSource source(String descriptorJson) throws IOException {
    Files.writeString(tempDir.resolve("datapackage.json"), descriptorJson);
    return new FileSystemDataPackageSource(tempDir.resolve("datapackage.json"));
  }

  @Test
  void shouldWarnWhenTopLevelProfileMissing() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    try (var src = source("""
        {
          "name": "test",
          "resources": [{ "name": "event", "path": "event.csv" }]
        }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION),
                 "Expected UNRECOGNIZED_PROFILE_VERSION for missing profile, got: " + result.issues());
    }
  }

  @Test
  void shouldWarnWhenProfileIsUnrecognized() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    try (var src = source("""
        {
          "name": "test",
          "profile": "not-a-uri",
          "resources": [{ "name": "event", "path": "event.csv" }]
        }
        """)) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION),
                 "Expected UNRECOGNIZED_PROFILE_VERSION for unrecognized profile, got: " + result.issues());
    }
  }

  @Test
  void shouldWarnWhenDwcDpTableMissingTabulaProfile() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    try (var src = source("""
        {
          "name": "test",
          "profile": "%s",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "mediatype": "text/csv"
          }]
        }
        """.formatted(PROFILE_0_1))) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION),
                 "Expected JSON_SCHEMA_VIOLATION for missing tabular profile, got: " + result.issues());
    }
  }

  @Test
  void shouldPassFullyConformantDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    try (var src = source("""
        {
          "name": "valid-dwcdp",
          "profile": "%s",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource",
            "mediatype": "text/csv",
            "schema": {
              "fields": [{
                "name": "eventID",
                "title": "Event ID",
                "description": "An identifier for a dwc:Event.",
                "type": "string",
                "format": "default",
                "dcterms:isVersionOf": "http://rs.tdwg.org/dwc/terms/eventID"
              }]
            }
          }]
        }
        """.formatted(PROFILE_0_1))) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.isValid(), "Expected no errors, got: " + DescriptorValidationResult.errors(result));
    }
  }

  @Test
  void shouldNotApplyDwcDpChecksToNonReservedResourceNames() throws Exception {
    Files.writeString(tempDir.resolve("custom.csv"), "id\n1\n");
    try (var src = source("""
        {
          "name": "test",
          "profile": "%s",
          "resources": [{
            "name": "custom-table",
            "path": "custom.csv",
            "schema": { "fields": [{ "name": "id", "type": "integer" }] }
          }]
        }
        """.formatted(PROFILE_0_1))) {
      DescriptorValidationResult result = validator.validate(src);
      assertTrue(result.issues().stream().noneMatch(i ->
                                                      i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
                                                      || i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
                                                      || i.violationType() == DescriptorViolationType.FOREIGN_KEY_MISSING
                                                      || i.violationType() == DescriptorViolationType.UNKNOWN_FIELD),
                 "No table-schema checks should fire for non-reserved resource names, got: " + result.issues());
    }
  }
}
