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
package org.gbif.dp.validator.api;

import java.util.Map;

/**
 * Default severity assignments for each {@link DescriptorViolationType}.
 *
 * <p>Validators accept an optional {@code Map<DescriptorViolationType, ValidationIssue.Severity>}
 * override. Any type not present in the override map falls back to this default.
 * This allows deployments to tune severity policy (e.g. promote a WARNING to ERROR,
 * or demote it to INFO) without recompiling.
 */
public final class DefaultSeverities {

  private DefaultSeverities() {}

  public static final Map<DescriptorViolationType, ValidationIssue.Severity> DEFAULTS = Map.ofEntries(
    // ── Blocking structural ──────────────────────────────────────────
    Map.entry(DescriptorViolationType.DESCRIPTOR_NOT_FOUND, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.INVALID_JSON, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.MISSING_RESOURCES, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.PATH_NOT_FOUND, ValidationIssue.Severity.ERROR),

    // ── Frictionless spec warnings ───────────────────────────────────
    Map.entry(DescriptorViolationType.MISSING_NAME, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.RESOURCE_MISSING_NAME, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.FK_UNKNOWN_REFERENCE_RESOURCE, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.UNKNOWN_FIELD_TYPE, ValidationIssue.Severity.WARNING),

    // ── DwC-DP JSON Schema (Layer 1) ─────────────────────────────────
    Map.entry(DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.JSON_SCHEMA_VIOLATION, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.JSON_SCHEMA_UNAVAILABLE, ValidationIssue.Severity.INFO),

    // ── DwC-DP table schema cross-validation (Layer 2) ───────────────
    Map.entry(DescriptorViolationType.REQUIRED_FIELD_MISSING, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.FIELD_TYPE_MISMATCH, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.FIELD_DUPLICATE, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.FIELD_DEFINITION_MISSING, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.FIELD_DEFINITION_MISMATCH, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.FOREIGN_KEY_MISSING, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.UNKNOWN_FIELD, ValidationIssue.Severity.INFO),
    Map.entry(DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE, ValidationIssue.Severity.INFO),

    // ── EML metadata validation ───────────────────────────────────────
    Map.entry(DescriptorViolationType.INVALID_XML, ValidationIssue.Severity.ERROR),
    Map.entry(DescriptorViolationType.EML_MISSING_TITLE, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.EML_MISSING_CREATOR, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.EML_XSD_VIOLATION, ValidationIssue.Severity.WARNING),
    Map.entry(DescriptorViolationType.EML_XSD_UNAVAILABLE, ValidationIssue.Severity.INFO));

  /**
   * Resolve the effective severity for a violation type, applying any caller-supplied
   * override before falling back to {@link #DEFAULTS}.
   */
  public static ValidationIssue.Severity resolve(
    DescriptorViolationType type,
    Map<DescriptorViolationType, ValidationIssue.Severity> overrides
  ) {
    if (overrides != null) {
      ValidationIssue.Severity override = overrides.get(type);
      if (override != null) return override;
    }
    return DEFAULTS.getOrDefault(type, ValidationIssue.Severity.WARNING);
  }
}
