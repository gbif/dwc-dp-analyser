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

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * Analyses the data within a DwC-DP, producing per-resource results.
 *
 * <p>Implementations are responsible only for data-level checks — foreign keys, primary keys,
 * data type constraints, and column statistics. Descriptor structure and EML validation are
 * out of scope and handled at the orchestration layer.
 *
 * <p>Infrastructure concerns (JDBC URLs, memory limits, thread counts) belong to the
 * concrete implementation and are supplied via its constructor, not this interface.
 *
 * @see DataPackageAnalysisOrchestrator for the top-level entry point that combines
 *      data analysis with structural and metadata validation
 */
public interface DataAnalyser {

  /**
   * Analyse the data resources declared in the descriptor.
   *
   * @param descriptorPath path to {@code datapackage.json}
   * @param options        analysis options (sample size for violation examples)
   * @param features       which data-level features to run; non-data features are ignored
   * @return one result per declared resource, in descriptor order
   */
  List<ResourceAnalysisResult> analyse(
    Path descriptorPath,
    ValidationOptions options,
    List<AnalysisFeature> features) throws IOException, SQLException;
}
