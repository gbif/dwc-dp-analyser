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
package org.gbif.dp.validator.eml;

import org.gbif.dp.common.io.FileSystemDataPackageSource;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EmlValidatorTest {

  final EmlValidator validator = new EmlValidator();

  @TempDir
  Path tempDir;

  private FileSystemDataPackageSource source() throws IOException {
    Files.writeString(tempDir.resolve("datapackage.json"), "{}");
    return new FileSystemDataPackageSource(tempDir.resolve("datapackage.json"));
  }

  @Test
  void shouldReturnAbsentWhenNoEmlFile() throws Exception {
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertFalse(result.isPresent());
      assertTrue(result.isValid());
      assertTrue(result.issues().isEmpty());
    }
  }

  @Test
  void shouldErrorOnMalformedXml() throws Exception {
    Files.writeString(tempDir.resolve("eml.xml"), "<eml><unclosed>");
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertTrue(result.isPresent());
      assertFalse(result.isValid());
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.INVALID_XML
                                                     && i.severity() == ValidationIssue.Severity.ERROR
                                                     && i.message().contains("eml.xml")),
                 "Expected INVALID_XML error, got: " + result.issues());
    }
  }

  @Test
  void shouldWarnWhenTitleMissing() throws Exception {
    Files.writeString(tempDir.resolve("eml.xml"), """
      <?xml version="1.0" encoding="UTF-8"?>
      <eml:eml xmlns:eml="https://eml.ecoinformatics.org/eml-2.2.0">
        <dataset>
          <creator><individualName><surName>Smith</surName></individualName></creator>
        </dataset>
      </eml:eml>
      """);
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertTrue(result.isPresent());
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.EML_MISSING_TITLE
                                                     && i.severity() == ValidationIssue.Severity.WARNING
                                                     && "/dataset/title".equals(i.location())),
                 "Expected EML_MISSING_TITLE warning at /dataset/title, got: " + result.issues());
    }
  }

  @Test
  void shouldWarnWhenCreatorMissing() throws Exception {
    Files.writeString(tempDir.resolve("eml.xml"), """
      <?xml version="1.0" encoding="UTF-8"?>
      <eml:eml xmlns:eml="https://eml.ecoinformatics.org/eml-2.2.0">
        <dataset>
          <title>My Dataset</title>
        </dataset>
      </eml:eml>
      """);
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertTrue(result.isPresent());
      assertTrue(result.issues().stream().anyMatch(i ->
                                                     i.violationType() == DescriptorViolationType.EML_MISSING_CREATOR
                                                     && i.severity() == ValidationIssue.Severity.WARNING
                                                     && "/dataset/creator".equals(i.location())),
                 "Expected EML_MISSING_CREATOR warning at /dataset/creator, got: " + result.issues());
    }
  }

  @Test
  void shouldPassWhenRequiredElementsPresent() throws Exception {
    Files.writeString(tempDir.resolve("eml.xml"), """
      <?xml version="1.0" encoding="UTF-8"?>
      <eml:eml xmlns:eml="https://eml.ecoinformatics.org/eml-2.2.0">
        <dataset>
          <title>My Dataset</title>
          <creator><individualName><surName>Smith</surName></individualName></creator>
        </dataset>
      </eml:eml>
      """);
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertTrue(result.isPresent());
      assertTrue(result.issues().stream().noneMatch(i ->
                                                      i.violationType() == DescriptorViolationType.INVALID_XML
                                                      || i.violationType() == DescriptorViolationType.EML_MISSING_TITLE
                                                      || i.violationType() == DescriptorViolationType.EML_MISSING_CREATOR),
                 "Expected no element or parse violations, got: " + result.issues());
    }
  }

  @Test
  void shouldReportXsdViolationOrUnavailableWhenXmlIsInvalidPerSchema() throws Exception {
    Files.writeString(tempDir.resolve("eml.xml"), """
      <?xml version="1.0" encoding="UTF-8"?>
      <notEml>
        <dataset>
          <title>My Dataset</title>
          <creator><individualName><surName>Smith</surName></individualName></creator>
        </dataset>
      </notEml>
      """);
    try (var src = source()) {
      EmlValidationResult result = validator.validate(src);
      assertTrue(result.isPresent());
      boolean xsdViolation = result.issues().stream()
        .anyMatch(i -> i.violationType() == DescriptorViolationType.EML_XSD_VIOLATION);
      boolean xsdUnavailable = result.issues().stream()
        .anyMatch(i -> i.violationType() == DescriptorViolationType.EML_XSD_UNAVAILABLE);
      assertTrue(xsdViolation || xsdUnavailable,
                 "Expected either EML_XSD_VIOLATION or EML_XSD_UNAVAILABLE, got: " + result.issues());

      result.issues().stream()
        .filter(i -> i.violationType() == DescriptorViolationType.EML_XSD_VIOLATION)
        .forEach(i -> {
          assertNotNull(i.detail(), "XSD violation should have structured detail");
          assertTrue(i.detail().contains("line"), "Detail should include line number");
          assertTrue(i.detail().contains("column"), "Detail should include column number");
          assertTrue(i.detail().contains("fatal"), "Detail should include fatal flag");
        });
    }
  }
}
