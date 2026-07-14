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
package org.gbif.dp.common.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemDataPackageSourceTest {

  @Test
  void readsDescriptorContent(@TempDir Path tempDir) throws IOException {
    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, "{\"resources\":[]}", StandardCharsets.UTF_8);

    try (DataPackageSource source = new FileSystemDataPackageSource(descriptor)) {
      assertEquals("{\"resources\":[]}", source.readDescriptor());
    }
  }

  @Test
  void opensExistingResource(@TempDir Path tempDir) throws IOException {
    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, "{}", StandardCharsets.UTF_8);
    Files.writeString(tempDir.resolve("occurrence.csv"), "id,name\n1,x\n", StandardCharsets.UTF_8);

    try (DataPackageSource source = new FileSystemDataPackageSource(descriptor)) {
      ResourceResult result = source.openResource("occurrence.csv");
      assertEquals(ResourceResult.Kind.FOUND, result.kind());

      byte[] bytes;
      try (ResourceResult.Found found = (ResourceResult.Found) result) {
        bytes = found.stream().readAllBytes();
      }
      assertArrayEquals("id,name\n1,x\n".getBytes(StandardCharsets.UTF_8), bytes);
    }
  }

  @Test
  void reportsMissingResource(@TempDir Path tempDir) throws IOException {
    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, "{}", StandardCharsets.UTF_8);

    try (DataPackageSource source = new FileSystemDataPackageSource(descriptor)) {
      ResourceResult result = source.openResource("does-not-exist.csv");

      assertEquals(ResourceResult.Kind.MISSING, result.kind());
      assertEquals("does-not-exist.csv", ((ResourceResult.Missing) result).relativePath());
    }
  }

  @Test
  void rejectsResourcePathEscapingBaseDirectory(@TempDir Path tempDir) throws IOException {
    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, "{}", StandardCharsets.UTF_8);

    try (DataPackageSource source = new FileSystemDataPackageSource(descriptor)) {
      ResourceResult result = source.openResource("../../../etc/passwd");

      assertEquals(ResourceResult.Kind.FAILED, result.kind());
    }
  }

  @Test
  void rejectsDescriptorWithoutParentDirectory() {
    Path root = Path.of("/").getRoot();

    assertThrows(IllegalArgumentException.class, () -> new FileSystemDataPackageSource(root));
  }

  @Test
  void closeIsSafeToCallAndIdempotent(@TempDir Path tempDir) throws IOException {
    Path descriptor = tempDir.resolve("datapackage.json");
    Files.writeString(descriptor, "{}", StandardCharsets.UTF_8);

    DataPackageSource source = new FileSystemDataPackageSource(descriptor);
    assertDoesNotThrow(source::close);
    assertDoesNotThrow(source::close); // safe to call twice
  }
}
