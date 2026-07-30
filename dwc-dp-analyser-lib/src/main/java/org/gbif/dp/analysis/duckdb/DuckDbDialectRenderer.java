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
package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.descriptor.DialectDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.*;

public class DuckDbDialectRenderer {

  public String toReadCsvArgs(DialectDescriptor dialect, String filename) {
    return toReadCsvArgs(dialect, filename, false);
  }

  /**
   * @param allVarchar when true, disables type auto-detection entirely (every column loads as
   *                   VARCHAR). Used as a fallback after a {@code Conversion Error} on the default
   *                   (sampled auto-detection) attempt — loading as VARCHAR always succeeds
   *                   regardless of content, since actual type conformance is checked separately
   *                   by {@link DuckDbDataTypeValidator} via {@code TRY_CAST}.
   */
  public String toReadCsvArgs(DialectDescriptor dialect, String filename, boolean allVarchar) {
    dialect = getOrDefaultOnFilename(dialect, filename);

    StringBuilder sb = new StringBuilder();
    sb.append("delim=").append(sq(dialect.delimiter()));
    sb.append(", header=true");
    if (allVarchar) {
      sb.append(", all_varchar=true");
    }

    if (dialect.escapeChar() != null) {
      sb.append(", escape=").append(sq(dialect.escapeChar()));
      sb.append(", quote=''");
    } else if (dialect.quoteChar() != null && !dialect.quoteChar().isEmpty()) {
      sb.append(", quote=").append(sq(dialect.quoteChar()));
    }

    if (!dialect.doubleQuote()) {
      sb.append(", escape=''");
    }

    if (dialect.nullSequence() != null) {
      sb.append(", nullstr=").append(sq(dialect.nullSequence()));
    }

    return sb.toString();
  }

  private static DialectDescriptor getOrDefaultOnFilename(DialectDescriptor dialect, String filename) {
    if (dialect != null) {
      return dialect;
    }
    return DialectDescriptor.fromExtension(filename);
  }

  /**
   * @param locations resolved, openable locations (already joined against the source's
   *                  {@code rawLocation()} via {@link org.gbif.dp.common.io.ResourceLocationResolver}) —
   *                  not raw descriptor-declared paths
   */
  public String buildReadQuery(List<String> locations, DialectDescriptor dialect) {
    return buildReadQuery(locations, dialect, false);
  }

  /**
   * @param allVarchar see {@link #toReadCsvArgs(DialectDescriptor, String, boolean)}. Has no
   *                   effect on Parquet resources, which are already typed by the file format.
   */
  public String buildReadQuery(List<String> locations, DialectDescriptor dialect, boolean allVarchar) {
    String joinedLocations = locations.stream()
      .map(str -> str.replace("'", "''"))
      .map(str -> "'" + str + "'")
      .collect(Collectors.joining(", "));

    String filename = locations.get(0);

    if (filename.toLowerCase(Locale.ROOT).endsWith(".parquet")) {
      return "read_parquet([" + joinedLocations + "])";
    }
    return "read_csv_auto([" + joinedLocations + "], " + toReadCsvArgs(dialect, filename, allVarchar) + ")";
  }
}
