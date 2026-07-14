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
package org.gbif.dp.descriptor;

import java.util.List;

/**
 * Describes a single field in a Frictionless Data Package resource schema.
 *
 * <p>{@code dctermsIsVersionOf}/{@code dctermsReferences} correspond to the DwC-DP guide's
 * {@code dcterms:isVersionOf}/{@code dcterms:references} field-level terms — renamed to valid
 * Java identifiers since record components can't contain {@code :}.
 */
public record FieldDescriptor(
  String name,
  String type,
  String format,
  List<MissingValueDescriptor> missingValues,
  FieldConstraints constraints,
  String title,
  String description,
  String dctermsIsVersionOf,
  String dctermsReferences) {

  /** Convenience constructor for the common case: no format, no constraints, no DwC-DP terms. */
  public FieldDescriptor(String name, String type, List<MissingValueDescriptor> missingValues) {
    this(name, type, "default", missingValues, FieldConstraints.none(), null, null, null, null);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String type = "string";
    private String format = "default";
    private List<MissingValueDescriptor> missingValues = List.of(MissingValueDescriptor.NULL);
    private FieldConstraints constraints = FieldConstraints.none();
    private String title;
    private String description;
    private String dctermsIsVersionOf;
    private String dctermsReferences;

    public Builder name(String v)                                { this.name = v;        return this; }
    public Builder type(String v)                                 { this.type = v;        return this; }
    public Builder format(String v)                               { this.format = v;      return this; }
    public Builder missingValues(List<MissingValueDescriptor> v)  { this.missingValues = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder constraints(FieldConstraints v)                { this.constraints = v == null ? FieldConstraints.none() : v; return this; }
    public Builder title(String v)                                { this.title = v;       return this; }
    public Builder description(String v)                          { this.description = v; return this; }
    public Builder dctermsIsVersionOf(String v)                    { this.dctermsIsVersionOf = v; return this; }
    public Builder dctermsReferences(String v)                     { this.dctermsReferences = v;  return this; }

    public FieldDescriptor build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("FieldDescriptor requires a name");
      }
      return new FieldDescriptor(name, type, format, missingValues, constraints, title, description,
                                 dctermsIsVersionOf, dctermsReferences);
    }
  }
}
