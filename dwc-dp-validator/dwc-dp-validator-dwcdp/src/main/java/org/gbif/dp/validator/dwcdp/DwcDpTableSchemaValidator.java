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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Layer 2: cross-validates each DwC-DP named resource in a datapackage.json's content against
 * the canonical table schema bundled for one profile version in {@code dwc-dp-schemas}.
 *
 * <p>The FK/required-field cross-check logic itself does not vary by version — it reads
 * {@code constraints.required} generically off whichever canonical schema is loaded, and
 * silently no-ops on {@code weakForeignKeys}/{@code weakPrimaryKey} for versions that don't
 * declare them. Only {@code classpathBase} (where {@code index.json} and
 * {@code table-schemas/} live) is version-specific, hence the constructor parameter.
 *
 * <p>Still operates on the raw {@link JsonNode} tree, not the {@code org.gbif.dp.descriptor}
 * model — migrating this onto {@code ResourceDescriptor}/{@code FieldDescriptor.constraints()}
 * is real follow-up work, deliberately not folded into this pass.
 */
public class DwcDpTableSchemaValidator {

  private static final Logger log = LoggerFactory.getLogger(DwcDpTableSchemaValidator.class);

  private static final String LEGACY_CLASSPATH_BASE = "/schemas/0.1";

  private record FieldCheck(String name, DescriptorViolationType violationType) {}

  private static final List<FieldCheck> REQUIRED_FIELD_CHECKS = List.of(
    new FieldCheck("type", DescriptorViolationType.FIELD_TYPE_MISMATCH)
  );

  private static final List<FieldCheck> OPTIONAL_FIELD_CHECKS = List.of(
    new FieldCheck("title", DescriptorViolationType.FIELD_DEFINITION_MISMATCH),
    new FieldCheck("description", DescriptorViolationType.FIELD_DEFINITION_MISMATCH),
    // new FieldCheck("comments", DescriptorViolationType.FIELD_MISMATCH),
    // new FieldCheck("examples", DescriptorViolationType.FIELD_MISMATCH),
    new FieldCheck("format", DescriptorViolationType.FIELD_DEFINITION_MISMATCH),
    new FieldCheck("namespace", DescriptorViolationType.FIELD_DEFINITION_MISMATCH),
    // new FieldCheck("rdfs:comment", DescriptorViolationType.FIELD_MISMATCH)
    new FieldCheck("dcterms:isVersionOf", DescriptorViolationType.FIELD_DEFINITION_MISMATCH),
    new FieldCheck("dcterms:references", DescriptorViolationType.FIELD_DEFINITION_MISMATCH)
  );

  private final ObjectMapper mapper;
  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;
  private final String indexClasspath;
  private final String schemaBase;

  /** Defaults to the 0.1 table schemas — legacy single-version behavior. */
  public DwcDpTableSchemaValidator() {
    this(new ObjectMapper(), Map.of(), LEGACY_CLASSPATH_BASE);
  }

  /** Defaults to the 0.1 table schemas — legacy single-version behavior. */
  public DwcDpTableSchemaValidator(
    ObjectMapper mapper,
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this(mapper, severityOverrides, LEGACY_CLASSPATH_BASE);
  }

  /** For multi-version delegation: {@code classpathBase} identifies which version's schemas to load. */
  public DwcDpTableSchemaValidator(
    ObjectMapper mapper,
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides,
    String classpathBase) {
    this.mapper = mapper;
    this.severityOverrides = severityOverrides;
    this.indexClasspath = classpathBase + "/index.json";
    this.schemaBase = classpathBase + "/table-schemas/";
  }

  /**
   * Cross-validate DwC-DP named resources in descriptor content against this instance's
   * canonical table schemas.
   *
   * @param descriptorContent datapackage.json content (already confirmed parseable by caller)
   * @return list of issues found (empty if all named resources conform)
   */
  public List<ValidationIssue> validate(String descriptorContent) {
    List<ValidationIssue> issues = new ArrayList<>();

    Map<String, String> nameToSchemaPath = loadIndex(issues);
    if (nameToSchemaPath == null) {
      return issues;
    }

    JsonNode root;
    try {
      root = mapper.readTree(descriptorContent);
    } catch (Exception e) {
      log.warn("Unexpected re-parse failure in table schema validator: {}", e.getMessage());
      return issues;
    }

    JsonNode resourcesNode = root.path("resources");
    if (!resourcesNode.isArray()) return issues;

    for (int i = 0; i < resourcesNode.size(); i++) {
      JsonNode resource = resourcesNode.get(i);
      String name = resource.path("name").asText("").trim();
      String loc = "resources[" + name + "]";

      String schemaPath = nameToSchemaPath.get(name);
      if (schemaPath == null) continue;

      JsonNode canonicalSchema = loadTableSchema(schemaPath, name, loc, issues);
      if (canonicalSchema == null) continue;

      crossCheck(resource, canonicalSchema, name, loc, issues);
    }

    return issues;
  }

