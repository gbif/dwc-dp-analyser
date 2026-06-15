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

import java.util.List;

/**
 * Result of validating an eml.xml metadata file.
 *
 * <p>Per the DwC-DP spec, eml.xml is optional (MAY). Absence is not an error.
 *
 * <p><strong>Jackson safety:</strong> static helpers only — no instance methods.
 *
 * @param emlPresent true if an eml.xml was found alongside the descriptor
 * @param issues     all issues found (empty when emlPresent=false)
 * @param valid      true when no ERRORs are present (absence counts as valid)
 */
public record EmlValidationResult(
  boolean emlPresent,
  List<ValidationIssue> issues,
  boolean valid) {

  public static List<ValidationIssue> errors(EmlValidationResult r) {
    return r.issues().stream()
      .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
      .toList();
  }

  public static List<ValidationIssue> warnings(EmlValidationResult r) {
    return r.issues().stream()
      .filter(i -> i.severity() == ValidationIssue.Severity.WARNING)
      .toList();
  }

  /** EML file was not found — valid per spec (eml.xml is optional). */
  public static EmlValidationResult absent() {
    return new EmlValidationResult(false, List.of(), true);
  }

  /** EML file was present and passed all checks. */
  public static EmlValidationResult ok() {
    return new EmlValidationResult(true, List.of(), true);
  }

  /** Build from a list of issues found in a present eml.xml. */
  public static EmlValidationResult of(List<ValidationIssue> issues) {
    boolean valid = issues.stream()
      .noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    return new EmlValidationResult(true, List.copyOf(issues), valid);
  }
}
