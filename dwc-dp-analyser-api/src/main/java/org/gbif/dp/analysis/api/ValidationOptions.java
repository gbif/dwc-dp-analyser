package org.gbif.dp.analysis.api;

/**
 * Options controlling the behaviour of a data analysis run.
 *
 * <p>Intentionally minimal — infrastructure concerns such as JDBC URLs and memory limits
 * belong to the concrete analyser implementation, not the interface contract.
 *
 * @param sampleSize maximum number of violation rows to collect as examples in results
 */
public record ValidationOptions(int sampleSize) {

  public static ValidationOptions defaults() {
    return new ValidationOptions(20);
  }
}
