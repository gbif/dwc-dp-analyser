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
 * A single structural issue found during validation of a descriptor or metadata file.
 *
 * <p><strong>Jackson safety:</strong> only record components are serialised. The
 * {@link #code()} accessor is deliberately NOT a component — it is excluded from
 * the canonical constructor and therefore safe to use as a convenience method without
 * appearing as an extra JSON field on deserialisation.
 *
 * @param severity      ERROR blocks data analysis; WARNING is non-conformance; INFO is advisory
 * @param violationType machine-readable violation category
 * @param message       human-readable explanation (may include context such as field name)
 * @param location      optional pointer into the document, e.g. "resources[1].schema.fields[0]"
 * @param detail        optional JSON string with structured context, e.g. keyword, evaluationPath, actualValue
 */
public record ValidationIssue(
  Severity severity,
  DescriptorViolationType violationType,
  String message,
  String location,
  String detail) {

  public enum Severity { ERROR, WARNING, INFO }

  /** Stable string code derived from the violation type — safe for JSON serialisation. */
  public String code() {
    return violationType.name();
  }

  // ── Static factories ──────────────────────────────────────────────────────

  /** Full control — caller supplies all fields including detail. */
  public static ValidationIssue of(
    Severity severity,
    DescriptorViolationType type,
    String message,
    String location,
    String detail) {
    return new ValidationIssue(severity, type, message, location, detail);
  }

  /** Without detail — resolves severity from defaults. */
  public static ValidationIssue of(
    DescriptorViolationType type,
    String message,
    String location) {
    return new ValidationIssue(
      DefaultSeverities.resolve(type, null), type, message, location, null);
  }

  /** Without detail — resolves severity from a caller-supplied override map. */
  public static ValidationIssue of(
    DescriptorViolationType type,
    String message,
    String location,
    Map<DescriptorViolationType, Severity> overrides) {
    return new ValidationIssue(
      DefaultSeverities.resolve(type, overrides), type, message, location, null);
  }

  /** With detail — resolves severity from defaults. */
  public static ValidationIssue of(
    DescriptorViolationType type,
    String message,
    String location,
    String detail) {
    return new ValidationIssue(
      DefaultSeverities.resolve(type, null), type, message, location, detail);
  }

  /** With detail — resolves severity from a caller-supplied override map. */
  public static ValidationIssue of(
    DescriptorViolationType type,
    String message,
    String location,
    String detail,
    Map<DescriptorViolationType, Severity> overrides) {
    return new ValidationIssue(
      DefaultSeverities.resolve(type, overrides), type, message, location, detail);
  }
}
