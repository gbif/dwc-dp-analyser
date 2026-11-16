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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * <p>A {@code profile} that is not bundled is fetched remotely if the {@link SchemaFetchPolicy}
 * allows it (default: {@link SchemaFetchPolicy#defaults()}). Layers 1 and 2 are skipped, with a
 * single {@code UNRECOGNIZED_PROFILE_VERSION} issue explaining why, if the {@code profile} is
 * missing, not bundled and not fetchable (blocked by policy, fetch failed, or not a profile).
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
    this(severityOverrides, SchemaFetchPolicy.defaults());
  }

  /**
   * @param schemaFetchPolicy decides which URLs may be fetched when a descriptor declares a
   *                          profile that is not bundled; {@link SchemaFetchPolicy#none()}
   *                          disables remote fetching entirely
   */
  public DwcDpDescriptorValidator(
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides,
    SchemaFetchPolicy schemaFetchPolicy) {
    this(new FrictionlessDescriptorValidator(new ObjectMapper(), severityOverrides),
         new DwcDpProfileRegistry(schemaFetchPolicy), new ObjectMapper(), severityOverrides);
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
    ProfileResolution resolution = profileRegistry.resolveDetailed(profileUri);

    if (resolution instanceof ProfileResolution.Unresolved unresolved) {
      allIssues.add(issue(DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION,
                          unresolvedMessage(profileUri, unresolved),
                          "/profile"));
    } else {
      DwcDpSchemaVersion v = ((ProfileResolution.Resolved) resolution).version();
      DwcDpProfileValidator profileValidator = new DwcDpProfileValidator(v.profileSchema(), severityOverrides);
      DwcDpTableSchemaValidator tableSchemaValidator =
        new DwcDpTableSchemaValidator(mapper, severityOverrides, v.tableSchemaSource());

      allIssues.addAll(profileValidator.validate(content));
      allIssues.addAll(tableSchemaValidator.validate(content));
    }

    return DescriptorValidationResult.of(allIssues);
  }

  private static final String SKIPPED = " — JSON Schema and table schema validation skipped.";

  /** One message per failure reason, so users can tell "unknown" from "blocked" from "offline". */
  private static String unresolvedMessage(String profileUri, ProfileResolution.Unresolved unresolved) {
    return switch (unresolved.reason()) {
      case MISSING -> "No 'profile' declared — cannot determine DwC-DP version" + SKIPPED;
      case NOT_A_URL -> "Unrecognized DwC-DP profile '" + profileUri + "': not a bundled version and "
                        + unresolved.detail() + SKIPPED;
      case REMOTE_DISABLED -> "DwC-DP profile '" + profileUri + "' is not a bundled version and "
                              + unresolved.detail() + SKIPPED;
      case NOT_ALLOWED -> "DwC-DP profile '" + profileUri + "' is not a bundled version and "
                          + unresolved.detail()
                          + (hostHintApplies(profileUri, unresolved)
                             ? ". Allow its host in the schema fetch policy (CLI: --schema-host <host>)"
                               + " to validate against it"
                             : "")
                          + SKIPPED;
      case FETCH_FAILED -> "DwC-DP profile '" + profileUri + "' is not a bundled version and "
                           + unresolved.detail() + SKIPPED;
      case NOT_A_PROFILE -> "DwC-DP profile '" + profileUri + "' is not a bundled version, and "
                            + unresolved.detail() + SKIPPED;
      case LOAD_FAILED -> "DwC-DP profile '" + profileUri + "' was fetched but "
                          + unresolved.detail() + SKIPPED;
    };
  }

  /**
   * Trusting another host only helps if the policy (not a hard rule such as user-info) refused
   * the URL, and the URL is on a default port — host allowlists never admit other ports.
   */
  private static boolean hostHintApplies(String profileUri, ProfileResolution.Unresolved unresolved) {
    if (!unresolved.detail().endsWith(RemoteSchemaFetcher.POLICY_DENIED)) {
      return false;
    }
    try {
      int port = URI.create(profileUri.trim()).getPort();
      return port == -1 || port == 80 || port == 443;
    } catch (IllegalArgumentException e) {
      return false;
    }
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
