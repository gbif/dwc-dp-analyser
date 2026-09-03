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
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class ForeignKeyCrossCheckerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final ForeignKeyCrossChecker checker = new ForeignKeyCrossChecker(Map.of());

  // Trimmed subset of the real event.json canonical schema: only what the FK check reads.
  private static final String CANONICAL_EVENT_SCHEMA = """
      {
        "name": "event",
        "foreignKeys": [
          {
            "fields": "parentEvent_fk",
            "predicate": "happened during",
            "reference": { "resource": "", "fields": "event_pk" }
          },
          {
            "fields": "eventProtocol_fk",
            "predicate": "followed",
            "reference": { "resource": "protocol", "fields": "protocol_pk" }
          }
        ],
        "weakForeignKeys": [
          {
            "fields": "eventConductedByID",
            "predicate": "conducted by",
            "reference": { "resource": "agent", "fields": "agentID" }
          }
        ]
      }
      """;

  @Test
  void shouldReportNoIssuesWhenDeclaredFieldsHaveMatchingForeignKeys() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Resource declares all participating fields (including event_pk, the target of the
    // self-reference) and expresses matching FKs for each — 2 strong, 1 weak.
    JsonNode resource = mapper.readTree("""
        {
          "name": "event",
          "schema": {
            "foreignKeys": [
              {
                "fields": "parentEvent_fk",
                "predicate": "happened during",
                "reference": { "resource": "", "fields": "event_pk" }
              },
              {
                "fields": "eventProtocol_fk",
                "predicate": "followed",
                "reference": { "resource": "protocol", "fields": "protocol_pk" }
              }
            ],
            "weakForeignKeys": [
              {
                "fields": "eventConductedByID",
                "predicate": "conducted by",
                "reference": { "resource": "agent", "fields": "agentID" }
              }
            ]
          }
        }
        """);

    Set<String> userFieldNames = Set.of("parentEvent_fk", "eventProtocol_fk", "eventConductedByID", "event_pk");
    Map<String, Set<String>> allResourceFieldNames = Map.of(
      "event", userFieldNames,
      "protocol", Set.of("protocol_pk"),
      "agent", Set.of("agentID"));

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.isEmpty(), "Expected no FK issues when declared fields have matching, valid FKs, got: " + issues);
  }

  @Test
  void shouldResolveEmptyReferenceResourceAsSelf() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Resource declares parentEvent_fk as a field but omits the corresponding FK entry entirely.
    JsonNode resource = mapper.readTree("""
        {
          "name": "event",
          "schema": {
            "foreignKeys": []
          }
        }
        """);

    Set<String> userFieldNames = Set.of("parentEvent_fk");
    Map<String, Set<String>> allResourceFieldNames = Map.of("event", userFieldNames);

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertEquals(1, issues.size(), "Expected exactly one missing-FK issue, got: " + issues);
    ValidationIssue issue = issues.get(0);
    assertEquals(DescriptorViolationType.FOREIGN_KEY_MISSING, issue.violationType());
    assertTrue(issue.message().contains("parentEvent_fk"), "Message should name the field: " + issue.message());
    assertTrue(issue.message().contains("self"),
               "Empty reference.resource should render as 'self', got: " + issue.message());
    assertTrue(issue.message().contains("happened during"), "Message should include the predicate: " + issue.message());
  }

  @Test
  void shouldNotRequireForeignKeyForFieldTheResourceDoesNotDeclare() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Resource declares NEITHER parentEvent_fk NOR eventProtocol_fk — it doesn't "have" either
    // relationship, so neither should be flagged as missing.
    JsonNode resource = mapper.readTree("""
        {
          "name": "event",
          "schema": { "foreignKeys": [] }
        }
        """);

    Set<String> userFieldNames = Set.of("eventDate", "locality"); // unrelated fields only
    Map<String, Set<String>> allResourceFieldNames = Map.of("event", userFieldNames);

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.isEmpty(),
               "No FK should be required when the resource doesn't declare the participating field(s): " + issues);
  }

  @Test
  void shouldRequireWeakForeignKeyWhenItsFieldIsDeclared() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Resource declares eventConductedByID (a weakForeignKeys field) but never expresses the
    // relationship — should be flagged the same way a strong FK would be.
    JsonNode resource = mapper.readTree("""
        {
          "name": "event",
          "schema": { "foreignKeys": [] }
        }
        """);

    Set<String> userFieldNames = Set.of("eventConductedByID");
    Map<String, Set<String>> allResourceFieldNames = Map.of("event", userFieldNames);

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertEquals(1, issues.size(), "Expected exactly one missing-FK issue for the weak relationship, got: " + issues);
    assertTrue(issues.get(0).message().contains("eventConductedByID"));
    assertTrue(issues.get(0).message().contains("conducted by"));
    assertTrue(issues.get(0).message().contains("agent"));
  }

  @Test
  void shouldTreatDifferentPredicatesToSameTargetAsDistinctRelationships() throws Exception {
    // Two canonical FKs on different fields to the SAME target resource ("protocol"), same
    // predicate here for simplicity — real distinguishing case is covered structurally by
    // identity including (fields, predicate, targetResource, targetFields) as a whole.
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Resource declares both participating fields (plus event_pk, the self-reference target)
    // but only expresses one of the two required relationships.
    JsonNode resource = mapper.readTree("""
        {
          "name": "event",
          "schema": {
            "foreignKeys": [
              {
                "fields": "parentEvent_fk",
                "predicate": "happened during",
                "reference": { "resource": "", "fields": "event_pk" }
              }
            ]
          }
        }
        """);

    Set<String> userFieldNames = Set.of("parentEvent_fk", "eventProtocol_fk", "event_pk");
    Map<String, Set<String>> allResourceFieldNames = Map.of("event", userFieldNames);

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertEquals(1, issues.size(), "Expected only the un-expressed relationship to be flagged, got: " + issues);
    assertTrue(issues.get(0).message().contains("eventProtocol_fk"));
  }

  @Test
  void shouldFlagForeignKeyReferencingUndeclaredOwnField() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Declares an FK on eventProtocol_fk without that field existing in the resource's own schema.
    JsonNode resource = mapper.readTree("""
      {
        "name": "event",
        "schema": {
          "foreignKeys": [
            {
              "fields": "eventProtocol_fk",
              "predicate": "followed",
              "reference": { "resource": "protocol", "fields": "protocol_pk" }
            }
          ]
        }
      }
      """);

    Set<String> userFieldNames = Set.of(); // eventProtocol_fk NOT declared
    Map<String, Set<String>> allResourceFieldNames = Map.of(
      "event", userFieldNames,
      "protocol", Set.of("protocol_pk"));

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FOREIGN_KEY_FIELD_NOT_DECLARED
                                          && i.message().contains("eventProtocol_fk")),
               "Expected FOREIGN_KEY_FIELD_NOT_DECLARED, got: " + issues);
  }

  @Test
  void shouldFlagForeignKeyReferencingUnknownTargetResource() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    JsonNode resource = mapper.readTree("""
      {
        "name": "event",
        "schema": {
          "foreignKeys": [
            {
              "fields": "eventProtocol_fk",
              "predicate": "followed",
              "reference": { "resource": "protocol", "fields": "protocol_pk" }
            }
          ]
        }
      }
      """);

    Set<String> userFieldNames = Set.of("eventProtocol_fk");
    Map<String, Set<String>> allResourceFieldNames = Map.of("event", userFieldNames); // "protocol" absent

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FK_UNKNOWN_REFERENCE_RESOURCE
                                          && i.message().contains("protocol")),
               "Expected FK_UNKNOWN_REFERENCE_RESOURCE, got: " + issues);
  }

  @Test
  void shouldFlagForeignKeyReferencingUndeclaredTargetField() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    JsonNode resource = mapper.readTree("""
      {
        "name": "event",
        "schema": {
          "foreignKeys": [
            {
              "fields": "eventProtocol_fk",
              "predicate": "followed",
              "reference": { "resource": "protocol", "fields": "protocol_pk" }
            }
          ]
        }
      }
      """);

    Set<String> userFieldNames = Set.of("eventProtocol_fk");
    Map<String, Set<String>> allResourceFieldNames = Map.of(
      "event", userFieldNames,
      "protocol", Set.of("someOtherField")); // protocol_pk NOT declared there

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FOREIGN_KEY_TARGET_FIELD_NOT_DECLARED
                                          && i.message().contains("protocol_pk")),
               "Expected FOREIGN_KEY_TARGET_FIELD_NOT_DECLARED, got: " + issues);
  }

  @Test
  void shouldFlagForeignKeyNotMatchingCanonicalSchema() throws Exception {
    JsonNode canonical = mapper.readTree(CANONICAL_EVENT_SCHEMA);

    // Right field, right target resource/field, WRONG predicate — canonical says "followed".
    JsonNode resource = mapper.readTree("""
      {
        "name": "event",
        "schema": {
          "foreignKeys": [
            {
              "fields": "eventProtocol_fk",
              "predicate": "used",
              "reference": { "resource": "protocol", "fields": "protocol_pk" }
            }
          ]
        }
      }
      """);

    Set<String> userFieldNames = Set.of("eventProtocol_fk");
    Map<String, Set<String>> allResourceFieldNames = Map.of(
      "event", userFieldNames,
      "protocol", Set.of("protocol_pk"));

    List<ValidationIssue> issues = checker.check(resource, canonical, userFieldNames, allResourceFieldNames, "event", "resources[event]");

    assertTrue(issues.stream().anyMatch(i ->
                                          i.violationType() == DescriptorViolationType.FOREIGN_KEY_NOT_IN_CANONICAL_SCHEMA
                                          && i.message().contains("used")),
               "Expected FOREIGN_KEY_NOT_IN_CANONICAL_SCHEMA for wrong predicate, got: " + issues);
  }
}
