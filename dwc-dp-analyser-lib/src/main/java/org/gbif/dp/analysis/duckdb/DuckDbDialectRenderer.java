package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.descriptor.DialectDescriptor;


import static org.gbif.dp.analysis.duckdb.DuckDbRenderUtils.*;

public class DuckDbDialectRenderer {

  public String toReadCsvArgs(DialectDescriptor dialect, String filename) {
    DialectDescriptor effective = dialect != null
      ? dialect
      : DialectDescriptor.fromExtension(filename);

    StringBuilder sb = new StringBuilder();
    sb.append("delim=").append(sq(effective.delimiter()));
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

}
