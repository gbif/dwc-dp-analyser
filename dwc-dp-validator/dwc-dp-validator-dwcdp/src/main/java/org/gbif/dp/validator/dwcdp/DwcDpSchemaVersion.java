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

import com.networknt.schema.Schema;

/**
 * Everything needed to validate a descriptor against one DwC-DP profile version.
 *
 * @param profileUri        the version's canonical profile URI
 * @param profileSchema     the loaded JSON Schema for the profile layer, or {@code null} if
 *                          loading failed at startup
 * @param tableSchemaSource where this version's {@code index.json} and
 *                          {@code table-schemas/} are read from
 * @param bundled           {@code true} for versions shipped in {@code dwc-dp-schemas};
 *                          {@code false} for versions fetched remotely at runtime
 */
public record DwcDpSchemaVersion(
  String profileUri,
  Schema profileSchema,
  TableSchemaSource tableSchemaSource,
  boolean bundled) {

  /** Bundled version whose table schemas live under {@code tableSchemaClasspathBase}. */
  public DwcDpSchemaVersion(String profileUri, Schema profileSchema, String tableSchemaClasspathBase) {
    this(profileUri, profileSchema, TableSchemaSource.classpath(tableSchemaClasspathBase), true);
  }

  /**
   * @return the classpath base for bundled versions, {@code null} for remote ones
   * @deprecated use {@link #tableSchemaSource()}
   */
  @Deprecated
  public String tableSchemaClasspathBase() {
    return tableSchemaSource instanceof TableSchemaSource.Classpath cp ? cp.base() : null;
  }
}