  private Map<String, String> loadIndex(List<ValidationIssue> issues) {
    try (InputStream is = getClass().getResourceAsStream(indexClasspath)) {
      if (is == null) {
        log.warn("DwC-DP index not found at classpath:{}", indexClasspath);
        issues.add(issue(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE,
                         "DwC-DP index.json not found on classpath — table schema cross-validation skipped.",
                         null));
        return null;
      }

      JsonNode index = mapper.readTree(is);
      Map<String, String> result = new HashMap<>();

      JsonNode tableSchemas = index.path("tableSchemas");
      if (tableSchemas.isArray()) {
        for (JsonNode entry : tableSchemas) {
          String name = entry.path("name").asText("").trim();
          String url = entry.path("url").asText("").trim();
          if (!name.isBlank() && !url.isBlank()) {
            String classpathEntry = schemaBase + url.replaceFirst("^table-schemas/", "");
            result.put(name, classpathEntry);
          }
        }
      }

      log.debug("Loaded DwC-DP index: {} table schemas", result.size());
      return result;
    } catch (IOException e) {
      log.warn("Failed to read DwC-DP index: {}", e.getMessage());
      issues.add(issue(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE,
                       "Failed to read DwC-DP index.json: " + e.getMessage(), null));
      return null;
    }
  }

  private JsonNode loadTableSchema(String classpathEntry, String name, String loc,
                                   List<ValidationIssue> issues) {
    try (InputStream is = getClass().getResourceAsStream(classpathEntry)) {
      if (is == null) {
        issues.add(issue(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE,
                         "Canonical table schema not found for '" + name + "' at classpath:" + classpathEntry,
                         loc));
        return null;
      }
      return mapper.readTree(is);
    } catch (IOException e) {
      issues.add(issue(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE,
                       "Failed to read canonical table schema for '" + name + "': " + e.getMessage(), loc));
      return null;
    }
  }

  private void crossCheck(JsonNode resource, JsonNode canonicalSchema,
                          String resourceName, String loc, List<ValidationIssue> issues) {

    List<ValidationIssue> duplicateFields = checkDuplicateNames(resource.path("schema"), resourceName, loc);
    issues.addAll(duplicateFields);

    Map<String, JsonNode> canonicalFields = getFieldMap(canonicalSchema);
    Map<String, JsonNode> userFields = getFieldMap(resource.path("schema"));

    List<ValidationIssue> crossValidationIssues = crossCheckNames(resourceName, loc, canonicalFields, userFields);
    issues.addAll(crossValidationIssues);

    for (Map.Entry<String, JsonNode> entry : userFields.entrySet()) {
      String fieldName = entry.getKey();
      JsonNode userField = entry.getValue();

      JsonNode canonical = canonicalFields.get(fieldName);
      if (canonical == null) {
        issues.add(issue(DescriptorViolationType.UNKNOWN_FIELD,
                         "Field '" + fieldName + "' in resource '" + resourceName
                         + "' is not in the canonical DwC-DP table schema.",
                         loc + ".schema.fields[" + fieldName + "]"));
        continue;
      }

      for (FieldCheck check : REQUIRED_FIELD_CHECKS) {
        Optional<ValidationIssue> missing = checkPresence(userField, check.name(),
                                                          name -> createMissingFieldIssue(fieldName, name, resourceName, loc));
        if (missing.isPresent()) {
          issues.add(missing.get());
          continue; // fail early
        }
        checkEquality(userField, canonical, check.name(),
                      (userValue, canonicalValue) -> createFieldIssue(fieldName, check.name(), resourceName, userValue, canonicalValue, loc, check.violationType()))
          .ifPresent(issues::add);
      }

      for (FieldCheck check : OPTIONAL_FIELD_CHECKS) {
        checkEquality(userField, canonical, check.name(),
                      (userValue, canonicalValue) -> createFieldIssue(fieldName, check.name(), resourceName, userValue, canonicalValue, loc, check.violationType()))
          .ifPresent(issues::add);
      }

      crossCheckForeignKeys(resource, canonicalSchema, resourceName, loc, issues);
    }

    crossCheckForeignKeys(resource, canonicalSchema, resourceName, loc, issues);
  }
  private ValidationIssue createFieldIssue(String fieldName, String definition, String resourceName,
                                           String userValue, String canonicalValue, String loc, DescriptorViolationType violationType) {
    return issue(violationType,
                 "Field '" + fieldName + "' in resource '" + resourceName
                 + "' declares '" + definition + "': '" + userValue
                 + "' but canonical schema expects '" + canonicalValue + "'.",
                 loc + ".schema.fields[" + fieldName + "]" + definition);
  }

  private ValidationIssue createMissingFieldIssue(String fieldName, String definition, String resourceName, String loc) {
    return issue(DescriptorViolationType.FIELD_DEFINITION_MISSING, // ← swap in your actual enum constant, guessed here
                 "Field '" + fieldName + "' in resource '" + resourceName
                 + "' is missing required definition '" + definition + "'.",
                 loc + ".schema.fields[" + fieldName + "]" + definition);
  }

