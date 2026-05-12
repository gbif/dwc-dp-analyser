package org.gbif.dp.descriptor;

import org.gbif.dp.descriptor.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
