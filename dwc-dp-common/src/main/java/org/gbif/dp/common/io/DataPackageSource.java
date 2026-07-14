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

/**
 * Abstraction over "where a Data Package's descriptor and resource files live," decoupling
 * validation and analysis code from any one storage backend (local filesystem, HDFS, S3, or
 * an in-memory/staged source).
 *
 * <p>Extends {@link AutoCloseable} so that sources holding open connections or temporary
 * resources (an HDFS client, a staged/unzipped temp directory) have somewhere to release
 * them. {@link FileSystemDataPackageSource} has nothing to release and its {@link #close()}
 * is a no-op, but the contract is established now so future implementations don't require
 * an interface change — and so callers get in the habit of try-with-resources from the start.
 *
 * <p>{@link #readDescriptor()} is exception-based because a missing or unreadable
 * {@code datapackage.json} is fatal to any validation or analysis run regardless of source
 * type. {@link #openResource(String)} returns a {@link ResourceResult} rather than throwing,
 * since a missing or unreadable individual resource is an ordinary, per-resource validation
 * finding rather than a run-ending failure.
 */
public interface DataPackageSource extends AutoCloseable {

  /**
   * Read the full contents of {@code datapackage.json}.
   *
   * @return the descriptor content, as text
   * @throws IOException if the descriptor cannot be read
   */
  String readDescriptor() throws IOException;

  /**
   * Attempt to open the resource file at {@code relativePath}, as declared in a resource's
   * {@code path} field in the descriptor.
   *
   * @param relativePath path relative to the data package's base location, exactly as it
   *                     appears in the descriptor's {@code resources[].path}
   * @return {@link ResourceResult.Found}, {@link ResourceResult.Missing}, or
   *         {@link ResourceResult.Failed}
   */
  ResourceResult openResource(String relativePath);

  /**
   * The base location this source resolves resource paths against, as a raw string — an
   * absolute filesystem path, an {@code hdfs://} URI, an {@code s3://} URI, etc. Exposed so
   * that native engines (DuckDB, Spark) which can open such locations directly can build their
   * own read path by joining this with a resource's declared {@code path}, without going
   * through {@link #openResource(String)} and buffering through the JVM.
   */
  String rawLocation();

  /**
   * Release any resources held by this source (open connections, temp directories, etc).
   *
   * <p>Overridden narrowly here (no checked {@link Exception}, unlike
   * {@link AutoCloseable#close()}) since no current or foreseeable implementation needs to
   * throw a checked exception on close — an HDFS client close failure, for instance, is
   * something to log, not propagate. Narrowing here also means callers using
   * try-with-resources don't need to catch a checked exception they can't meaningfully act on.
   */
  @Override
  void close();
}
