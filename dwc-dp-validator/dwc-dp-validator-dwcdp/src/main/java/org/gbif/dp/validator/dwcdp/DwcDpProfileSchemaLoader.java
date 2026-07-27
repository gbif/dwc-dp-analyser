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
package org.gbif.dp.validator.dwcdp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.BasicDialectRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.DialectRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.NonValidationKeyword;

/**
 * Shared networknt {@link Schema} loading logic — builds a {@link SchemaRegistry} that
 * resolves the Frictionless base schema and one TDWG profile schema from the classpath,
 * with no network access.
 */
final class DwcDpProfileSchemaLoader {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileSchemaLoader.class);

  private static final String FRICTIONLESS_SCHEMA_BASE_URL = "https://specs.frictionlessdata.io/schemas";
  private static final String FRICTIONLESS_SCHEMA_BASE_REF = "classpath:schemas";

  /**
   * Frictionless Data Package schemas declare a handful of keywords — {@code propertyOrder},
   * {@code context}, {@code examples}, {@code options} — that are Frictionless-specific
   * conventions rather than JSON Schema vocabulary. Without registering them, networknt logs
   * an {@code UnknownKeywordFactory} WARN for each one on every schema load: harmless (unknown
   * keywords already default to being treated as annotations), but noisy in prod logs.
   * Registering them as {@link NonValidationKeyword} makes that "harmless" explicit and
   * silences the warning without changing validation semantics.
   *
   * <p>DwC-DP profile versions don't share one JSON Schema dialect: 0.1 is Draft 4, 1.0_DEV is
   * Draft 2020-12. Rather than pin the registry to one dialect (which broke 1.0_DEV), each of
   * the two standard dialects that appear across supported versions is overridden — by dialect
   * id, via {@link DialectRegistry} — with these four keywords added. The dialect actually used
   * per schema still comes from that schema's own {@code $schema} keyword; only the *set* of
   * dialects available for override is fixed here.
   *
   * <p>If a future DwC-DP version adopts a dialect not listed here (e.g. Draft 2019-09), add
   * an override for it too, or the warning will resurface for that version's schemas.
   */
  private static final DialectRegistry DIALECT_REGISTRY = new BasicDialectRegistry(
    List.of(
      withFrictionlessKeywords(Dialects.getDraft4()),
      withFrictionlessKeywords(Dialects.getDraft202012())
    )
  );

  private DwcDpProfileSchemaLoader() {}

  private static Dialect withFrictionlessKeywords(Dialect base) {
    return Dialect.builder(base)
      .keyword(new NonValidationKeyword("propertyOrder"))
      .keyword(new NonValidationKeyword("context"))
      .keyword(new NonValidationKeyword("examples"))
      .keyword(new NonValidationKeyword("options"))
      .build();
  }

  /**
   * @param profileUri       the DwC-DP profile's own canonical {@code $id} — what a
   *                         descriptor's {@code profile} field is expected to equal
   * @param tdwgSchemaBaseUrl the URL prefix under which this version's schemas are hosted
   *                          (e.g. {@code https://rs.tdwg.org/dwc-dp})
   * @param tdwgSchemaBaseRef the classpath prefix those URLs map to
   *                          (e.g. {@code classpath:schemas/0.1})
   * @return the loaded {@link Schema}, or {@code null} if loading failed
   */
  static Schema load(String profileUri, String tdwgSchemaBaseUrl, String tdwgSchemaBaseRef) {
    try {
      SchemaRegistry registry = SchemaRegistry.builder()
        .dialectRegistry(DIALECT_REGISTRY)
        .schemaIdResolvers(schemaIdResolvers -> schemaIdResolvers
          .mapPrefix(FRICTIONLESS_SCHEMA_BASE_URL, FRICTIONLESS_SCHEMA_BASE_REF)
          .mapPrefix(tdwgSchemaBaseUrl, tdwgSchemaBaseRef)
        )
        .build();
      Schema schema = registry.getSchema(SchemaLocation.of(profileUri));
      log.debug("Loaded DwC-DP profile schema: {}", profileUri);
      return schema;
    } catch (Exception e) {
      log.error("Failed to load DwC-DP profile schema {}: {}", profileUri, e.getMessage(), e);
      return null;
    }
  }
}
