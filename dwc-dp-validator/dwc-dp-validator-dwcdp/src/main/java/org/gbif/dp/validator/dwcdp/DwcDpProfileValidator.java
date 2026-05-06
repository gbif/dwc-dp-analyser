package org.gbif.dp.validator.dwcdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 1: validates a datapackage.json against the DwC-DP JSON Schema profile
 * ({@code /schemas/0.1/dwc-dp-profile.json}) using networknt json-schema-validator 2.x.
 *
 * <p>Both the DwC-DP profile schema and the Frictionless data-package schema it references
 * are loaded from the classpath (bundled in {@code dwc-dp-schemas}) and registered in a
 * {@link SchemaRegistry} keyed by their canonical URIs, so no network access is needed.
 *
 * <p>The {@link SchemaRegistry} and resolved schema are built once per instance.
 */
public class DwcDpProfileValidator {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileValidator.class);

  private static final String FRICTIONLESS_SCHEMA_BASE_URL = "https://specs.frictionlessdata.io/schemas";
  private static final String FRICTIONLESS_SCHEMA_BASE_REF = "classpath:schemas";
  private static final String TDWG_SCHEMA_BASE_URL = "https://rs.tdwg.org/dwc-dp";
  private static final String TDWG_SCHEMA_BASE_REF = "classpath:schemas/0.1";
  private static final String PROFILE_URI = TDWG_SCHEMA_BASE_URL + "/dwc-dp-profile.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;
  private final Schema schema;

  public DwcDpProfileValidator() {
    this(Map.of());
  }

  public DwcDpProfileValidator(
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.severityOverrides = severityOverrides;
    this.schema = loadSchema();
  }

  /**
   * Validate the descriptor against the DwC-DP profile.
   *
   * @param descriptorPath path to an already-confirmed-parseable datapackage.json
   * @return list of issues (empty = fully conformant)
   */
  public List<ValidationIssue> validate(Path descriptorPath) {
    if (schema == null) {
      return List.of(issue(
        DescriptorViolationType.JSON_SCHEMA_UNAVAILABLE,
        "DwC-DP profile schema not available on classpath — JSON Schema validation skipped.",
        null,
        null));
    }

    List<ValidationIssue> issues = new ArrayList<>();
    try {
      String content = Files.readString(descriptorPath);
      for (Error error : schema.validate(content, InputFormat.JSON)) {
        issues.add(issueFromError(error));
      }
    } catch (IOException e) {
      log.warn("Could not read descriptor for JSON Schema validation: {}", e.getMessage());
      issues.add(issue(
        DescriptorViolationType.JSON_SCHEMA_VIOLATION,
        "Could not read descriptor: " + e.getMessage(),
        null,
        null));
    } catch (Exception e) {
      log.warn("Unexpected error during JSON Schema validation: {}", e.getMessage());
      issues.add(issue(
        DescriptorViolationType.JSON_SCHEMA_VIOLATION,
        "JSON Schema validation failed unexpectedly: " + e.getMessage(),
        null,
        null));
    }
    return issues;
  }

  // ── error mapping ──────────────────────────────────────────────────────────

  private ValidationIssue issueFromError(Error error) {
    return issue(
      DescriptorViolationType.JSON_SCHEMA_VIOLATION,
      error.getMessage(),
      error.getInstanceLocation() != null ? error.getInstanceLocation().toString() : null,
      buildDetail(error));
  }

  private static String buildDetail(Error error) {
    Map<String, String> detail = new LinkedHashMap<>();
    if (error.getKeyword() != null) {
      detail.put("keyword", error.getKeyword());
    }
    if (error.getEvaluationPath() != null) {
      detail.put("evaluationPath", error.getEvaluationPath().toString());
    }
    if (error.getInstanceNode() != null) {
      detail.put("actualValue", error.getInstanceNode().asText());
    }
    if (detail.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialize error detail to JSON", e);
      return null;
    }
  }

  // ── schema loading ─────────────────────────────────────────────────────────

  private Schema loadSchema() {
    try {
      SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4,
        builder -> builder.schemaIdResolvers(schemaIdResolvers -> schemaIdResolvers
          .mapPrefix(FRICTIONLESS_SCHEMA_BASE_URL, FRICTIONLESS_SCHEMA_BASE_REF)
          .mapPrefix(TDWG_SCHEMA_BASE_URL, TDWG_SCHEMA_BASE_REF)
        )
      );
      Schema loaded = registry.getSchema(SchemaLocation.of(PROFILE_URI));
      log.debug("DwC-DP profile schema loaded successfully");
      return loaded;
    } catch (Exception e) {
      log.error("Failed to load DwC-DP profile schema: {}", e.getMessage(), e);
      return null;
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private ValidationIssue issue(
    DescriptorViolationType type, String message, String location, String detail) {
    return ValidationIssue.of(type, message, location, detail, severityOverrides);
  }
}
