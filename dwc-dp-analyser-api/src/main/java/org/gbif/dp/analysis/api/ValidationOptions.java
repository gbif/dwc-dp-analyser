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
