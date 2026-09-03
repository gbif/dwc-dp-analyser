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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the legacy no-arg / 2-arg constructors, which default to the 0.1 table schemas — see
 * {@link DwcDpTableSchemaValidator}'s class-level note on why the legacy path still exists.
 */
class DwcDpTableSchemaValidatorTest {

  final DwcDpTableSchemaValidator validator = new DwcDpTableSchemaValidator();

  @Test
  void shouldReportMissingRequiredField() {
    // occurrence requires occurrenceID and eventID
    String json = """
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
        """;

    List<ValidationIssue> issues = validator.validate(json);

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
                                          && i.message().contains("eventID")),
               "Expected REQUIRED_FIELD_MISSING for eventID, got: " + issues);
  }

  @Test
  void shouldReportFieldTypeMismatch() {
    String json = """
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
        """;

    List<ValidationIssue> issues = validator.validate(json);

    // occurrenceID canonical type is "string" — declaring "integer" is a mismatch
    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
                                          && i.message().contains("occurrenceID")),
               "Expected FIELD_TYPE_MISMATCH for occurrenceID, got: " + issues);
  }

  @Test
  void shouldReportForeignKeyMissingExactlyOnce() {
    // occurrence's canonical schema declares a required FK on eventID -> event;
    // this resource omits foreignKeys entirely, so it should be flagged once, not once per field.
    String json = """
      {
        "name": "test",
        "resources": [{
          "name": "occurrence",
          "path": "occurrence.csv",
          "schema": {
            "fields": [
              { "name": "occurrenceID", "type": "string" },
              { "name": "eventID",      "type": "string" }
            ]
          }
        }]
      }
      """;

    List<ValidationIssue> issues = validator.validate(json);

    List<ValidationIssue> fkIssues = issues.stream()
      .filter(i -> i.violationType() == DescriptorViolationType.FOREIGN_KEY_MISSING)
      .toList();

    assertEquals(1, fkIssues.size(),
                 "Expected exactly one FOREIGN_KEY_MISSING issue for eventID, got: " + fkIssues);
    assertTrue(fkIssues.get(0).message().contains("eventID"));
  }

  @Test
  void shouldReportDuplicateField() {
    String json = """
      {
        "name": "test",
        "resources": [{
          "name": "occurrence",
          "path": "occurrence.csv",
          "schema": {
            "fields": [
              { "name": "occurrenceID",     "type": "integer" },
              { "name": "eventID",          "type": "string"  },
              { "name": "organismQuantity", "type": "string"  },
              { "name": "organismQuantity", "type": "string"  }
            ]
           }
        }]
      }
      """;

    List<ValidationIssue> issues = validator.validate(json);

    assertTrue(issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.FIELD_DUPLICATE
        && i.message().contains("organismQuantity")),
               "Expected FIELD_DUPLICATE for 'organismQuantity', got: " + issues);
  }

  @Test
  void shouldMarkIssueWithUserValueAndCanonicalValueOnMismatch() {
    String json = """
        {
          "name": "test",
          "resources": [{
            "name": "occurrence",
            "path": "occurrence.csv",
            "schema": {
              "fields": [
                { "name": "occurrenceID",  "type": "string" },
                { "name": "eventID",       "type": "string" },
                { "name": "organismQuantity", "title": "My Own Title", "type": "string" }
              ]
            }
          }]
        }
        """;

    List<ValidationIssue> issues = validator.validate(json);

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FIELD_DEFINITION_MISMATCH
                                          && i.message().contains("'title': 'My Own Title'")
                                          && !i.message().contains("expects: 'My Own Title'")
                                          && i.message().contains("expects 'Organism Quantity'")),
               "Expected " + DescriptorViolationType.FIELD_DEFINITION_MISMATCH.name() + " for title value mismatch, got: " + issues);
  }

  @Test
  void shouldReportUnknownField() {
    String json = """
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
        """;

    List<ValidationIssue> issues = validator.validate(json);

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.UNKNOWN_FIELD
                                          && i.message().contains("myCustomField")),
               "Expected UNKNOWN_FIELD for myCustomField, got: " + issues);
    assertEquals(ValidationIssue.Severity.INFO,
                 issues.stream().filter(i -> i.violationType() == DescriptorViolationType.UNKNOWN_FIELD)
                   .findFirst().orElseThrow().severity());
  }

  @Test
  void shouldNotApplyChecksToNonReservedResourceNames() {
    String json = """
        {
          "name": "test",
          "resources": [{
            "name": "custom-table",
            "path": "custom.csv",
            "schema": { "fields": [{ "name": "id", "type": "integer" }] }
          }]
        }
        """;

    List<ValidationIssue> issues = validator.validate(json);

    assertTrue(issues.stream().noneMatch(i ->
                                           i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING
                                           || i.violationType() == DescriptorViolationType.FIELD_TYPE_MISMATCH
                                           || i.violationType() == DescriptorViolationType.UNKNOWN_FIELD),
               "No table-schema checks should fire for non-reserved resource names");
  }

  @Test
  void shouldAllowSeverityOverride() {
    String json = """
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
        """;

    // Override REQUIRED_FIELD_MISSING to INFO
    DwcDpTableSchemaValidator lenientValidator = new DwcDpTableSchemaValidator(
      new ObjectMapper(),
      Map.of(DescriptorViolationType.REQUIRED_FIELD_MISSING, ValidationIssue.Severity.INFO));

    List<ValidationIssue> issues = lenientValidator.validate(json);

    issues.stream()
      .filter(i -> i.violationType() == DescriptorViolationType.REQUIRED_FIELD_MISSING)
      .forEach(i -> assertEquals(ValidationIssue.Severity.INFO, i.severity(),
                                 "REQUIRED_FIELD_MISSING should be demoted to INFO via override"));
  }

  @Test
  void shouldReportUnavailableWhenIndexMissingForClasspathBase() {
    DwcDpTableSchemaValidator validatorWithBadBase = new DwcDpTableSchemaValidator(
      new ObjectMapper(), Map.of(), "/schemas/does-not-exist");

    List<ValidationIssue> issues = validatorWithBadBase.validate("""
        { "name": "test", "resources": [] }
        """);

    assertEquals(1, issues.size());
    assertEquals(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE, issues.get(0).violationType());
  }
}
