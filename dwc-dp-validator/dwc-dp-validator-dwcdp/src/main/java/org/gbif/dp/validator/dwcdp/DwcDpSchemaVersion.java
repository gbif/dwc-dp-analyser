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
 * @param profileUri              the version's canonical profile URI — matched exactly
 *                                 against a descriptor's {@code profile} field
 * @param profileSchema           the loaded JSON Schema for Layer 1, or {@code null} if
 *                                 loading failed at startup
 * @param tableSchemaClasspathBase classpath prefix for this version's {@code index.json}
 *                                 and {@code table-schemas/} (e.g. {@code /schemas/0.1})
 */
public record DwcDpSchemaVersion(String profileUri, Schema profileSchema, String tableSchemaClasspathBase) {}
