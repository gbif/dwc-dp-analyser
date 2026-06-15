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

import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DwcDpTableSchemaValidatorTest {

  final DwcDpTableSchemaValidator validator = new DwcDpTableSchemaValidator();

  @TempDir Path tempDir;

  @Test
  void shouldReportMissingRequiredField() throws Exception {
    // occurrence requires occurrenceID and eventID
    Files.writeString(tempDir.resolve("occurrence.csv"), "occurrenceID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "occurrence",
            "path": "occurrence.csv",
            "schema": {
              "fields": [
                { "name": "occurrenceID", "type": "string" }
              ]
            }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    // eventID is required in occurrence — should be reported
    assertTrue(issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
          && i.message().contains("eventID")),
      "Expected REQUIRED_FIELD_MISSING for eventID, got: " + issues);
  }

  @Test
  void shouldReportFieldTypeMismatch() throws Exception {
    Files.writeString(tempDir.resolve("occurrence.csv"), "occurrenceID,eventID\n1,e1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "occurrence",
            "path": "occurrence.csv",
            "schema": {
              "fields": [
                { "name": "occurrenceID", "type": "integer" },
                { "name": "eventID",      "type": "string"  }
              ]
            }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    // occurrenceID canonical type is "string" — declaring "integer" is a mismatch
    assertTrue(issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
          && i.message().contains("occurrenceID")),
      "Expected FIELD_TYPE_MISMATCH for occurrenceID, got: " + issues);
  }

  @Test
  void shouldReportUnknownField() throws Exception {
    Files.writeString(tempDir.resolve("occurrence.csv"), "occurrenceID,eventID,myCustomField\n1,e1,x\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "occurrence",
            "path": "occurrence.csv",
            "schema": {
              "fields": [
                { "name": "occurrenceID",  "type": "string" },
                { "name": "eventID",       "type": "string" },
                { "name": "myCustomField", "type": "string" }
              ]
            }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.UNKNOWN_FIELD
          && i.message().contains("myCustomField")),
      "Expected UNKNOWN_FIELD for myCustomField, got: " + issues);
    assertEquals(ValidationIssue.Severity.INFO,
      issues.stream().filter(i -> i.violationType() == DescriptorViolationType.UNKNOWN_FIELD)
        .findFirst().orElseThrow().severity());
  }

  @Test
  void shouldNotApplyChecksToNonReservedResourceNames() throws Exception {
    Files.writeString(tempDir.resolve("custom.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "custom-table",
            "path": "custom.csv",
            "schema": { "fields": [{ "name": "id", "type": "integer" }] }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(issues.stream().noneMatch(i ->
        i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
          || i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
          || i.violationType() == DescriptorViolationType.UNKNOWN_FIELD),
      "No table-schema checks should fire for non-reserved resource names");
  }

  @Test
  void shouldAllowSeverityOverride() throws Exception {
    Files.writeString(tempDir.resolve("occurrence.csv"), "occurrenceID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "test",
          "resources": [{
            "name": "occurrence",
            "path": "occurrence.csv",
            "schema": {
              "fields": [{ "name": "occurrenceID", "type": "string" }]
            }
          }]
        }
        """);

    // Override REQUIRED_FIELD_MISSING to INFO
    DwcDpTableSchemaValidator lenientValidator = new DwcDpTableSchemaValidator(
      new com.fasterxml.jackson.databind.ObjectMapper(),
      java.util.Map.of(
        DescriptorViolationType.REQUIRED_FIELD_MISSING,
        ValidationIssue.Severity.INFO));

    List<ValidationIssue> issues = lenientValidator.validate(tempDir.resolve("datapackage.json"));

    issues.stream()
      .filter(i -> i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING)
      .forEach(i -> assertEquals(ValidationIssue.Severity.INFO, i.severity(),
        "REQUIRED_FIELD_MISSING should be demoted to INFO via override"));
  }
}
