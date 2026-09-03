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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Cross-checks a resource's foreign keys ({@code foreignKeys} and {@code weakForeignKeys})
 * against the canonical DwC-DP table schema and the rest of the descriptor, per the DwC-DP
 * guide (dwc.tdwg.org/dp, §3.3(4)):
 *
 * <ol>
 *   <li><b>Required</b>: if the resource's own schema declares the field(s) participating in a
 *       canonical relationship — i.e. the table "has" that relationship — it MUST be expressed.
 *   <li><b>Valid</b>: any foreign key the resource DOES declare must (a) reference a field that
 *       exists in its own schema, (b) reference a target resource and target field that actually
 *       exist in the descriptor, and (c) match one of the relationships defined in the canonical
 *       schema — the spec says relationships not defined there MUST NOT be declared.
 * </ol>
 *
 * <p>{@code weakForeignKeys} is not part of the published spec (a local convention for
 * optional/inferred relationships) but is folded into both checks above identically to strong
 * {@code foreignKeys}.
 *
 * <p>A foreign key is identified by its (field(s), predicate, target resource, target field(s))
 * — DwC-DP allows multiple references to the same target distinguished only by predicate, so
 * identity must include the predicate.
 *
 * <p>Schema-level only: says nothing about whether actual data rows contain null/blank values
 * for these fields — that's a separate, data-level concern not handled here.
 */
class ForeignKeyCrossChecker {

  private static final String PREDICATE_FIELD_KEY = "predicate";

  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;

  ForeignKeyCrossChecker(Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.severityOverrides = severityOverrides;
  }

  private record ForeignKeyRef(List<String> fields, String predicate, String targetResourceRaw, List<String> targetFields) {

    /** Human-readable form for issue messages; an empty raw target resource is DwC-DP's "self" convention. */
    String describe() {
      String fieldPart = String.join("+", fields);
      String targetFieldPart = String.join("+", targetFields);
      String arrow = predicate.isBlank() ? "->" : "--(" + predicate + ")->";
      String targetLabel = targetResourceRaw.isBlank() ? "self" : targetResourceRaw;
      return fieldPart + " " + arrow + " " + targetLabel + "[" + targetFieldPart + "]";
    }

    /** Resolves "self" (empty reference.resource) to the actual resource name, for lookups. */
    String resolvedTargetResource(String selfResourceName) {
      return targetResourceRaw.isBlank() ? selfResourceName : targetResourceRaw;
    }
  }

  /**
   * @param userFieldNames        fields declared in this resource's own schema
   * @param allResourceFieldNames resource name -> declared field names, for every resource in the
   *                              descriptor — needed to validate a FK's target resource/field
   */
  List<ValidationIssue> check(JsonNode resource, JsonNode canonicalSchema, Set<String> userFieldNames,
                              Map<String, Set<String>> allResourceFieldNames,
                              String resourceName, String loc) {

    Set<ForeignKeyRef> canonicalFks = extractAll(canonicalSchema);
    Set<ForeignKeyRef> userFks = extractAll(resource.path("schema"));

    List<ValidationIssue> issues = new ArrayList<>();
    issues.addAll(checkRequiredRelationshipsAreDeclared(canonicalFks, userFks, userFieldNames, resourceName, loc));
    issues.addAll(checkDeclaredForeignKeysAreValid(userFks, canonicalFks, userFieldNames, allResourceFieldNames, resourceName, loc));
    return issues;
  }

  /** §3.3(4), first half: a table that "has" a canonical relationship (declares the field) must express it. */
  private List<ValidationIssue> checkRequiredRelationshipsAreDeclared(
    Set<ForeignKeyRef> canonicalFks, Set<ForeignKeyRef> userFks, Set<String> userFieldNames,
    String resourceName, String loc) {

    List<ValidationIssue> issues = new ArrayList<>();
    for (ForeignKeyRef canonicalFk : canonicalFks) {
      boolean tableHasRelationship = userFieldNames.containsAll(canonicalFk.fields());
      if (!tableHasRelationship) continue;
      if (userFks.contains(canonicalFk)) continue;

      issues.add(issue(DescriptorViolationType.FOREIGN_KEY_MISSING,
                       "Missing reference '" + canonicalFk.describe() + "' declared in the canonical schema for '"
                       + resourceName + "' but not found in the descriptor.",
                       loc + ".schema.foreignKeys"));
    }
    return issues;
  }

