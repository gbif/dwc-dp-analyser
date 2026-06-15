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

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.*;

public class DuckDbDialectRenderer {

  public String toReadCsvArgs(DialectDescriptor dialect, String filename) {
    dialect = getOrDefaultOnFilename(dialect, filename);

    StringBuilder sb = new StringBuilder();
    sb.append("delim=").append(sq(dialect.delimiter()));
    sb.append(", header=true");
    sb.append(", sample_size=-1");

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

  public String buildReadQuery(List<Path> paths, DialectDescriptor dialect) {
    String joinedPaths = paths.stream()
      .map(Path::toAbsolutePath)
      .map(Object::toString)
      .map(str -> str.replace("'", "''"))
      .map(str -> "'" + str + "'")
      .collect(Collectors.joining(", "));

    String filename = paths.get(0).toAbsolutePath().toString();

    if (filename.toLowerCase(Locale.ROOT).endsWith(".parquet")) {
      return "read_parquet([" + joinedPaths + "])";
    }
    return "read_csv_auto([" + joinedPaths + "], " + toReadCsvArgs(dialect, filename) + ")";
  }
}
