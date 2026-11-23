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

import org.gbif.dp.common.io.DataPackageSource;
import org.gbif.dp.common.io.ResourceResult;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;
import org.gbif.eml.validation.EmlIssue;
import org.gbif.eml.validation.EmlIssueType;
import org.gbif.eml.validation.EmlSchema;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates a data package's {@code eml.xml}: well-formedness and the schema it declares (EML
 * 2.2.0 when it declares none or an unknown one), through the shared GBIF EML library
 * (gbif-eml-validation, bundled schemas, no network, no DOCTYPE), and the presence of a title and
 * a creator.
 *
 * <p>The issues are those the analyser reported before the shared library: {@code INVALID_XML}
 * (an error), {@code EML_MISSING_TITLE} and {@code EML_MISSING_CREATOR}, and {@code
 * EML_XSD_VIOLATION} (warnings). The library's notes that the default schema was used are not
 * reported, since validating against EML 2.2.0 was the analyser's only behaviour; its GBIF quality
 * rules are not run.
 */
public class EmlValidator {

  private static final Logger log = LoggerFactory.getLogger(EmlValidator.class);

  public static final String EML_FILENAME = "eml.xml";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final org.gbif.eml.validation.EmlValidator LIBRARY =
    org.gbif.eml.validation.EmlValidator.builder()
      .defaultSchema(EmlSchema.EML_2_2_0)
      .qualityRules(false)
      .build();

  /**
   * Validate {@code eml.xml} as exposed by {@code source}.
   *
   * <p>If the resource cannot be opened due to a backend error (not simply absent), it is
   * currently treated the same as absent — eml.xml being optional means we don't want a
   * transient read failure to register as a validation ERROR.
   */
  public EmlValidationResult validate(DataPackageSource source) {
    ResourceResult result = source.openResource(EML_FILENAME);

    if (result.kind() == ResourceResult.Kind.MISSING) {
      log.debug("No {} found", EML_FILENAME);
      return EmlValidationResult.absent();
    }
    if (result.kind() == ResourceResult.Kind.FAILED) {
      ResourceResult.Failed failed = (ResourceResult.Failed) result;
      log.warn("Could not open {}: {}", EML_FILENAME, failed.cause().getMessage());
      return EmlValidationResult.absent();
    }

    byte[] emlBytes;
    try (ResourceResult.Found found = (ResourceResult.Found) result) {
      emlBytes = found.stream().readAllBytes();
    } catch (IOException e) {
      log.warn("Could not read {}: {}", EML_FILENAME, e.getMessage());
      return EmlValidationResult.absent();
    }

    log.debug("Validating EML ({} bytes)", emlBytes.length);
    List<EmlIssue> found = LIBRARY.validate(emlBytes).issues();
    List<ValidationIssue> issues = new ArrayList<>();

    List<EmlIssue> notWellFormed =
      found.stream().filter(i -> i.type() == EmlIssueType.NOT_WELL_FORMED).toList();
    if (!notWellFormed.isEmpty()) {
      notWellFormed.forEach(i -> issues.add(invalidXml(i)));
      return EmlValidationResult.of(issues);
    }

    Document doc = parseXml(emlBytes);
    if (doc != null) {
      checkRequiredElement(doc, "title", "/dataset/title", issues);
      checkRequiredElement(doc, "creator", "/dataset/creator", issues);
    }

    for (EmlIssue issue : found) {
      if (issue.type() == EmlIssueType.SCHEMA_VIOLATION) {
        issues.add(xsdViolation(issue));
      } else {
        log.debug("EML note not reported: {} {}", issue.type(), issue.message());
      }
    }
    return EmlValidationResult.of(issues);
  }

  private static ValidationIssue invalidXml(EmlIssue issue) {
    return issue(
      DescriptorViolationType.INVALID_XML,
      "eml.xml is not well-formed XML: " + issue.message(),
      null,
      issue.line() == null
        ? detail("parseError", issue.message())
        : detail("parseError", issue.message(),
                 "line", String.valueOf(issue.line()),
                 "column", String.valueOf(issue.column())));
  }

  private static ValidationIssue xsdViolation(EmlIssue issue) {
    String line = issue.line() == null ? "" : String.valueOf(issue.line());
    String column = issue.column() == null ? "" : String.valueOf(issue.column());
    return issue(
      DescriptorViolationType.EML_XSD_VIOLATION,
      "XSD error" + (issue.line() == null ? "" : " at line " + issue.line()) + ": " + issue.message(),
      null,
      detail("message", issue.message(),
             "line", line,
             "column", column,
             "fatal", "false"));
  }

  /** The document for the title and creator checks; the library has already accepted it. */
  private static Document parseXml(byte[] emlBytes) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      return builder.parse(new ByteArrayInputStream(emlBytes));
    } catch (Exception e) {
      log.warn("Could not read {} for the title and creator checks: {}", EML_FILENAME, e.getMessage());
      return null;
    }
  }

  private void checkRequiredElement(Document doc, String localName, String location,
                                    List<ValidationIssue> issues) {
    NodeList nodes = doc.getElementsByTagNameNS("*", localName);
    if (nodes.getLength() == 0) {
      nodes = doc.getElementsByTagName(localName);
    }
    DescriptorViolationType type = localName.equals("title")
      ? DescriptorViolationType.EML_MISSING_TITLE
      : DescriptorViolationType.EML_MISSING_CREATOR;

    if (nodes.getLength() == 0 || nodes.item(0).getTextContent().isBlank()) {
      issues.add(issue(type,
                       "EML file is missing a required <" + localName + "> element or it is empty.",
                       location,
                       detail("element", localName)));
    }
  }

  private static ValidationIssue issue(DescriptorViolationType type, String message,
                                       String location, String detail) {
    return ValidationIssue.of(type, message, location, detail);
  }

  private static String detail(String... keyValuePairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
      map.put(keyValuePairs[i], keyValuePairs[i + 1]);
    }
    try {
      return MAPPER.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialize detail to JSON", e);
      return null;
    }
  }
}
