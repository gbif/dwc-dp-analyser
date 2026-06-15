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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JacksonDataPackageParserTest {

  @TempDir
  Path tempDir;

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

    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, json);

    // The resource file does not need to exist — path is only resolved, not read
    DataPackageDescriptor result = new JacksonDataPackageParser().parse(descriptor);

    assertEquals(1, result.resources().size());
    DialectDescriptor dialect = result.resources().get(0).dialect();
    assertNotNull(dialect);
    assertEquals("\t", dialect.delimiter());
    assertEquals("'", dialect.quoteChar());
  }
}
