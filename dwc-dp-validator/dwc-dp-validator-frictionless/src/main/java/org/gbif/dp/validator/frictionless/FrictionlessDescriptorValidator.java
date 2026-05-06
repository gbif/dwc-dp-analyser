package org.gbif.dp.validator.frictionless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a datapackage.json against the Frictionless Data Package v1 specification.
 *
 * <p>Checks (blocking ERRORs first, then warnings):
 * <ol>
 *   <li>File exists</li>
 *   <li>Valid JSON</li>
 *   <li>{@code resources} is a non-empty array</li>
 *   <li>{@code name} present and non-blank</li>
 *   <li>Each resource has a non-blank {@code name}</li>
 *   <li>Each resource path exists on disk</li>
 *   <li>FK {@code reference.resource} values resolve to declared resource names</li>
 *   <li>Field {@code type} values are from the Frictionless v1 vocabulary</li>
 * </ol>
 *
 * <p>Locations use JSON Pointer notation (e.g. {@code /resources/0/schema/fields/1/type}).
 *
 * <p>Severity of each violation type can be overridden at construction time via a
 * {@code Map<DescriptorViolationType, ValidationIssue.Severity>}.
 */
public class FrictionlessDescriptorValidator implements DescriptorValidator {

  private static final Logger log = LoggerFactory.getLogger(FrictionlessDescriptorValidator.class);

  public static final Set<String> FRICTIONLESS_TYPES = Set.of(
    "string", "number", "integer", "boolean",
    "object", "array", "date", "time", "datetime",
    "year", "yearmonth", "duration", "geopoint", "geojson", "any");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ObjectMapper mapper;
  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;

  public FrictionlessDescriptorValidator() {
    this(new ObjectMapper(), Map.of());
  }

  public FrictionlessDescriptorValidator(
    ObjectMapper mapper,
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.mapper = mapper;
    this.severityOverrides = severityOverrides;
  }

  @Override
  public DescriptorValidationResult validate(Path descriptorPath) {
    List<ValidationIssue> issues = new ArrayList<>();

    // 1. File existence
    if (!Files.exists(descriptorPath)) {
      return DescriptorValidationResult.of(List.of(issue(
        DescriptorViolationType.DESCRIPTOR_NOT_FOUND,
        "datapackage.json not found at: " + descriptorPath,
        null, null)));
    }

    // 2. Valid JSON
    JsonNode root;
    try {
      root = mapper.readTree(descriptorPath.toFile());
    } catch (Exception e) {
      return DescriptorValidationResult.of(List.of(issue(
        DescriptorViolationType.INVALID_JSON,
        "datapackage.json is not valid JSON: " + e.getMessage(),
        null, detail("parseError", e.getMessage()))));
    }

    // 3. resources — blocking if absent or empty
    JsonNode resourcesNode = root.path("resources");
    if (!resourcesNode.isArray() || resourcesNode.isEmpty()) {
      return DescriptorValidationResult.of(List.of(issue(
        DescriptorViolationType.MISSING_RESOURCES,
        "'resources' must be a non-empty array.",
        "/resources", null)));
    }

    // 4. name
    if (root.path("name").asText("").isBlank()) {
      issues.add(issue(DescriptorViolationType.MISSING_NAME,
        "The 'name' property is missing or blank.",
        "/name", null));
    }

    // Collect resource names for FK cross-reference
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode r : resourcesNode) {
      String n = r.path("name").asText("").trim();
      if (!n.isBlank()) resourceNames.add(n);
    }

    // 5–8. Per-resource checks
    Path baseDir = descriptorPath.getParent();
    for (int i = 0; i < resourcesNode.size(); i++) {
      JsonNode resource = resourcesNode.get(i);
      String loc = "/resources/" + i;

      // 5. Resource name
      if (resource.path("name").asText("").isBlank()) {
        issues.add(issue(DescriptorViolationType.RESOURCE_MISSING_NAME,
          "Resource at index " + i + " has no 'name' property.",
          loc + "/name", null));
      }

      // 6. Paths on disk
      validatePaths(resource, baseDir, loc, issues);

      // 7. FK references
      validateFkReferences(resource, resourceNames, loc, issues);

      // 8. Field types
      validateFieldTypes(resource, loc, issues);
    }

    return DescriptorValidationResult.of(issues);
  }

  // ── per-resource checks ───────────────────────────────────────────────────

  private void validatePaths(JsonNode resource, Path baseDir, String loc,
                             List<ValidationIssue> issues) {
    JsonNode pathNode = resource.path("path");
    if (pathNode.isMissingNode() || pathNode.isNull()) return;

    List<String> paths = new ArrayList<>();
    if (pathNode.isArray()) {
      pathNode.forEach(p -> { String v = p.asText("").trim(); if (!v.isBlank()) paths.add(v); });
    } else {
      String v = pathNode.asText("").trim();
      if (!v.isBlank()) paths.add(v);
    }

    for (String rel : paths) {
      if (!Files.exists(baseDir.resolve(rel).normalize())) {
        issues.add(issue(DescriptorViolationType.PATH_NOT_FOUND,
          "Resource path does not exist on disk: " + rel,
          loc + "/path",
          detail("path", rel)));
      }
    }
  }

  private void validateFkReferences(JsonNode resource, List<String> resourceNames,
                                    String loc, List<ValidationIssue> issues) {
    JsonNode fkNodes = resource.path("schema").path("foreignKeys");
    if (!fkNodes.isArray()) return;
    for (int j = 0; j < fkNodes.size(); j++) {
      String ref = fkNodes.get(j).path("reference").path("resource").asText("").trim();
      if (!ref.isBlank() && !resourceNames.contains(ref)) {
        issues.add(issue(DescriptorViolationType.FK_UNKNOWN_REFERENCE_RESOURCE,
          "Foreign key references undeclared resource: '" + ref + "'.",
          loc + "/schema/foreignKeys/" + j + "/reference/resource",
          detail("referencedResource", ref)));
      }
    }
  }

  private void validateFieldTypes(JsonNode resource, String loc, List<ValidationIssue> issues) {
    JsonNode fields = resource.path("schema").path("fields");
    if (!fields.isArray()) return;
    for (int k = 0; k < fields.size(); k++) {
      JsonNode field = fields.get(k);
      String type = field.path("type").asText("string").trim().toLowerCase();
      if (!FRICTIONLESS_TYPES.contains(type)) {
        issues.add(issue(DescriptorViolationType.UNKNOWN_FIELD_TYPE,
          "Field '" + field.path("name").asText("?") + "' has unknown type: '" + type + "'.",
          loc + "/schema/fields/" + k + "/type",
          detail("fieldName", field.path("name").asText("?"), "actualType", type)));
      }
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private ValidationIssue issue(DescriptorViolationType type, String message,
                                String location, String detail) {
    return ValidationIssue.of(type, message, location, detail, severityOverrides);
  }

  private static String detail(String... keyValuePairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
      map.put(keyValuePairs[i], keyValuePairs[i + 1]);
    }
    try {
      return MAPPER.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialize detail to JSON", e);
      return null;
    }
  }
}
