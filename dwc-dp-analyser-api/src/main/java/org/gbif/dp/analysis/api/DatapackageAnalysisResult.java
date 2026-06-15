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

import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.EmlValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Top-level result of a full DwC-DP analysis run.
 */
public record DatapackageAnalysisResult(
  DescriptorValidationResult descriptorValidation,
  EmlValidationResult emlValidation,
  List<ResourceAnalysisResult> resourceAnalysisResults) {

  /**
   * Overall validity: descriptor must be valid AND all resources must pass data validation.
   * EML issues are informational and do not affect overall validity (eml.xml is optional).
   */
  public static boolean isValid(DatapackageAnalysisResult result) {
    if (!result.descriptorValidation().valid()) {
      return false;
    }
    return result.resourceAnalysisResults().stream()
      .allMatch(ResourceAnalysisResult::isValid);
  }

  public static List<ForeignKeyViolation> foreignKeyViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults().stream()
      .flatMap(rar -> rar.foreignKeyViolations().stream())
      .toList();
  }

  public static List<PrimaryKeyViolation> primaryKeyViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults().stream()
      .map(ResourceAnalysisResult::primaryKeyViolation)
      .filter(Objects::nonNull)
      .toList();
  }

  public static List<DataTypeViolation> dataTypeViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults().stream()
      .flatMap(rar -> rar.dataTypeViolations().stream())
      .toList();
  }
}
