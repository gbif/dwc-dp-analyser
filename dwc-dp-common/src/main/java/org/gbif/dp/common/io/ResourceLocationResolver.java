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

import java.net.URI;

/**
 * Joins a {@link DataPackageSource#rawLocation()} with a resource's declared relative path,
 * producing a location string a native engine (DuckDB, Spark, etc.) can open directly.
 *
 * <p>Uses {@link URI#resolve(String)} rather than naive string concatenation so the same
 * logic works uniformly whether {@code rawLocation} is a {@code file://}, {@code hdfs://},
 * or {@code s3://} URI — each scheme's own resolution semantics apply without this class
 * needing to know which one it's looking at.
 */
public final class ResourceLocationResolver {

  private ResourceLocationResolver() {}

  public static String resolve(String rawBaseLocation, String relativePath) {
    if (ResourcePathTraversal.containsTraversal(relativePath)) {
      throw new IllegalArgumentException("Path traversal is not permitted: " + relativePath);
    }

    URI base = URI.create(rawBaseLocation);
    // URI#resolve treats a base without a trailing slash as a "file", not a "directory" —
    // e.g. resolving "b" against "file:///a/foo" yields "file:///a/b", silently discarding
    // "foo". Force directory semantics so resolution always appends under the base.
    URI baseDir = base.getPath() != null && base.getPath().endsWith("/")
      ? base
      : URI.create(base + "/");

    return baseDir.resolve(relativePath).toString();
  }
}
