package org.gbif.dp.analysis;

import org.gbif.dp.analysis.api.DatapackageAnalysisResult;
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
 * Decorator over any {@link DataPackageAnalyser} that runs structural validation
 * (descriptor conformance and EML) before delegating to the wrapped analyser.
 *
 * <p>If descriptor validation produces a blocking ERROR, data analysis is skipped entirely
 * and an empty result is returned with the descriptor issues preserved.
 * EML validation never blocks data analysis (eml.xml is optional per the DwC-DP spec).
 *
 * <p>Usage — default setup uses {@link DwcDpDescriptorValidator} (covers Frictionless v1
 * + DwC-DP profile rules) and {@link EmlValidator}:
 * <pre>{@code
 * DataPackageAnalyser analyser = new ValidatingDataPackageAnalyser(
 *     new DuckDbDataPackageAnalyser(...));
 * }</pre>
 *
 * Or supply custom validators for testing or Frictionless-only mode:
 * <pre>{@code
 * DataPackageAnalyser analyser = new ValidatingDataPackageAnalyser(
 *     inner, customDescriptorValidator, customEmlValidator);
 * }</pre>
 */
public class ValidatingDataPackageAnalyser implements DataPackageAnalyser {

  private static final Logger log = LoggerFactory.getLogger(ValidatingDataPackageAnalyser.class);

  private final DataPackageAnalyser delegate;
  private final DescriptorValidator descriptorValidator;
  private final EmlValidator emlValidator;

  /** Default constructor — uses DwcDpDescriptorValidator + EmlValidator. */
  public ValidatingDataPackageAnalyser(DataPackageAnalyser delegate) {
    this(delegate, new DwcDpDescriptorValidator(), new EmlValidator());
  }

  public ValidatingDataPackageAnalyser(
    DataPackageAnalyser delegate,
    DescriptorValidator descriptorValidator,
    EmlValidator emlValidator) {
    this.delegate = delegate;
    this.descriptorValidator = descriptorValidator;
    this.emlValidator = emlValidator;
  }

  @Override
  public DatapackageAnalysisResult analyse(
    Path descriptorPath,
    org.gbif.dp.analysis.api.ValidationOptions options,
    List<org.gbif.dp.analysis.api.AnalysisFeature> analysisFeatures) throws IOException, SQLException {

    // ── Descriptor validation ─────────────────────────────────────────────
    DescriptorValidationResult descriptorResult = DescriptorValidationResult.ok();
    if (analysisFeatures.contains(org.gbif.dp.analysis.api.AnalysisFeature.DESCRIPTOR_VALIDATION)) {
      log.info("Validating descriptor: {}", descriptorPath);
      descriptorResult = descriptorValidator.validate(descriptorPath);

      if (!descriptorResult.canProceedToDataAnalysis()) {
        log.warn("Descriptor validation blocked data analysis — {} blocking error(s)",
          DescriptorValidationResult.errors(descriptorResult).size());
        EmlValidationResult emlResult = maybeValidateEml(descriptorPath, analysisFeatures);
        return new DatapackageAnalysisResult(descriptorResult, emlResult, List.of());
      }
    }

    // ── EML validation ────────────────────────────────────────────────────
    EmlValidationResult emlResult = maybeValidateEml(descriptorPath, analysisFeatures);

    // ── Delegate data analysis ────────────────────────────────────────────
    DatapackageAnalysisResult dataResult = delegate.analyse(descriptorPath, options, analysisFeatures);

    // Merge: replace the delegate's (empty) descriptor/eml results with ours
    return new DatapackageAnalysisResult(
      descriptorResult,
      emlResult,
      dataResult.resourceAnalysisResults());
  }

  private EmlValidationResult maybeValidateEml(Path descriptorPath,
                                               List<org.gbif.dp.analysis.api.AnalysisFeature> features) {
    if (features.contains(org.gbif.dp.analysis.api.AnalysisFeature.EML_VALIDATION)) {
      log.info("Validating EML alongside: {}", descriptorPath);
      return emlValidator.validate(descriptorPath);
    }
    return EmlValidationResult.absent();
  }
}
