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
package org.gbif.dp.analysis.schema;

import org.gbif.dp.analysis.api.AnalysisExecution;
import org.gbif.dp.analysis.api.DatapackageAnalysisResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;

import tools.jackson.databind.node.ObjectNode;

public final class GenerateJsonSchema {

  private static final Path OUTPUT =
    Path.of("analysis-execution.schema.json");

  private GenerateJsonSchema() {
  }

  public static void main(String[] args) throws IOException {
    SchemaGeneratorConfig config =
      new SchemaGeneratorConfigBuilder(
        SchemaVersion.DRAFT_2020_12,
        OptionPreset.PLAIN_JSON)
        .with(new JacksonSchemaModule())
        .build();

    SchemaGenerator generator = new SchemaGenerator(config);

    ObjectNode schema =
      generator.generateSchema(
        AnalysisExecution.class,
        DatapackageAnalysisResult.class);

    Files.writeString(
      OUTPUT,
      schema.toPrettyString());

    System.out.println(
      "Generated schema: " + OUTPUT.toAbsolutePath());
  }
}
