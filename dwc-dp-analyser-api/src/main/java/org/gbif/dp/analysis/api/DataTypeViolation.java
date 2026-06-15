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
package org.gbif.dp.analysis.api;

import java.util.List;

/**
 * Reports rows in a resource where a column value does not match the declared Frictionless type.
 *
 * @param resource      name of the resource containing the violation
 * @param field         column name
 * @param declaredType  the Frictionless type declared in the schema (e.g. "integer", "date")
 * @param violationCount total number of non-null values that fail the cast
 * @param sampleValues  a small list of actual values that could not be cast
 */
public record DataTypeViolation(
    String resource,
    String field,
    String declaredType,
    long violationCount,
    List<String> sampleValues) {}
