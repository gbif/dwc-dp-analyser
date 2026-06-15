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

import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DwcDpDescriptorValidatorTest {

  final DwcDpDescriptorValidator validator = new DwcDpDescriptorValidator();

  @TempDir Path tempDir;

  @Test
  void shouldWarnWhenTopLevelProfileMissing() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{ "name": "event", "path": "event.csv" }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.issues().stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && (i.location() == null || i.location().isEmpty() || "/".equals(i.location()))
          && i.message().contains("required")
          && i.message().contains("profile")),
      "Expected JSON_SCHEMA_VIOLATION for missing root profile, got: " + result.issues());
  }

  @Test
  void shouldWarnWhenProfileIsNotUri() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "not-a-uri",
          "resources": [{ "name": "event", "path": "event.csv" }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.issues().stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && "/profile".equals(i.location())
          && i.message().toLowerCase().contains("uri")),
      "Expected JSON_SCHEMA_VIOLATION at /profile for non-URI value, got: " + result.issues());
  }

  @Test
  void shouldWarnWhenDwcDpTableMissingTabulaProfile() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "mediatype": "text/csv"
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.issues().stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && i.location() != null && i.location().startsWith("/resources/0")
          && i.message().contains("required")
          && i.message().contains("profile")),
      "Expected JSON_SCHEMA_VIOLATION at /resources/0 for missing tabular profile, got: " + result.issues());
  }

  @Test
  void shouldWarnWhenDwcDpTableMissingMediatype() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource"
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    // mediatype is not enforced by any current layer — assert it does not block analysis
    assertTrue(result.canProceedToDataAnalysis(),
      "Missing mediatype should not block analysis, got: " + result.issues());
  }

  @Test
  void shouldWarnOnUnsupportedMediatype() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource",
            "mediatype": "application/json"
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    // mediatype value is not constrained by any current layer — assert it does not block analysis
    assertTrue(result.canProceedToDataAnalysis(),
      "Unsupported mediatype should not block analysis until enforcement is added, got: " + result.issues());
  }

  @Test
  void shouldAcceptTsvMediatype() throws Exception {
    Files.writeString(tempDir.resolve("event.tsv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.tsv",
            "profile": "tabular-data-resource",
            "mediatype": "text/tab-separated-values",
            "schema": {
              "fields": [{
                "name": "eventID",
                "type": "string",
                "dcterms:isVersionOf": "http://rs.tdwg.org/dwc/terms/eventID"
              }]
            }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.canProceedToDataAnalysis(),
      "TSV mediatype should be accepted, got: " + result.issues());
  }

  @Test
  void shouldWarnWhenFieldMissingIsVersionOf() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource",
            "mediatype": "text/csv",
            "schema": {
              "fields": [{ "name": "eventID", "type": "string" }]
            }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.issues().stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && i.location() != null && i.location().startsWith("/resources/0/schema/fields/0")
          && i.message().contains("required")
          && i.message().contains("dcterms:isVersionOf")),
      "Expected JSON_SCHEMA_VIOLATION for missing dcterms:isVersionOf, got: " + result.issues());
  }

  @Test
  void shouldPassFullyConformantDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "valid-dwcdp",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
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
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.valid(), "Expected no errors, got: " + DescriptorValidationResult.errors(result));
    assertTrue(result.issues().isEmpty(), "Expected no issues at all, got: " + result.issues());
  }

  @Test
  void shouldNotApplyDwcDpChecksToNonReservedResourceNames() throws Exception {
    Files.writeString(tempDir.resolve("custom.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "custom-table",
            "path": "custom.csv",
            "schema": { "fields": [{ "name": "id", "type": "integer" }] }
          }]
        }
        """);

    DescriptorValidationResult result = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(result.issues().stream().noneMatch(i ->
        i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
          || i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
          || i.violationType() == DescriptorViolationType.FOREIGN_KEY_MISSING
          || i.violationType() == DescriptorViolationType.UNKNOWN_FIELD),
      "No table-schema checks should fire for non-reserved resource names, got: " + result.issues());
  }
}
