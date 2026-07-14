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
package org.gbif.dp.common.descriptor;

import org.gbif.dp.descriptor.DataPackageDescriptor;
import org.gbif.dp.descriptor.DialectDescriptor;
import org.gbif.dp.descriptor.ResourceDescriptor;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JacksonDataPackageParserTest {

  @Test
  void resourceInheritsRootDialectWhenNoneDefinedOnResource() throws IOException {
    String json = """
        {
          "name": "test-package",
          "dialect": {
            "delimiter": "\\t",
            "quoteChar": "'"
          },
          "resources": [
            {
              "name": "occurrences",
              "path": "occurrences.csv"
            }
          ]
        }
        """;

    DataPackageDescriptor result = new JacksonDataPackageParser().parse(json);

    assertEquals(1, result.resources().size());
    ResourceDescriptor resource = result.resources().get(0);
    assertEquals("occurrences.csv", resource.paths().get(0));
    assertTrue(resource.schema().fields().isEmpty());

    DialectDescriptor dialect = resource.dialect();
    assertNotNull(dialect);
    assertEquals("\t", dialect.delimiter());
    assertEquals("'", dialect.quoteChar());
  }

  @Test
  void parsesForeignKeysAndWeakForeignKeysWithPredicate() throws IOException {
    String json = """
        {
          "name": "test-package",
          "resources": [
            {
              "name": "occurrence",
              "path": "occurrence.csv",
              "schema": {
                "fields": [],
                "primaryKey": "occurrence_pk",
                "weakPrimaryKey": "occurrenceID",
                "foreignKeys": [
                  { "fields": "event_fk", "predicate": "happened during",
                    "reference": { "resource": "event", "fields": "event_pk" } }
                ],
                "weakForeignKeys": [
                  { "fields": "recordedByID", "predicate": "recorded by",
                    "reference": { "resource": "agent", "fields": "agentID" } }
                ]
              }
            }
          ]
        }
        """;

    DataPackageDescriptor result = new JacksonDataPackageParser().parse(json);
    ResourceDescriptor resource = result.resources().get(0);

    assertEquals(List.of("occurrence_pk"), resource.schema().primaryKey().keys());
    assertEquals(List.of("occurrenceID"), resource.schema().weakPrimaryKey().keys());
    assertEquals("event", resource.schema().foreignKeys().get(0).reference().resource());
    assertEquals("happened during", resource.schema().foreignKeys().get(0).predicate());
    assertEquals("agent", resource.schema().weakForeignKeys().get(0).reference().resource());
  }
}
