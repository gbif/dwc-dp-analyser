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

/**
 * Machine-readable violation types for descriptor structural validation.
 *
 * <p>Severity is intentionally NOT on this enum — it lives in
 * {@link DefaultSeverities#DEFAULTS} and can be overridden per-deployment via a
 * {@code Map<DescriptorViolationType, ValidationIssue.Severity>} passed to validators.
 * This keeps the enum stable (additions are non-breaking) while allowing severity
 * policy to evolve independently.
 */
public enum DescriptorViolationType {

  // ── Blocking structural (Frictionless) ───────────────────────────────────

  DESCRIPTOR_NOT_FOUND(
    "The datapackage.json file does not exist at the given path."),

  INVALID_JSON(
    "The datapackage.json is not valid JSON."),

  MISSING_RESOURCES(
    "The 'resources' array is absent or empty — no data can be analysed."),

  PATH_NOT_FOUND(
    "A resource path declared in the descriptor does not exist on disk."),

  // ── Frictionless spec warnings ────────────────────────────────────────────

  MISSING_NAME(
    "The descriptor is missing a top-level 'name' property (RECOMMENDED by Frictionless spec)."),

  RESOURCE_MISSING_NAME(
    "A resource entry has no 'name' property."),

  FK_UNKNOWN_REFERENCE_RESOURCE(
    "A foreign key references a resource name not declared in this package."),

  UNKNOWN_FIELD_TYPE(
    "A field declares a type not in the Frictionless v1 type vocabulary."),

  // ── DwC-DP JSON Schema (Layer 1) ─────────────────────────────────────────

  UNRECOGNIZED_PROFILE_VERSION(
    "The descriptor's 'profile' does not match any known DwC-DP profile version — "
    + "JSON Schema and table schema validation were skipped."),

  JSON_SCHEMA_VIOLATION(
    "The descriptor does not conform to the DwC-DP JSON Schema profile (dwc-dp-profile.json)."),

  JSON_SCHEMA_UNAVAILABLE(
    "The DwC-DP JSON Schema could not be loaded — profile validation skipped."),

  // ── DwC-DP table schema cross-validation (Layer 2) ───────────────────────

  REQUIRED_FIELD_MISSING(
    "A field required by the canonical DwC-DP table schema is absent from the resource declaration."),

  FIELD_TYPE_MISMATCH(
    "A field's declared type differs from the canonical DwC-DP table schema type."),

  FOREIGN_KEY_MISSING(
    "A foreign key required by the canonical DwC-DP table schema is not declared in the resource."),

  UNKNOWN_FIELD(
    "A field is declared that does not exist in the canonical DwC-DP table schema."),

  TABLE_SCHEMA_UNAVAILABLE(
    "The canonical table schema for a DwC-DP resource could not be loaded from the classpath."),

  INVALID_XML(
    "The eml.xml file is not well-formed XML."),

  EML_MISSING_TITLE(
    "The EML file is missing a required <title> element or it is empty."),

  EML_MISSING_CREATOR(
    "The EML file is missing a required <creator> element or it is empty."),

  EML_XSD_VIOLATION(
    "The EML file does not conform to the EML 2.2.0 XSD schema."),

  EML_XSD_UNAVAILABLE(
    "The bundled EML XSD schema could not be loaded — XSD validation skipped.");

  private final String description;

  DescriptorViolationType(String description) {
    this.description = description;
  }

  /** Human-readable description of what this violation means. */
  public String description() {
    return description;
  }
}
