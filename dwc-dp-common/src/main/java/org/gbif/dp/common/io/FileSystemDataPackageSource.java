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

/**
 * {@link DataPackageSource} backed by the local filesystem.
 *
 * <p>The path passed to the constructor is expected to point directly at the
 * {@code datapackage.json} file. All resource lookups in {@link #openResource(String)} are
 * resolved relative to that file's parent directory, matching how {@code path} entries in a
 * Data Package resource are interpreted per the Frictionless spec.
 *
 * <p>This is the default and, for now, only source used by the community-facing CLI, which
 * is expected to run against local checkouts of a data package.
 *
 * <p>{@link #close()} is a no-op — nothing is held open at the source level. Individual
 * {@link ResourceResult.Found} streams returned by {@link #openResource(String)} are the
 * caller's responsibility to close, independent of this source's lifecycle.
 */
public class FileSystemDataPackageSource implements DataPackageSource {

  private final Path descriptorPath;
  private final Path baseDirectory;

  public FileSystemDataPackageSource(Path descriptorPath) {
    this.descriptorPath = descriptorPath.toAbsolutePath().normalize();
    Path parent = this.descriptorPath.getParent();
    if (parent == null) {
      throw new IllegalArgumentException(
        "descriptorPath must have a parent directory: " + descriptorPath);
    }
    this.baseDirectory = parent;
  }

  @Override
  public String readDescriptor() throws IOException {
    return Files.readString(descriptorPath, StandardCharsets.UTF_8);
  }

  @Override
  public ResourceResult openResource(String relativePath) {
    if (ResourcePathTraversal.containsTraversal(relativePath)) {
      return new ResourceResult.Failed(
        relativePath, new IOException("Path traversal is not permitted: " + relativePath));
    }

    Path resolved = baseDirectory.resolve(relativePath).normalize();
    if (!resolved.startsWith(baseDirectory)) {
      return new ResourceResult.Failed(
        relativePath,
        new IOException("Resolved path escapes data package base directory: " + resolved));
    }

    if (!Files.exists(resolved)) {
      return new ResourceResult.Missing(relativePath);
    }

    try {
      return new ResourceResult.Found(Files.newInputStream(resolved));
    } catch (IOException e) {
      return new ResourceResult.Failed(relativePath, e);
    }
  }

  @Override
  public String rawLocation() {
    return baseDirectory.toUri().toString();
  }

  @Override
  public void close() {
    // Nothing to release — the local filesystem needs no held-open connection or handle.
  }
}