  private Optional<ValidationIssue> checkPresence(JsonNode userField, String name, Function<String, ValidationIssue> issueMapper) {
    JsonNode value = userField.path(name);
    if (value.isMissingNode() || value.isNull()) {
      return Optional.of(issueMapper.apply(name));
    }
    return Optional.empty();
  }

  private Optional<ValidationIssue> checkEquality(JsonNode userField, JsonNode canonicalField, String name, BiFunction<String, String, ValidationIssue> issueMapper) {
    String userValue = userField.path(name).asText("").trim();
    String canonicalValue = canonicalField.path(name).asText("").trim();
    if (!userValue.equals(canonicalValue)) {
      return Optional.of(issueMapper.apply(name, userValue));
    }
    return Optional.empty();
  }

  private List<ValidationIssue> crossCheckNames(
    String resourceName,
    String loc,
    Map<String, JsonNode> canonicalFields,
    Map<String, JsonNode> userFields
  ) {
    List<ValidationIssue> crossValidationIssues = new ArrayList<>();
    for (Map.Entry<String, JsonNode> entry : canonicalFields.entrySet()) {
      String fieldName = entry.getKey();
      JsonNode canonical = entry.getValue();
      boolean required = canonical.path("constraints").path("required").asBoolean(false);

      if (required && !userFields.containsKey(fieldName)) {
        crossValidationIssues.add(issue(DescriptorViolationType.REQUIRED_FIELD_MISSING,
                                        "Required field '" + fieldName + "' is missing from resource '" + resourceName
                                        + "'.",
                                        loc + ".schema.fields"));
      }
    }
    return crossValidationIssues;
  }

  private List<ValidationIssue> checkDuplicateNames(JsonNode schema, String resourceName, String loc) {
    Set<String> fields = new HashSet<>();
    Map<String, ValidationIssue> issues = new HashMap<>();
    JsonNode FieldNodes = schema.path("fields");
    if (FieldNodes.isArray()) {
      for (JsonNode f : FieldNodes) {
        String name = f.path("name").asText("").trim();
        if (!name.isBlank()) {
          if (fields.contains(name)) {
            issues.putIfAbsent(name, (issue(DescriptorViolationType.FIELD_DUPLICATE,
                             "Field '" + name + "' is duplicated in resource '" + resourceName + "'.",
                             loc + ".schema.fields[" + name + "]")));
          }
          fields.add(name);
        }
      }
    }
    return List.copyOf(issues.values());
  }

  private static Map<String, JsonNode> getFieldMap(JsonNode canonicalSchema) {
    Map<String, JsonNode> fields = new HashMap<>();
    JsonNode fieldNodes = canonicalSchema.path("fields");
    if (fieldNodes.isArray()) {
      for (JsonNode field : fieldNodes) {
        String name = field.path("name").asText("").trim();
        if (!name.isBlank()) fields.put(name, field);
      }
    }
    return fields;
  }

  private void crossCheckForeignKeys(JsonNode resource, JsonNode canonicalSchema,
                                     String resourceName, String loc, List<ValidationIssue> issues) {

    JsonNode canonicalFks = canonicalSchema.path("foreignKeys");
    if (!canonicalFks.isArray()) return;

    Set<String> requiredFields = new HashSet<>();
    JsonNode canonicalFields = canonicalSchema.path("fields");
    if (canonicalFields.isArray()) {
      for (JsonNode f : canonicalFields) {
        if (f.path("constraints").path("required").asBoolean(false)) {
          requiredFields.add(f.path("name").asText("").trim());
        }
      }
    }

    JsonNode userFks = resource.path("schema").path("foreignKeys");
    List<String> userFkFields = new ArrayList<>();
    if (userFks.isArray()) {
      for (JsonNode fk : userFks) {
        JsonNode fields = fk.path("fields");
        if (fields.isTextual()) {
          userFkFields.add(fields.asText().trim());
        } else if (fields.isArray()) {
          fields.forEach(f -> userFkFields.add(f.asText().trim()));
        }
      }
    }

    for (JsonNode canonicalFk : canonicalFks) {
      JsonNode fieldsNode = canonicalFk.path("fields");
      String fkField = fieldsNode.isTextual()
        ? fieldsNode.asText().trim()
        : (fieldsNode.isArray() && !fieldsNode.isEmpty()
           ? fieldsNode.get(0).asText().trim() : "");

      if (fkField.isBlank()) continue;
      if (!requiredFields.contains(fkField)) continue;

      if (!userFkFields.contains(fkField)) {
        String refResource = canonicalFk.path("reference").path("resource").asText("self");
        issues.add(issue(DescriptorViolationType.FOREIGN_KEY_MISSING,
                         "Foreign key on field '" + fkField + "' -> '" + refResource
                         + "' is declared in the canonical schema for '" + resourceName
                         + "' but not in the descriptor.",
                         loc + ".schema.foreignKeys"));
      }
    }
  }

  private ValidationIssue issue(DescriptorViolationType type, String message, String location) {
    return ValidationIssue.of(type, message, location, severityOverrides);
  }
}
