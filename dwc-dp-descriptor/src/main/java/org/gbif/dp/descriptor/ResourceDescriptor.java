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
import java.util.Map;

/**
 * A single Frictionless Data Resource within a Data Package.
 *
 * @param paths declared {@code path} entries, exactly as they appear in the descriptor —
 *              a relative reference or a fully-qualified URL. Deliberately left unresolved:
 *              this model has no storage-backend dependency.
 * @param data  inline data, if the resource embeds rows directly rather than referencing a
 *              {@code path}.
 * @param schema the resource's {@code schema} property — fields, keys, and relationships.
 */
public record ResourceDescriptor(
  String name,
  String profile,
  List<String> paths,
  List<Map<String, Object>> data,
  String title,
  String description,
  String homepage,
  String format,
  String mediatype,
  String encoding,
  Long bytes,
  String hash,
  SchemaDescriptor schema,
  DialectDescriptor dialect,
  List<PackageSource> sources,
  List<License> licenses) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String profile;
    private List<String> paths = List.of();
    private List<Map<String, Object>> data = List.of();
    private String title;
    private String description;
    private String homepage;
    private String format;
    private String mediatype;
    private String encoding;
    private Long bytes;
    private String hash;
    private SchemaDescriptor schema = SchemaDescriptor.empty();
    private DialectDescriptor dialect;
    private List<PackageSource> sources = List.of();
    private List<License> licenses = List.of();

    public Builder name(String v)          { this.name = v;    return this; }
    public Builder profile(String v)       { this.profile = v; return this; }
    public Builder paths(List<String> v)   { this.paths = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder data(List<Map<String, Object>> v) { this.data = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder title(String v)         { this.title = v;       return this; }
    public Builder description(String v)   { this.description = v; return this; }
    public Builder homepage(String v)      { this.homepage = v;    return this; }
    public Builder format(String v)        { this.format = v;      return this; }
    public Builder mediatype(String v)     { this.mediatype = v;   return this; }
    public Builder encoding(String v)      { this.encoding = v;    return this; }
    public Builder bytes(Long v)           { this.bytes = v;       return this; }
    public Builder hash(String v)          { this.hash = v;        return this; }
    public Builder schema(SchemaDescriptor v) { this.schema = v == null ? SchemaDescriptor.empty() : v; return this; }
    public Builder dialect(DialectDescriptor v) { this.dialect = v; return this; }
    public Builder sources(List<PackageSource> v)  { this.sources = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder licenses(List<License> v)       { this.licenses = v == null ? List.of() : List.copyOf(v); return this; }

    public ResourceDescriptor build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("ResourceDescriptor requires a name");
      }
      return new ResourceDescriptor(name, profile, paths, data, title, description, homepage, format,
                                    mediatype, encoding, bytes, hash, schema, dialect, sources, licenses);
    }
  }
}
