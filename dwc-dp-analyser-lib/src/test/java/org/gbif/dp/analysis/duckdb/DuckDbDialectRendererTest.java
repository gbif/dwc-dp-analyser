package org.gbif.dp.analysis.duckdb;

import org.gbif.dp.descriptor.DialectDescriptor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuckDbDialectRendererTest {

  DuckDbDialectRenderer renderer = new DuckDbDialectRenderer();

  @Test
  void defaultDialectShouldProduceStandardCsvArgs() {
    String args = renderer.toReadCsvArgs(DialectDescriptor.defaults(), "data.csv");
    assertTrue(args.contains("delim=','"));
    assertTrue(args.contains("quote='\"'"));
    assertTrue(args.contains("header=true"));
    assertTrue(args.contains("sample_size=-1"));
  }

  @Test
  void shouldUseDialectOverExtensionWhenBothPresent() {
    // explicit dialect says comma, but filename is .tsv — dialect wins
    DialectDescriptor d = DialectDescriptor.builder().delimiter(",").build();
    String args = renderer.toReadCsvArgs(d, "data.tsv");
    assertTrue(args.contains("delim=','"));
    assertFalse(args.contains("delim='\\t'"));
  }

  @Test
  void nullDialectShouldFallBackToExtension() {
    String args = renderer.toReadCsvArgs(null, "data.tsv");
    assertTrue(args.contains("delim='\t'"));
  }

  @Test
  void shouldRenderSemicolonDelimiter() {
    DialectDescriptor d = DialectDescriptor.builder().delimiter(";").build();
    String args = renderer.toReadCsvArgs(d, "data.csv");
    assertTrue(args.contains("delim=';'"));
  }

  @Test
  void shouldRenderEscapeCharAndDisableQuote() {
    DialectDescriptor d = DialectDescriptor.builder()
      .quoteChar(null)
      .escapeChar("\\")
      .build();
    String args = renderer.toReadCsvArgs(d, "data.csv");
    assertTrue(args.contains("escape='\\\\'"));
    assertTrue(args.contains("quote=''"));
  }

  @Test
  void shouldRenderNullSequence() {
    DialectDescriptor d = DialectDescriptor.builder().nullSequence("\\N").build();
    String args = renderer.toReadCsvArgs(d, "data.csv");
    assertTrue(args.contains("nullstr='\\\\N'"));
  }

  @Test
  void parquetShouldNotProduceCsvArgs() {
    String query = renderer.buildReadQuery(
      List.of(Path.of("/data/file.parquet")), DialectDescriptor.defaults());
    assertTrue(query.startsWith("read_parquet("));
    assertFalse(query.contains("read_csv_auto"));
  }

  @Test
  void csvShouldProduceReadCsvAutoQuery() {
    String query = renderer.buildReadQuery(
      List.of(Path.of("/data/file.csv")), DialectDescriptor.defaults());
    assertTrue(query.startsWith("read_csv_auto("));
  }
}
