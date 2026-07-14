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
 * A foreign key declaration. Used for both {@code foreignKeys} (enforced when the referring
 * field is required — {@code reference.resource} is the sibling resource's {@code name},
 * per the DwC-DP guide, never a separate "schema name") and {@code weakForeignKeys}
 * (advisory / not-yet-ratified extension) — both share this exact shape.
 */
public record ForeignKeyDescriptor(List<String> fields, ReferenceDescriptor reference, String predicate) {

  public ForeignKeyDescriptor(List<String> fields, ReferenceDescriptor reference) {
    this(fields, reference, null);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private List<String> fields = List.of();
    private ReferenceDescriptor reference;
    private String predicate;

    public Builder fields(List<String> v)           { this.fields = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder reference(ReferenceDescriptor v)  { this.reference = v; return this; }
    public Builder predicate(String v)               { this.predicate = v; return this; }

    public ForeignKeyDescriptor build() {
      return new ForeignKeyDescriptor(fields, reference, predicate);
    }
  }
}
