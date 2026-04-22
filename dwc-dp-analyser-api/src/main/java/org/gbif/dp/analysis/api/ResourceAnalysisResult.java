package org.gbif.dp.analysis.api;

import java.util.List;

public record ResourceAnalysisResult(
        String name,
        List<ForeignKeyViolation> foreignKeyViolations,
        PrimaryKeyViolation primaryKeyViolation, List<DataTypeViolation> dataTypeViolations,
        List<ColumnStatistics> columnAnalyses,
        long totalRows
) {

    public boolean isValid() {
        boolean validForeignKeys = foreignKeyViolations == null || foreignKeyViolations.isEmpty();
        boolean validPrimaryKey = primaryKeyViolation == null;
        boolean validDataType = dataTypeViolations == null || dataTypeViolations.isEmpty();
        return validForeignKeys && validPrimaryKey && validDataType;
    }
}
