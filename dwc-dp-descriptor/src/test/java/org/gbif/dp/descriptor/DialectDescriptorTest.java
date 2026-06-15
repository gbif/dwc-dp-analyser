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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DialectDescriptorTest {

  @Test
  void defaultsShouldReflectRfc4180() {
    DialectDescriptor d = DialectDescriptor.defaults();
    assertEquals(",", d.delimiter());
    assertEquals("\"", d.quoteChar());
    assertNull(d.escapeChar());
    assertTrue(d.doubleQuote());
    assertFalse(d.skipInitialSpace());
    assertNull(d.nullSequence());
  }

  @Test
  void fromExtensionShouldInferTabForTsv() {
    DialectDescriptor d = DialectDescriptor.fromExtension("occurrence.tsv");
    assertEquals("\t", d.delimiter());
  }

  @Test
  void fromExtensionShouldReturnDefaultsForCsv() {
    DialectDescriptor d = DialectDescriptor.fromExtension("occurrence.csv");
    assertEquals(",", d.delimiter());
  }

  @Test
  void fromExtensionShouldReturnDefaultsForUnknownExtension() {
    DialectDescriptor d = DialectDescriptor.fromExtension("occurrence.txt");
    assertEquals(",", d.delimiter());
  }

  @Test
  void fromExtensionShouldHandleNull() {
    DialectDescriptor d = DialectDescriptor.fromExtension(null);
    assertEquals(",", d.delimiter());
  }

  @Test
  void fromExtensionShouldBeCaseInsensitive() {
    DialectDescriptor d = DialectDescriptor.fromExtension("occurrence.TSV");
    assertEquals("\t", d.delimiter());
  }

  @Test
  void builderShouldOverrideDefaults() {
    DialectDescriptor d = DialectDescriptor.builder()
      .delimiter(";")
      .skipInitialSpace(true)
      .nullSequence("\\N")
      .build();
    assertEquals(";", d.delimiter());
    assertTrue(d.skipInitialSpace());
    assertEquals("\\N", d.nullSequence());
    // unset fields retain defaults
    assertEquals("\"", d.quoteChar());
    assertTrue(d.doubleQuote());
  }

  @Test
  void builderShouldRejectEscapeCharAndQuoteCharTogether() {
    assertThrows(IllegalStateException.class, () ->
      DialectDescriptor.builder()
        .quoteChar("\"")
        .escapeChar("\\")
        .build()
    );
  }

  @Test
  void builderShouldAllowEscapeCharWhenQuoteCharIsAbsent() {
    DialectDescriptor d = DialectDescriptor.builder()
      .quoteChar(null)
      .escapeChar("\\")
      .build();
    assertEquals("\\", d.escapeChar());
    assertNull(d.quoteChar());
  }
}
