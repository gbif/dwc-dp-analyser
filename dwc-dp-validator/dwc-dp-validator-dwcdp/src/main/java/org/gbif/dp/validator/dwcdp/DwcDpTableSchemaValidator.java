package org.gbif.dp.validator.dwcdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Layer 2: cross-validates each DwC-DP named resource in a datapackage.json against
 * the canonical table schema bundled in {@code dwc-dp-schemas}.
 *
 * <p>For each resource whose {@code name} is a reserved DwC-DP table name:
 * <ul>
 *   <li>Loads the canonical field list from the bundled table schema</li>
 *   <li>Checks all {@code constraints.required=true} fields are declared</li>
 *   <li>Checks declared field types match the canonical type</li>
 *   <li>Checks canonical foreign keys are declared</li>
 *   <li>Reports any declared fields absent from the canonical schema (INFO)</li>
 * </ul>
 *
 * <p>{@code index.json} is read once per {@link #validate} call and held in a local map
 * for all per-resource lookups within that call — not cached across calls, not static.
 */
public class DwcDpTableSchemaValidator {

  private static final Logger log = LoggerFactory.getLogger(DwcDpTableSchemaValidator.class);

  private static final String INDEX_CLASSPATH   = "/schemas/0.1/index.json";
  private static final String SCHEMA_BASE       = "/schemas/0.1/table-schemas/";

  private final ObjectMapper mapper;
  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;

  public DwcDpTableSchemaValidator() {
    this(new ObjectMapper(), Map.of());
  }

  public DwcDpTableSchemaValidator(
    ObjectMapper mapper,
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.mapper = mapper;
    this.severityOverrides = severityOverrides;
  }

  /**
   * Cross-validate DwC-DP named resources in the descriptor against canonical table schemas.
   *
   * @param descriptorPath path to datapackage.json (already confirmed parseable by caller)
   * @return list of issues found (empty if all named resources conform)
   */
  public List<ValidationIssue> validate(Path descriptorPath) {
    List<ValidationIssue> issues = new ArrayList<>();

    // Read index.json once — maps resource name -> classpath of table schema
    Map<String, String> nameToSchemaPath = loadIndex(issues);
    if (nameToSchemaPath == null) {
      return issues; // index unavailable, already reported
    }

    JsonNode root;
    try {
      root = mapper.readTree(descriptorPath.toFile());
    } catch (Exception e) {
      // Descriptor already validated parseable upstream; this should not happen
      log.warn("Unexpected re-parse failure in table schema validator: {}", e.getMessage());
      return issues;
    }

    JsonNode resourcesNode = root.path("resources");
    if (!resourcesNode.isArray()) return issues;

    for (int i = 0; i < resourcesNode.size(); i++) {
      JsonNode resource = resourcesNode.get(i);
      String name = resource.path("name").asText("").trim();
      String loc  = "resources[" + i + "]";

      String schemaPath = nameToSchemaPath.get(name);
      if (schemaPath == null) {
        // Not a reserved DwC-DP table — skip
        continue;
      }

      JsonNode canonicalSchema = loadTableSchema(schemaPath, name, loc, issues);
      if (canonicalSchema == null) continue;

      crossCheck(resource, canonicalSchema, name, loc, issues);
    }

    return issues;
  }

  // ── index loading ─────────────────────────────────────────────────────────

  /**
   * Load index.json and build name → classpath-of-table-schema map.
   * Returns null if the index cannot be loaded.
   */
  private Map<String, String> loadIndex(List<ValidationIssue> issues) {
    try (InputStream is = getClass().getResourceAsStream(INDEX_CLASSPATH)) {
      if (is == null) {
        log.warn("DwC-DP index not found at classpath:{}", INDEX_CLASSPATH);
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
          String url  = entry.path("url").asText("").trim(); // e.g. "table-schemas/occurrence.json"
          if (!name.isBlank() && !url.isBlank()) {
            // Convert relative url to absolute classpath: "table-schemas/x.json" -> "/schemas/0.1/table-schemas/x.json"
            String classpathEntry = SCHEMA_BASE + url.replaceFirst("^table-schemas/", "");
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

  // ── table schema loading ──────────────────────────────────────────────────

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

  // ── cross-checking ────────────────────────────────────────────────────────

  private void crossCheck(JsonNode resource, JsonNode canonicalSchema,
                          String resourceName, String loc, List<ValidationIssue> issues) {

    // Build canonical field map: fieldName -> fieldNode
    Map<String, JsonNode> canonicalFields = new HashMap<>();
    JsonNode canonicalFieldsNode = canonicalSchema.path("fields");
    if (canonicalFieldsNode.isArray()) {
      for (JsonNode f : canonicalFieldsNode) {
        String n = f.path("name").asText("").trim();
        if (!n.isBlank()) canonicalFields.put(n, f);
      }
    }

    // Build user-declared field map: fieldName -> fieldNode
    Map<String, JsonNode> userFields = new HashMap<>();
    JsonNode userFieldsNode = resource.path("schema").path("fields");
    if (userFieldsNode.isArray()) {
      for (JsonNode f : userFieldsNode) {
        String n = f.path("name").asText("").trim();
        if (!n.isBlank()) userFields.put(n, f);
      }
    }

    // 1. Required fields present?
    for (Map.Entry<String, JsonNode> entry : canonicalFields.entrySet()) {
      String fieldName  = entry.getKey();
      JsonNode canonical = entry.getValue();
      boolean required  = canonical.path("constraints").path("required").asBoolean(false);

      if (required && !userFields.containsKey(fieldName)) {
        issues.add(issue(DescriptorViolationType.REQUIRED_FIELD_MISSING,
          "Required field '" + fieldName + "' is missing from resource '" + resourceName + "'.",
          loc + ".schema.fields"));
      }
    }

    // 2. Type mismatches + unknown fields
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

      String userType      = userField.path("type").asText("string").trim().toLowerCase();
      String canonicalType = canonical.path("type").asText("string").trim().toLowerCase();
      if (!userType.equals(canonicalType)) {
        issues.add(issue(DescriptorViolationType.FIELD_TYPE_MISMATCH,
          "Field '" + fieldName + "' in resource '" + resourceName
            + "' declares type '" + userType
            + "' but canonical schema expects '" + canonicalType + "'.",
          loc + ".schema.fields[" + fieldName + "].type"));
      }
    }

    // 3. Foreign key declarations
    crossCheckForeignKeys(resource, canonicalSchema, resourceName, loc, issues);
  }

  private void crossCheckForeignKeys(JsonNode resource, JsonNode canonicalSchema,
                                     String resourceName, String loc, List<ValidationIssue> issues) {

    JsonNode canonicalFks = canonicalSchema.path("foreignKeys");
    if (!canonicalFks.isArray()) return;

    // Build canonical required fields set for guard below
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
        : (fieldsNode.isArray() && fieldsNode.size() > 0
           ? fieldsNode.get(0).asText().trim() : "");

      if (fkField.isBlank()) continue;

      // Only enforce FK presence if the field itself is required
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
