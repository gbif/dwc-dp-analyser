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

import java.util.List;

/**
 * Describes a single field in a Frictionless Data Package resource schema.
 *
 * @param name   the column name
 * @param type   the Frictionless type (string, integer, number, boolean, date, datetime, time, year, object, array, etc.)
 * @param format optional format hint (e.g. "default", "email", "uri", a date pattern, etc.)
 */
public record FieldDescriptor(String name, String type, String format, List<MissingValueDescriptor> missingValues) {

  /** Convenience constructor when no format is specified. */
  public FieldDescriptor(String name, String type, List<MissingValueDescriptor> missingValues) {
    this(name, type, "default", missingValues);
  }
}
