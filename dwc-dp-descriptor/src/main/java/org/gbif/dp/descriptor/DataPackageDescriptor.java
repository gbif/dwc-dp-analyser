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

public record DataPackageDescriptor(
  String name,
  String profile,
  String id,
  String title,
  String description,
  String homepage,
  String created,
  List<Contributor> contributors,
  List<String> keywords,
  String image,
  List<License> licenses,
  List<PackageSource> sources,
  List<ResourceDescriptor> resources) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String profile;
    private String id;
    private String title;
    private String description;
    private String homepage;
    private String created;
    private List<Contributor> contributors = List.of();
    private List<String> keywords = List.of();
    private String image;
    private List<License> licenses = List.of();
    private List<PackageSource> sources = List.of();
    private List<ResourceDescriptor> resources = List.of();

    public Builder name(String v)        { this.name = v;        return this; }
    public Builder profile(String v)     { this.profile = v;     return this; }
    public Builder id(String v)          { this.id = v;          return this; }
    public Builder title(String v)       { this.title = v;       return this; }
    public Builder description(String v) { this.description = v; return this; }
    public Builder homepage(String v)    { this.homepage = v;    return this; }
    public Builder created(String v)     { this.created = v;     return this; }
    public Builder contributors(List<Contributor> v) { this.contributors = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder keywords(List<String> v)          { this.keywords = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder image(String v) { this.image = v; return this; }
    public Builder licenses(List<License> v)       { this.licenses = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder sources(List<PackageSource> v)  { this.sources = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder resources(List<ResourceDescriptor> v) { this.resources = v == null ? List.of() : List.copyOf(v); return this; }

    public DataPackageDescriptor build() {
      return new DataPackageDescriptor(name, profile, id, title, description, homepage, created,
                                       contributors, keywords, image, licenses, sources, resources);
    }
  }
}
