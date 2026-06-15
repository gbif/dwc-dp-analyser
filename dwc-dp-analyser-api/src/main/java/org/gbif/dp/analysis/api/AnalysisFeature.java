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
package org.gbif.dp.analysis.api;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Feature flags controlling which analysis checks are performed in a given run.
 *
 * <p>Each feature has a human-readable {@link #description()} suitable for user-facing
 * reporting, help text, or summarising what a result set covers.
 *
 * <p>Features naturally divide into two concerns:
 * <ul>
 *   <li><em>Data analysis</em> — COUNT, COUNT_DISTINCT, FOREIGN_KEY_CONSTRAINT,
 *       PRIMARY_KEY_UNIQUE, DATA_TYPE_CONSTRAINT — handled by {@code DataAnalyser}</li>
 *   <li><em>Structural validation</em> — DESCRIPTOR_VALIDATION, EML_VALIDATION
 *       — handled by the {@code DataPackageAnalysisOrchestrator}</li>
 * </ul>
 */
public enum AnalysisFeature {

  COUNT(
    "Count the number of populated (non-null) values per column."),

  COUNT_DISTINCT(
    "Count the number of distinct populated values per column."),

  FOREIGN_KEY_CONSTRAINT(
    "Verify that all foreign key references resolve to existing rows in the referenced resource."),

  PRIMARY_KEY_UNIQUE(
    "Verify that the declared primary key fields are unique across all rows in the resource."),

  DATA_TYPE_CONSTRAINT(
    "Verify that column values conform to the Frictionless type declared in the schema "
      + "(e.g. integer, date, boolean) using DuckDB TRY_CAST."),

  DESCRIPTOR_VALIDATION(
    "Validate the datapackage.json descriptor for structural conformance against the "
      + "Frictionless Data Package v1 spec and the DwC-DP profile rules."),

  EML_VALIDATION(
    "Validate the eml.xml metadata file for well-formedness, required elements "
      + "(title, creator), and conformance with the EML 2.2.0 XSD schema.");

  private final String description;

  AnalysisFeature(String description) {
    this.description = description;
  }

  /** Human-readable description of what this feature checks. */
  public String description() {
    return description;
  }

  /** All features, in declaration order. */
  public static final java.util.List<AnalysisFeature> ALL_FEATURES =
    Arrays.stream(values()).toList();

  /**
   * Returns an immutable map of every feature to its description.
   * Useful for generating user-facing reports or help text.
   */
  public static Map<AnalysisFeature, String> descriptions() {
    return Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(f -> f, AnalysisFeature::description));
  }
}
