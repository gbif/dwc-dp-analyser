package org.gbif.dp.validator.dwcdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gbif.dp.validator.api.DefaultSeverities;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.gbif.dp.validator.frictionless.DescriptorValidator;
import org.gbif.dp.validator.frictionless.FrictionlessDescriptorValidator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full DwC-DP descriptor validator — orchestrates three layers:
 *
 * <ol>
 *   <li><strong>Frictionless structural</strong> ({@link FrictionlessDescriptorValidator}):
 *       JSON parseable, resources array, paths on disk, FK references, field types.</li>
 *   <li><strong>DwC-DP JSON Schema</strong> ({@link DwcDpProfileValidator}):
 *       validates the descriptor against {@code dwc-dp-profile.json} via networknt.</li>
 *   <li><strong>DwC-DP table schema cross-validation</strong> ({@link DwcDpTableSchemaValidator}):
 *       for each reserved DwC-DP table name, checks required fields, types, and FKs
 *       against the canonical {@code table-schemas/*.json}.</li>
 * </ol>
 *
 * <p>Layers 2 and 3 are skipped if Layer 1 produced a blocking error (unparseable descriptor).
 *
 * <p>Severity of each violation type is controlled by the {@code severityOverrides} map,
 * falling back to {@link DefaultSeverities#DEFAULTS} for any type not present in the map.
 */
public class DwcDpDescriptorValidator implements DescriptorValidator {

  private final FrictionlessDescriptorValidator frictionlessValidator;
  private final DwcDpProfileValidator profileValidator;
  private final DwcDpTableSchemaValidator tableSchemaValidator;

  /** Default constructor — uses default severity assignments. */
  public DwcDpDescriptorValidator() {
    this(Map.of());
  }

  /**
   * Constructor with severity overrides.
   *
   * @param severityOverrides map of violation type to severity; types absent from this map
   *                          fall back to {@link DefaultSeverities#DEFAULTS}
   */
  public DwcDpDescriptorValidator(
    Map<DescriptorViolationType, ValidationIssue.Severity> severityOverrides) {
    ObjectMapper mapper = new ObjectMapper();
    this.frictionlessValidator = new FrictionlessDescriptorValidator(mapper, severityOverrides);
    this.profileValidator      = new DwcDpProfileValidator(severityOverrides);
    this.tableSchemaValidator  = new DwcDpTableSchemaValidator(mapper, severityOverrides);
  }

  /** Full constructor for testing or custom component injection. */
  public DwcDpDescriptorValidator(
    FrictionlessDescriptorValidator frictionlessValidator,
    DwcDpProfileValidator profileValidator,
    DwcDpTableSchemaValidator tableSchemaValidator) {
    this.frictionlessValidator = frictionlessValidator;
    this.profileValidator      = profileValidator;
    this.tableSchemaValidator  = tableSchemaValidator;
  }

  @Override
  public DescriptorValidationResult validate(Path descriptorPath) {
    // Layer 0: Frictionless structural checks
    DescriptorValidationResult frictionlessResult = frictionlessValidator.validate(descriptorPath);

    // If unparseable or unloadable, skip layers 1 and 2
    if (!frictionlessResult.canProceedToDataAnalysis()
      && DescriptorValidationResult.errors(frictionlessResult).stream()
      .anyMatch(e -> e.violationType() == DescriptorViolationType.DESCRIPTOR_NOT_FOUND
        || e.violationType() == DescriptorViolationType.INVALID_JSON)) {
      return frictionlessResult;
    }

    List<ValidationIssue> allIssues = new ArrayList<>(frictionlessResult.issues());

    // Layer 1: DwC-DP JSON Schema profile
    allIssues.addAll(profileValidator.validate(descriptorPath));

    // Layer 2: Table schema cross-validation
    allIssues.addAll(tableSchemaValidator.validate(descriptorPath));

    return DescriptorValidationResult.of(allIssues);
  }
}
