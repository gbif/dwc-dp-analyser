package org.gbif.dp.analysis.api;

import java.util.List;
import java.util.Objects;

public record DatapackageAnalysisResult(
  List<ResourceAnalysisResult> resourceAnalysisResults
) {
  public static boolean isValid(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults.stream()
      .allMatch(ResourceAnalysisResult::isValid);
  }

  public static List<ForeignKeyViolation> foreignKeyViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults.stream()
      .flatMap(rar -> rar.foreignKeyViolations().stream())
      .toList();
  }

  public static List<PrimaryKeyViolation> primaryKeyViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults.stream()
      .map(ResourceAnalysisResult::primaryKeyViolation)
      .filter(Objects::nonNull)
      .toList();
  }

  public static List<DataTypeViolation> dataTypeViolations(DatapackageAnalysisResult result) {
    return result.resourceAnalysisResults.stream()
      .flatMap(rar -> rar.dataTypeViolations().stream())
      .toList();
  }

}
