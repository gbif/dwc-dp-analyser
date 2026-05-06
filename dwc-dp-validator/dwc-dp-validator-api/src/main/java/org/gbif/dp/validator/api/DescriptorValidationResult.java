package org.gbif.dp.validator.api;

import java.util.List;
import java.util.Set;

/**
 * Result of validating the structural conformance of a datapackage.json descriptor.
 *
 * <p><strong>Jackson safety:</strong> this record contains only data fields.
 * All derived views (errors, warnings, canProceed) are expressed as static methods
 * to avoid accidental serialisation as JSON properties.
 *
 * @param issues                   all issues found, in discovery order
 * @param valid                    true when there are no ERRORs
 * @param canProceedToDataAnalysis true unless a blocking ERROR prevents loading resources
 *                                 (DESCRIPTOR_NOT_FOUND, INVALID_JSON, MISSING_RESOURCES,
 *                                 PATH_NOT_FOUND)
 */
public record DescriptorValidationResult(
  List<ValidationIssue> issues,
  boolean valid,
  boolean canProceedToDataAnalysis) {

  /** Codes whose presence means data analysis cannot proceed. */
  private static final Set<String> BLOCKING_CODES = Set.of(
    "DESCRIPTOR_NOT_FOUND",
    "INVALID_JSON",
    "MISSING_RESOURCES",
    "PATH_NOT_FOUND");

  // ── Static helpers (NOT instance methods — safe for Jackson) ─────────────

  public static List<ValidationIssue> errors(DescriptorValidationResult r) {
    return r.issues().stream()
      .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
      .toList();
  }

  public static List<ValidationIssue> warnings(DescriptorValidationResult r) {
    return r.issues().stream()
      .filter(i -> i.severity() == ValidationIssue.Severity.WARNING)
      .toList();
  }

  /** Build a result from a list of issues, deriving valid and canProceed automatically. */
  public static DescriptorValidationResult of(List<ValidationIssue> issues) {
    boolean valid = issues.stream()
      .noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    boolean canProceed = issues.stream()
      .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
      .noneMatch(i -> BLOCKING_CODES.contains(i.code()));
    return new DescriptorValidationResult(List.copyOf(issues), valid, canProceed);
  }

  /** A clean result with no issues. */
  public static DescriptorValidationResult ok() {
    return new DescriptorValidationResult(List.of(), true, true);
  }
}
