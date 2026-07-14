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
import java.util.Set;

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
      String loc = "resources[" + i + "]";

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

    Map<String, JsonNode> canonicalFields = new HashMap<>();
    JsonNode canonicalFieldsNode = canonicalSchema.path("fields");
    if (canonicalFieldsNode.isArray()) {
      for (JsonNode f : canonicalFieldsNode) {
        String n = f.path("name").asText("").trim();
        if (!n.isBlank()) canonicalFields.put(n, f);
      }
    }

    Map<String, JsonNode> userFields = new HashMap<>();
    JsonNode userFieldsNode = resource.path("schema").path("fields");
    if (userFieldsNode.isArray()) {
      for (JsonNode f : userFieldsNode) {
        String n = f.path("name").asText("").trim();
        if (!n.isBlank()) userFields.put(n, f);
      }
    }

    for (Map.Entry<String, JsonNode> entry : canonicalFields.entrySet()) {
      String fieldName = entry.getKey();
      JsonNode canonical = entry.getValue();
      boolean required = canonical.path("constraints").path("required").asBoolean(false);

      if (required && !userFields.containsKey(fieldName)) {
        issues.add(issue(DescriptorViolationType.REQUIRED_FIELD_MISSING,
                         "Required field '" + fieldName + "' is missing from resource '" + resourceName + "'.",
                         loc + ".schema.fields"));
      }
    }

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

      String userType = userField.path("type").asText("string").trim().toLowerCase();
      String canonicalType = canonical.path("type").asText("string").trim().toLowerCase();
      if (!userType.equals(canonicalType)) {
        issues.add(issue(DescriptorViolationType.FIELD_TYPE_MISMATCH,
                         "Field '" + fieldName + "' in resource '" + resourceName
                         + "' declares type '" + userType
                         + "' but canonical schema expects '" + canonicalType + "'.",
                         loc + ".schema.fields[" + fieldName + "].type"));
      }
    }

    crossCheckForeignKeys(resource, canonicalSchema, resourceName, loc, issues);
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
