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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * Shared networknt {@link Schema} loading logic — builds a {@link SchemaRegistry} that
 * resolves the Frictionless base schema and one TDWG profile schema from the classpath,
 * with no network access.
 */
final class DwcDpProfileSchemaLoader {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileSchemaLoader.class);

  private static final String FRICTIONLESS_SCHEMA_BASE_URL = "https://specs.frictionlessdata.io/schemas";
  private static final String FRICTIONLESS_SCHEMA_BASE_REF = "classpath:schemas";

  private DwcDpProfileSchemaLoader() {}

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
      SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4,
                                                                  builder -> builder.schemaIdResolvers(schemaIdResolvers -> schemaIdResolvers
                                                                    .mapPrefix(FRICTIONLESS_SCHEMA_BASE_URL, FRICTIONLESS_SCHEMA_BASE_REF)
                                                                    .mapPrefix(tdwgSchemaBaseUrl, tdwgSchemaBaseRef)
                                                                  )
      );
      Schema schema = registry.getSchema(SchemaLocation.of(profileUri));
      log.debug("Loaded DwC-DP profile schema: {}", profileUri);
      return schema;
    } catch (Exception e) {
      log.error("Failed to load DwC-DP profile schema {}: {}", profileUri, e.getMessage(), e);
      return null;
    }
  }
}
