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
 * A resource's {@code schema} object — the Frictionless Table Schema describing its fields
 * and structural constraints. Per the DwC-DP guide, {@code fields}, {@code primaryKey}, and
 * {@code foreignKeys} all live here, nested under the resource's {@code schema} property —
 * not as siblings of {@code schema} on the resource itself.
 *
 * <p>{@code weakPrimaryKey}/{@code weakForeignKeys} are not part of the ratified DwC-DP guide
 * (which only documents {@code fields}/{@code primaryKey}/{@code foreignKeys}/
 * {@code missingValues}) — they're a 1.0_DEV-prerelease extension, sanctioned by the guide's
 * general allowance for custom schema properties.
 */
public record SchemaDescriptor(
  List<FieldDescriptor> fields,
  PrimaryKeyDescriptor primaryKey,
  PrimaryKeyDescriptor weakPrimaryKey,
  List<ForeignKeyDescriptor> foreignKeys,
  List<ForeignKeyDescriptor> weakForeignKeys,
  List<MissingValueDescriptor> missingValues) {

  public static SchemaDescriptor empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private List<FieldDescriptor> fields = List.of();
    private PrimaryKeyDescriptor primaryKey;
    private PrimaryKeyDescriptor weakPrimaryKey;
    private List<ForeignKeyDescriptor> foreignKeys = List.of();
    private List<ForeignKeyDescriptor> weakForeignKeys = List.of();
    private List<MissingValueDescriptor> missingValues = List.of(MissingValueDescriptor.NULL);

    public Builder fields(List<FieldDescriptor> v)               { this.fields = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder primaryKey(PrimaryKeyDescriptor v)             { this.primaryKey = v; return this; }
    public Builder weakPrimaryKey(PrimaryKeyDescriptor v)         { this.weakPrimaryKey = v; return this; }
    public Builder foreignKeys(List<ForeignKeyDescriptor> v)      { this.foreignKeys = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder weakForeignKeys(List<ForeignKeyDescriptor> v)  { this.weakForeignKeys = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder missingValues(List<MissingValueDescriptor> v)  { this.missingValues = v == null ? List.of() : List.copyOf(v); return this; }

    public SchemaDescriptor build() {
      return new SchemaDescriptor(fields, primaryKey, weakPrimaryKey, foreignKeys, weakForeignKeys, missingValues);
    }
  }
}