  /** §3.3(4), second half: any FK the resource declares must be internally consistent AND canonical. */
  private List<ValidationIssue> checkDeclaredForeignKeysAreValid(
    Set<ForeignKeyRef> userFks, Set<ForeignKeyRef> canonicalFks, Set<String> userFieldNames,
    Map<String, Set<String>> allResourceFieldNames, String resourceName, String loc) {

    List<ValidationIssue> issues = new ArrayList<>();

    for (ForeignKeyRef fk : userFks) {

      for (String field : fk.fields()) {
        if (!userFieldNames.contains(field)) {
          issues.add(issue(DescriptorViolationType.FOREIGN_KEY_FIELD_NOT_DECLARED,
                           "Foreign key '" + fk.describe() + "' in resource '" + resourceName
                           + "' references field '" + field + "' which is not declared in this resource's schema.",
                           loc + ".schema.foreignKeys"));
        }
      }

      String targetResource = fk.resolvedTargetResource(resourceName);
      Set<String> targetFieldNames = allResourceFieldNames.get(targetResource);
      if (targetFieldNames == null) {
        issues.add(issue(DescriptorViolationType.FK_UNKNOWN_REFERENCE_RESOURCE,
                         "Foreign key '" + fk.describe() + "' in resource '" + resourceName
                         + "' references resource '" + targetResource + "' which is not declared in this package.",
                         loc + ".schema.foreignKeys"));
      } else {
        for (String targetField : fk.targetFields()) {
          if (!targetFieldNames.contains(targetField)) {
            issues.add(issue(DescriptorViolationType.FOREIGN_KEY_TARGET_FIELD_NOT_DECLARED,
                             "Foreign key '" + fk.describe() + "' in resource '" + resourceName
                             + "' references field '" + targetField + "' in resource '" + targetResource
                             + "' which is not declared there.",
                             loc + ".schema.foreignKeys"));
          }
        }
      }

      if (!canonicalFks.contains(fk)) {
        issues.add(issue(DescriptorViolationType.FOREIGN_KEY_NOT_IN_CANONICAL_SCHEMA,
                         "Foreign key '" + fk.describe() + "' declared in resource '" + resourceName
                         + "' does not match any relationship in the canonical DwC-DP table schema.",
                         loc + ".schema.foreignKeys"));
      }
    }

    return issues;
  }

  private Set<ForeignKeyRef> extractAll(JsonNode schemaNode) {
    Set<ForeignKeyRef> refs = new LinkedHashSet<>(extractForeignKeys(schemaNode.path("foreignKeys")));
    refs.addAll(extractForeignKeys(schemaNode.path("weakForeignKeys")));
    return refs;
  }

  private Set<ForeignKeyRef> extractForeignKeys(JsonNode fksNode) {
    Set<ForeignKeyRef> refs = new LinkedHashSet<>();
    if (!fksNode.isArray()) return refs;

    for (JsonNode fk : fksNode) {
      List<String> fields = fieldNames(fk.path("fields"));
      if (fields.isEmpty()) continue;

      String predicate = fk.path(PREDICATE_FIELD_KEY).asText("").trim();
      JsonNode reference = fk.path("reference");
      String targetResourceRaw = reference.path("resource").asText("").trim();
      List<String> targetFields = fieldNames(reference.path("fields"));

      refs.add(new ForeignKeyRef(fields, predicate, targetResourceRaw, targetFields));
    }
    return refs;
  }

  /** Frictionless FK "fields" can be a single string or an array of strings (composite key) — normalizes to a list either way. */
  private List<String> fieldNames(JsonNode fieldsNode) {
    if (fieldsNode.isTextual()) {
      String value = fieldsNode.asText("").trim();
      return value.isBlank() ? List.of() : List.of(value);
    }
    if (fieldsNode.isArray()) {
      List<String> names = new ArrayList<>();
      fieldsNode.forEach(n -> {
        String value = n.asText("").trim();
        if (!value.isBlank()) names.add(value);
      });
      return names;
    }
    return List.of();
  }

  private ValidationIssue issue(DescriptorViolationType type, String message, String location) {
    return ValidationIssue.of(type, message, location, severityOverrides);
  }
}
