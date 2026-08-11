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
import java.sql.SQLException;
import java.util.List;

/**
 * Top-level entry point for a full DwC-DP analysis run.
 *
 * <p>Orchestrates structural validation (descriptor, EML) and data analysis, assembling
 * a single {@link DatapackageAnalysisResult} from the outputs of each component.
 *
 * <p>The ordering contract is:
 * <ol>
 *   <li>Descriptor validation (if {@link AnalysisFeature#DESCRIPTOR_VALIDATION} selected)</li>
 *   <li>EML validation (if {@link AnalysisFeature#EML_VALIDATION} selected)</li>
 *   <li>Data analysis — skipped if descriptor validation produced a blocking error</li>
 * </ol>
 *
 * <p>Downstream consumers that only need result types should depend on
 * {@code dwc-dp-analyser-api}. Consumers that need to run analysis should depend on
 * {@code dwc-dp-analyser-lib} for the default implementation.
 */
public interface DataPackageAnalysisOrchestrator {

  /**
   * Run a full analysis of the DwC-DP at {@code descriptorLocation}.
   *
   * @param descriptorLocation identifies where {@code datapackage.json} lives; interpretation
   *                           (local path, HDFS URI, staged temp location, etc.) is entirely
   *                           up to the implementation
   * @param options        analysis options (sample size for violation examples)
   * @param features       which features to run; pass {@link AnalysisFeature#ALL_FEATURES}
   *                       for a complete report
   * @return aggregated result covering descriptor, EML, and per-resource data analysis
   */
  DatapackageAnalysisResult analyse(
    String descriptorLocation,
    ValidationOptions options,
    List<AnalysisFeature> features) throws IOException, SQLException;

  AnalysisExecution<DatapackageAnalysisResult> analyseWithFullReport(
    String descriptorLocation,
    ValidationOptions options,
    List<AnalysisFeature> features
  ) throws IOException, SQLException;
}
