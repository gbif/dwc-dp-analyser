package org.gbif.dp.analysis.api;

import java.util.List;

public record ResourceAnalysisResult(
        String name,
        List<ForeignKeyViolation> foreignKeyViolations,
        PrimaryKeyViolation primaryKeyViolation,
        List<DataTypeViolation> dataTypeViolations,
        List<ColumnStatistics> columnAnalyses,
        long totalRows
) {

    public static boolean isValid(ResourceAnalysisResult result) {
        boolean validForeignKeys = result.foreignKeyViolations == null || result.foreignKeyViolations.isEmpty();
        boolean validPrimaryKey = result.primaryKeyViolation == null;
        boolean validDataType = result.dataTypeViolations == null || result.dataTypeViolations.isEmpty();
        return validForeignKeys && validPrimaryKey && validDataType;
    }
}
