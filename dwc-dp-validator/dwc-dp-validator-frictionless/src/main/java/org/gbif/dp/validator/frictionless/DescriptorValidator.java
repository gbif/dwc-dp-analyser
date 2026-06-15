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
