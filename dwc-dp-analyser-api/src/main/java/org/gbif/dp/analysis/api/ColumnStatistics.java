package org.gbif.dp.analysis.api;

public record ColumnStatistics(
        String name,
        long populatedValues,
        long uniqueValues
) {
}
