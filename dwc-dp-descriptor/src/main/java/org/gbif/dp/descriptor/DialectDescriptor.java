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

import java.util.Locale;

/**
 * Represents a Frictionless CSV Dialect descriptor.
 * Defaults follow RFC 4180 / the Frictionless spec.
 */
public record DialectDescriptor(
  String delimiter,
  String quoteChar,
  String escapeChar,      // null if not set (mutually exclusive with quoteChar)
  boolean doubleQuote,
  String lineTerminator,
  boolean skipInitialSpace,
  String nullSequence     // null if not set
) {

  /** RFC 4180 / Frictionless defaults */
  public static DialectDescriptor defaults() {
    return new Builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static DialectDescriptor fromExtension(String filename) {
    if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".tsv")) {
      return builder().delimiter("\t").build();
    }
    return defaults();
  }

  public static final class Builder {
    private String delimiter       = ",";
    private String quoteChar       = "\"";
    private String escapeChar      = null;
    private boolean doubleQuote    = true;
    private String lineTerminator  = "\r\n";
    private boolean skipInitialSpace = false;
    private String nullSequence    = null;

    public Builder delimiter(String v)        { this.delimiter = v;        return this; }
    public Builder quoteChar(String v)        { this.quoteChar = v;        return this; }
    public Builder escapeChar(String v)       { this.escapeChar = v;       return this; }
    public Builder doubleQuote(boolean v)     { this.doubleQuote = v;      return this; }
    public Builder lineTerminator(String v)   { this.lineTerminator = v;   return this; }
    public Builder skipInitialSpace(boolean v){ this.skipInitialSpace = v; return this; }
    public Builder nullSequence(String v)     { this.nullSequence = v;     return this; }

    public DialectDescriptor build() {
      if (escapeChar != null && quoteChar != null && !quoteChar.isEmpty()) {
        throw new IllegalStateException("escapeChar and quoteChar are mutually exclusive");
      }
      return new DialectDescriptor(
        delimiter, quoteChar, escapeChar, doubleQuote,
        lineTerminator, skipInitialSpace, nullSequence
      );
    }
  }

}
