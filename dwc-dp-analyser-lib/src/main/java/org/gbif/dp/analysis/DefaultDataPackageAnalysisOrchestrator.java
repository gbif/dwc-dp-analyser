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
package org.gbif.dp.analysis;

import org.gbif.dp.analysis.api.AnalysisExecution;
import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.AnalysisMetadata;
import org.gbif.dp.analysis.api.DataAnalyser;
import org.gbif.dp.analysis.api.DataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.common.io.DataPackageSource;
import org.gbif.dp.common.io.DataPackageSources;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.dwcdp.DwcDpDescriptorValidator;
import org.gbif.dp.validator.eml.EmlValidator;
import org.gbif.dp.validator.frictionless.DescriptorValidator;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link DataPackageAnalysisOrchestrator}.
 *
 * <p>Sequences three independent concerns in order:
 * <ol>
 *   <li>Descriptor validation via {@link DescriptorValidator}</li>
 *   <li>EML validation via {@link EmlValidator}</li>
 *   <li>Data analysis via {@link DataAnalyser} — skipped on blocking descriptor errors</li>
 * </ol>
 *
 * <p>Each component knows nothing of the others. The orchestrator assembles their results
 * into a single {@link DatapackageAnalysisResult}.
 *
 * <p>The default constructor uses {@link DwcDpDescriptorValidator} (Frictionless v1 +
 * DwC-DP profile rules) and {@link EmlValidator}. Supply alternatives for testing or
 * Frictionless-only validation.
 */
public class DefaultDataPackageAnalysisOrchestrator implements DataPackageAnalysisOrchestrator {

  private static final Logger log =
    LoggerFactory.getLogger(DefaultDataPackageAnalysisOrchestrator.class);

  private final DataAnalyser dataAnalyser;
  private final DescriptorValidator descriptorValidator;
  private final EmlValidator emlValidator;

  /** Default — DwC-DP descriptor validator + EML validator. */
  public DefaultDataPackageAnalysisOrchestrator(DataAnalyser dataAnalyser) {
    this(dataAnalyser, new DwcDpDescriptorValidator(), new EmlValidator());
  }

  public DefaultDataPackageAnalysisOrchestrator(
    DataAnalyser dataAnalyser,
    DescriptorValidator descriptorValidator,
    EmlValidator emlValidator) {
    this.dataAnalyser = dataAnalyser;
    this.descriptorValidator = descriptorValidator;
    this.emlValidator = emlValidator;
  }

  @Override
  public DatapackageAnalysisResult analyse(
    String descriptorLocation, ValidationOptions options, List<AnalysisFeature> features)
    throws IOException, SQLException {

    DescriptorValidationResult descriptorResult = DescriptorValidationResult.ok();
    EmlValidationResult emlResult = EmlValidationResult.absent();

    try (DataPackageSource source = DataPackageSources.open(descriptorLocation)) {
      if (features.contains(AnalysisFeature.DESCRIPTOR_VALIDATION)) {
        log.debug("Validating descriptor: {}", descriptorLocation);
        descriptorResult = descriptorValidator.validate(source);

        if (!descriptorResult.canProceedToDataAnalysis()) {
          log.warn("Descriptor validation blocked data analysis — {} blocking error(s)",
                   DescriptorValidationResult.errors(descriptorResult).size());
          emlResult = runEmlValidation(source, features);
          return new DatapackageAnalysisResult(descriptorResult, emlResult, List.of());
        }
      }

      emlResult = runEmlValidation(source, features);
    }

    log.debug("Starting data analysis: {}", descriptorLocation);
    var resourceResults = dataAnalyser.analyse(descriptorLocation, options, features);

    return new DatapackageAnalysisResult(descriptorResult, emlResult, resourceResults);
  }

  @Override
  public AnalysisExecution<DatapackageAnalysisResult> analyseWithFullReport(
    String descriptorLocation,
    ValidationOptions options,
    List<AnalysisFeature> features
  ) throws IOException, SQLException {

    LocalDateTime started = LocalDateTime.now(ZoneId.of("UTC"));
    DatapackageAnalysisResult analysisResult = analyse(descriptorLocation, options, features);
    LocalDateTime finished = LocalDateTime.now(ZoneId.of("UTC"));

    AnalysisMetadata analysisMetadata = new AnalysisMetadata(
      started, finished, features, DatapackageAnalysisResult.isValid(analysisResult)
    );

    return new AnalysisExecution<>(
      analysisResult,
      analysisMetadata
    );
  }

  private EmlValidationResult runEmlValidation(DataPackageSource source, List<AnalysisFeature> features) {
    if (features.contains(AnalysisFeature.EML_VALIDATION)) {
      return emlValidator.validate(source);
    }
    return EmlValidationResult.absent();
  }
}
