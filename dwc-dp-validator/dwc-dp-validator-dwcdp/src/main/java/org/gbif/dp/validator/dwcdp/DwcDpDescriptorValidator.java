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

import org.gbif.dp.common.io.DataPackageSource;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.gbif.dp.validator.frictionless.DescriptorValidator;
import org.gbif.dp.validator.frictionless.FrictionlessDescriptorValidator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full DwC-DP descriptor validator — orchestrates three layers:
 *
 * <ol>
 *   <li><strong>Frictionless structural</strong> ({@link FrictionlessDescriptorValidator}):
 *       version-independent, always runs.</li>
 *   <li><strong>DwC-DP JSON Schema</strong> ({@link DwcDpProfileValidator}): version-specific,
 *       resolved from the descriptor's {@code profile} field via {@link DwcDpProfileRegistry}.</li>
 *   <li><strong>DwC-DP table schema cross-validation</strong> ({@link DwcDpTableSchemaValidator}):
 *       version-specific, same resolution.</li>
 * </ol>
 *
 * <p>Layers 1 and 2 are skipped, with a single {@code UNRECOGNIZED_PROFILE_VERSION} issue,
 * if the descriptor's {@code profile} is missing or doesn't match a known version.
 */
public class DwcDpDescriptorValidator implements DescriptorValidator {

  private static final Logger log = LoggerFactory.getLogger(DwcDpDescriptorValidator.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final FrictionlessDescriptorValidator frictionlessValidator;
  private final DwcDpProfileRegistry profileRegistry;
  private final ObjectMapper mapper;
  private final Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides;

  public DwcDpDescriptorValidator() {
    this(Map.of());
  }

  public DwcDpDescriptorValidator(Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this(new FrictionlessDescriptorValidator(new ObjectMapper(), severityOverrides),
         new DwcDpProfileRegistry(), new ObjectMapper(), severityOverrides);
  }

  /** Full constructor for testing or custom component injection. */
  public DwcDpDescriptorValidator(
    FrictionlessDescriptorValidator frictionlessValidator,
    DwcDpProfileRegistry profileRegistry,
    ObjectMapper mapper,
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    this.frictionlessValidator = frictionlessValidator;
    this.profileRegistry = profileRegistry;
    this.mapper = mapper;
    this.severityOverrides = severityOverrides;
  }

  @Override
  public DescriptorValidationResult validate(DataPackageSource source) {
    // Layer 0: Frictionless structural checks
    DescriptorValidationResult frictionlessResult = frictionlessValidator.validate(source);

    if (!frictionlessResult.hasDataAnalysis()
        && DescriptorValidationResult.errors(frictionlessResult).stream()
          .anyMatch(e -> e.violationType() == DescriptorViolationType.DESCRIPTOR_NOT_FOUND
                         || e.violationType() == DescriptorViolationType.INVALID_JSON)) {
      return frictionlessResult;
    }

    List<ValidationIssue> allIssues = new ArrayList<>(frictionlessResult.issues());

    String content;
    try {
      content = source.readDescriptor();
    } catch (IOException e) {
      // Layer 0 just succeeded reading this moments ago — a failure here points at a
      // transient/backend issue rather than the descriptor itself, so log and stop here
      // rather than turn it into a structural finding.
      log.warn("Could not re-read descriptor for profile/table-schema validation: {}", e.getMessage());
      return DescriptorValidationResult.of(allIssues);
    }

    String profileUri = extractProfileUri(content);
    Optional<DwcDpSchemaVersion> version = profileUri == null
      ? Optional.empty()
      : profileRegistry.resolve(profileUri);

    if (version.isEmpty()) {
      allIssues.add(issue(DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION,
                          profileUri == null
                            ? "No 'profile' declared — cannot determine DwC-DP version; "
                              + "JSON Schema and table schema validation skipped."
                            : "Unrecognized DwC-DP profile version '" + profileUri + "' — "
                              + "JSON Schema and table schema validation skipped.",
                          "/profile"));
    } else {
      DwcDpSchemaVersion v = version.get();
      DwcDpProfileValidator profileValidator = new DwcDpProfileValidator(v.profileSchema(), severityOverrides);
      DwcDpTableSchemaValidator tableSchemaValidator =
        new DwcDpTableSchemaValidator(mapper, severityOverrides, v.tableSchemaClasspathBase());

      allIssues.addAll(profileValidator.validate(content));
      allIssues.addAll(tableSchemaValidator.validate(content));
    }

    return DescriptorValidationResult.of(allIssues);
  }

  private static String extractProfileUri(String descriptorContent) {
    try {
      JsonNode root = MAPPER.readTree(descriptorContent);
      String profile = root.path("profile").asText("").trim();
      return profile.isBlank() ? null : profile;
    } catch (Exception e) {
      return null;
    }
  }

  private ValidationIssue issue(DescriptorViolationType type, String message, String location) {
    return ValidationIssue.of(type, message, location, severityOverrides);
  }
}
