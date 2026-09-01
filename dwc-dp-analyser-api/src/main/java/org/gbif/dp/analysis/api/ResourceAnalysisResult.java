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

import java.util.List;

public record ResourceAnalysisResult(
        String name,
        List<ForeignKeyViolation> foreignKeyViolations,
        PrimaryKeyViolation primaryKeyViolation,
        List<DataTypeViolation> dataTypeViolations,
        List<ColumnStatistics> columnStatistics,
        long totalRows
) {

    public static boolean isValid(ResourceAnalysisResult result) {
        boolean validForeignKeys = result.foreignKeyViolations == null || result.foreignKeyViolations.isEmpty();
        boolean validPrimaryKey = result.primaryKeyViolation == null;
        boolean validDataType = result.dataTypeViolations == null || result.dataTypeViolations.isEmpty();
        return validForeignKeys && validPrimaryKey && validDataType;
    }
}
