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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;

/**
 * Layer 1: validates a datapackage.json's content against a DwC-DP JSON Schema profile via
 * networknt json-schema-validator 2.x.
 *
 * <p>Stateless with respect to I/O — takes descriptor content already read by the caller
 * (previously read the file itself from a {@code Path}; that responsibility now belongs to
 * whoever resolves a {@code DataPackageSource}, one layer up).
 *
 * <p>The legacy no-arg / severity-only constructors default to the 0.1 profile for backward
 * compatibility. For multi-version delegation, construct with an already-resolved
 * {@link Schema} from {@link DwcDpProfileRegistry} instead.
 */
public class DwcDpProfileValidator {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileValidator.class);

  private static final String LEGACY_PROFILE_URI = "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json";
  private static final String LEGACY_TDWG_SCHEMA_BASE_URL = "https://rs.tdwg.org/dwc-dp";
  private static final String LEGACY_TDWG_SCHEMA_BASE_REF = "classpath:schemas";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;
  private final Schema schema;

  /** Defaults to the 0.1 profile — legacy single-version behavior. */
  public DwcDpProfileValidator() {
    this(Map.of());
  }

  /** Defaults to the 0.1 profile — legacy single-version behavior. */
  public DwcDpProfileValidator(Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.severityOverrides = severityOverrides;
    this.schema = DwcDpProfileSchemaLoader.load(
      LEGACY_PROFILE_URI, LEGACY_TDWG_SCHEMA_BASE_URL, LEGACY_TDWG_SCHEMA_BASE_REF);
  }

  /** For multi-version delegation: constructed with an already-resolved {@link Schema}. */
  public DwcDpProfileValidator(Schema schema, Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.schema = schema;
    this.severityOverrides = severityOverrides;
  }

  /**
   * Validate descriptor content against this instance's profile schema.
   *
   * @param descriptorContent an already-confirmed-parseable datapackage.json's content
   * @return list of issues (empty = fully conformant)
   */
  public List<ValidationIssue> validate(String descriptorContent) {
    if (schema == null) {
      return List.of(issue(
        DescriptorViolationType.JSON_SCHEMA_UNAVAILABLE,
        "DwC-DP profile schema not available on classpath — JSON Schema validation skipped.",
        null, null));
    }

    List<ValidationIssue> issues = new ArrayList<>();
    try {
      for (Error error : schema.validate(descriptorContent, InputFormat.JSON)) {
        issues.add(issueFromError(error));
      }
    } catch (Exception e) {
      log.warn("Unexpected error during JSON Schema validation: {}", e.getMessage());
      issues.add(issue(
        DescriptorViolationType.JSON_SCHEMA_VIOLATION,
        "JSON Schema validation failed unexpectedly: " + e.getMessage(),
        null, null));
    }
    return issues;
  }

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

  private ValidationIssue issue(DescriptorViolationType type, String message, String location, String detail) {
    return ValidationIssue.of(type, message, location, detail, severityOverrides);
  }
}
