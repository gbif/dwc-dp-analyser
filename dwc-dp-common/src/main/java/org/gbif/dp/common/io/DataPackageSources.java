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

import java.nio.file.Path;

/**
 * Resolves an opaque {@code descriptorLocation} string — as passed through the
 * {@code DataAnalyser}/{@code DataPackageAnalysisOrchestrator} contracts — to a concrete
 * {@link DataPackageSource}.
 *
 * <p>Only local filesystem paths are supported today. Centralizing this dispatch means
 * adding HDFS/S3/HTTP support later is a change in exactly one place, not one per caller.
 */
public final class DataPackageSources {

  private DataPackageSources() {}

  public static DataPackageSource open(String descriptorLocation) {
    // TODO: dispatch on scheme (hdfs://, s3://, http(s)://, etc.) once non-filesystem
    // sources exist. For now every descriptorLocation is treated as a local path.
    return new FileSystemDataPackageSource(Path.of(descriptorLocation));
  }
}
