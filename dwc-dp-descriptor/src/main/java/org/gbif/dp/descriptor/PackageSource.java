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

public record PackageSource(String title, String path, String email) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String title;
    private String path;
    private String email;

    public Builder title(String v) { this.title = v; return this; }
    public Builder path(String v)  { this.path = v;  return this; }
    public Builder email(String v) { this.email = v; return this; }

    public PackageSource build() {
      if (title == null || title.isBlank()) {
        throw new IllegalStateException("PackageSource requires a title");
      }
      return new PackageSource(title, path, email);
    }
  }
}
