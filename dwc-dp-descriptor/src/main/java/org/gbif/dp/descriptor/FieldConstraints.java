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

/** A single field constraint set (Frictionless Table Schema `constraints`). */
public record FieldConstraints(
  boolean required,
  boolean unique,
  Integer minLength,
  Integer maxLength,
  String pattern,
  java.util.List<String> enumValues,
  Double minimum,
  Double maximum) {

  public static FieldConstraints none() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private boolean required = false;
    private boolean unique = false;
    private Integer minLength;
    private Integer maxLength;
    private String pattern;
    private java.util.List<String> enumValues = java.util.List.of();
    private Double minimum;
    private Double maximum;

    public Builder required(boolean v)       { this.required = v;    return this; }
    public Builder unique(boolean v)         { this.unique = v;      return this; }
    public Builder minLength(Integer v)      { this.minLength = v;   return this; }
    public Builder maxLength(Integer v)      { this.maxLength = v;   return this; }
    public Builder pattern(String v)         { this.pattern = v;     return this; }
    public Builder enumValues(java.util.List<String> v) { this.enumValues = v == null ? java.util.List.of() : java.util.List.copyOf(v); return this; }
    public Builder minimum(Double v)         { this.minimum = v;     return this; }
    public Builder maximum(Double v)         { this.maximum = v;     return this; }

    public FieldConstraints build() {
      return new FieldConstraints(required, unique, minLength, maxLength, pattern, enumValues, minimum, maximum);
    }
  }
}
