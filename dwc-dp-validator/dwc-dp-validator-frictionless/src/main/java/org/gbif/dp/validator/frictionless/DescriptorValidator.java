package org.gbif.dp.validator.frictionless;

import org.gbif.dp.validator.api.DescriptorValidationResult;

import java.nio.file.Path;

/**
 * Validates the structural conformance of a datapackage.json descriptor.
 */
public interface DescriptorValidator {

  /**
   * Validate the descriptor at the given path.
   *
   * @param descriptorPath path to datapackage.json
   * @return result containing all issues found and whether data analysis can proceed
   */
  DescriptorValidationResult validate(Path descriptorPath);
}
