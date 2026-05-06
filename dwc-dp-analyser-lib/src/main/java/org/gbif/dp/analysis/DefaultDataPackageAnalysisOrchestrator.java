package org.gbif.dp.analysis;

import org.gbif.dp.analysis.api.AnalysisFeature;
import org.gbif.dp.analysis.api.DataAnalyser;
import org.gbif.dp.analysis.api.DataPackageAnalysisOrchestrator;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
import org.gbif.dp.analysis.api.ValidationOptions;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.dwcdp.DwcDpDescriptorValidator;
import org.gbif.dp.validator.eml.EmlValidator;
import org.gbif.dp.validator.frictionless.DescriptorValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

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
    Path descriptorPath, ValidationOptions options, List<AnalysisFeature> features)
    throws IOException, SQLException {

    // ── 1. Descriptor validation ──────────────────────────────────────────
    DescriptorValidationResult descriptorResult = DescriptorValidationResult.ok();
    if (features.contains(AnalysisFeature.DESCRIPTOR_VALIDATION)) {
      log.info("Validating descriptor: {}", descriptorPath);
      descriptorResult = descriptorValidator.validate(descriptorPath);

      if (!descriptorResult.canProceedToDataAnalysis()) {
        log.warn("Descriptor validation blocked data analysis — {} blocking error(s)",
          DescriptorValidationResult.errors(descriptorResult).size());
        EmlValidationResult emlResult = runEmlValidation(descriptorPath, features);
        return new DatapackageAnalysisResult(descriptorResult, emlResult, List.of());
      }
    }

    // ── 2. EML validation ─────────────────────────────────────────────────
    EmlValidationResult emlResult = runEmlValidation(descriptorPath, features);

    // ── 3. Data analysis ──────────────────────────────────────────────────
    log.info("Starting data analysis: {}", descriptorPath);
    var resourceResults = dataAnalyser.analyse(descriptorPath, options, features);

    return new DatapackageAnalysisResult(descriptorResult, emlResult, resourceResults);
  }

  private EmlValidationResult runEmlValidation(Path descriptorPath, List<AnalysisFeature> features) {
    if (features.contains(AnalysisFeature.EML_VALIDATION)) {
      log.info("Validating EML alongside: {}", descriptorPath);
      return emlValidator.validate(descriptorPath);
    }
    return EmlValidationResult.absent();
  }
}
