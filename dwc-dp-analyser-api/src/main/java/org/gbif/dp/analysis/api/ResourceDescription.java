package org.gbif.dp.analysis.api;

import java.util.List;

public record ResourceDescription(
        String name,
        List<ForeignKeyViolation> foreignKeyViolations,
        List<ColumnStatistics> columnAnalyses,
        long totalRows

) {
}
