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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates the {@code eml.xml} metadata file that may accompany a DwC-DP.
 *
 * <p>Per the DwC-DP spec eml.xml is optional (MAY). Absence is not an error. When present,
 * checks run in order: well-formed XML, required elements (title, creator), XSD conformance.
 *
 * <p>{@code eml.xml} is read once via {@link DataPackageSource#openResource(String)} into a
 * byte array, then reused for both the well-formedness parse and XSD validation — avoiding
 * two separate backend round trips for the same file.
 */
public class EmlValidator {

  private static final Logger log = LoggerFactory.getLogger(EmlValidator.class);

  public static final String EML_FILENAME = "eml.xml";

  private static final String EML_SCHEMA_CLASSPATH = "/eml-2.2.0/xsd/eml.xsd";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Schema EML_SCHEMA = loadSchemaOnce();

  private static Schema loadSchemaOnce() {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setResourceResolver((type, namespaceURI, publicId, systemId, baseURI) -> {
        String siblingPath = "/eml-2.2.0/xsd/" + systemId;
        InputStream is = EmlValidator.class.getResourceAsStream(siblingPath);
        if (is == null) {
          log.warn("Could not resolve XSD import: {}", siblingPath);
          return null;
        }
        return new ClasspathLSInput(systemId, is);
      });

      try (InputStream is = EmlValidator.class.getResourceAsStream(EML_SCHEMA_CLASSPATH)) {
        if (is == null) {
          log.warn("Bundled EML 2.2.0 schema not found at classpath:{}. "
                   + "XSD validation will be skipped.", EML_SCHEMA_CLASSPATH);
          return null;
        }
        Schema schema = factory.newSchema(new StreamSource(is, EML_SCHEMA_CLASSPATH));
        log.debug("EML 2.2.0 schema loaded from classpath");
        return schema;
      }
    } catch (Exception e) {
      log.warn("Failed to load bundled EML schema — XSD validation will be skipped: {}",
               e.getMessage());
      return null;
    }
  }

  private static final class ClasspathLSInput implements org.w3c.dom.ls.LSInput {
    private final String systemId;
    private InputStream byteStream;

    ClasspathLSInput(String systemId, InputStream byteStream) {
      this.systemId = systemId;
      this.byteStream = byteStream;
    }

    @Override public InputStream getByteStream() { return byteStream; }
    @Override public void setByteStream(InputStream s) { this.byteStream = s; }
    @Override public String getSystemId() { return systemId; }
    @Override public void setSystemId(String s) {}
    @Override public String getBaseURI() { return null; }
    @Override public void setBaseURI(String s) {}
    @Override public String getPublicId() { return null; }
    @Override public void setPublicId(String s) {}
    @Override public java.io.Reader getCharacterStream() { return null; }
    @Override public void setCharacterStream(java.io.Reader r) {}
    @Override public String getStringData() { return null; }
    @Override public void setStringData(String s) {}
    @Override public String getEncoding() { return null; }
    @Override public void setEncoding(String s) {}
    @Override public boolean getCertifiedText() { return false; }
    @Override public void setCertifiedText(boolean b) {}
  }

  /**
   * Validate {@code eml.xml} as exposed by {@code source}.
   *
   * <p>If the resource cannot be opened due to a backend error (not simply absent), it is
   * currently treated the same as absent — eml.xml being optional means we don't want a
   * transient read failure to register as a validation ERROR. This is a judgment call:
   * a persistent permissions/connectivity problem on a remote source would be silently
   * indistinguishable from "no EML provided." Flagging this rather than deciding it quietly —
   * happy to add a distinct issue type for the FAILED case if that ambiguity matters to you.
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
    List<ValidationIssue> issues = new ArrayList<>();

    Document doc = parseXml(emlBytes, issues);
    if (doc == null) {
      return EmlValidationResult.of(issues);
    }

    checkRequiredElement(doc, "title", "/dataset/title", issues);
    checkRequiredElement(doc, "creator", "/dataset/creator", issues);

    validateXsd(emlBytes, issues);

    return EmlValidationResult.of(issues);
  }

  private Document parseXml(byte[] emlBytes, List<ValidationIssue> issues) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      return builder.parse(new ByteArrayInputStream(emlBytes));
    } catch (SAXParseException e) {
      issues.add(issue(
        DescriptorViolationType.INVALID_XML,
        "eml.xml is not well-formed XML: " + e.getMessage(),
        null,
        detail("parseError", e.getMessage(),
               "line", String.valueOf(e.getLineNumber()),
               "column", String.valueOf(e.getColumnNumber()))));
      return null;
    } catch (Exception e) {
      issues.add(issue(
        DescriptorViolationType.INVALID_XML,
        "eml.xml is not well-formed XML: " + e.getMessage(),
        null,
        detail("parseError", e.getMessage())));
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

  private record XsdError(int line, int column, boolean fatal, String message) {}

  private void validateXsd(byte[] emlBytes, List<ValidationIssue> issues) {
    if (EML_SCHEMA == null) {
      issues.add(issue(
        DescriptorViolationType.EML_XSD_UNAVAILABLE,
        "EML XSD schema is not bundled — XSD validation skipped.",
        null, null));
      return;
    }

    try {
      Validator validator = EML_SCHEMA.newValidator();
      List<XsdError> xsdErrors = new ArrayList<>();
      validator.setErrorHandler(new DefaultHandler() {
        @Override
        public void error(SAXParseException e) {
          xsdErrors.add(new XsdError(e.getLineNumber(), e.getColumnNumber(), false, e.getMessage()));
        }

        @Override
        public void fatalError(SAXParseException e) {
          xsdErrors.add(new XsdError(e.getLineNumber(), e.getColumnNumber(), true, e.getMessage()));
        }
      });

      validator.validate(new StreamSource(new ByteArrayInputStream(emlBytes)));

      for (XsdError xsdError : xsdErrors) {
        issues.add(issue(
          DescriptorViolationType.EML_XSD_VIOLATION,
          (xsdError.fatal() ? "Fatal XSD error" : "XSD error")
          + " at line " + xsdError.line() + ": " + xsdError.message(),
          null,
          detail("message", xsdError.message(),
                 "line", String.valueOf(xsdError.line()),
                 "column", String.valueOf(xsdError.column()),
                 "fatal", String.valueOf(xsdError.fatal()))));
      }
    } catch (SAXException e) {
      issues.add(issue(
        DescriptorViolationType.EML_XSD_VIOLATION,
        "XSD validation error: " + e.getMessage(),
        null, detail("parseError", e.getMessage())));
    } catch (Exception e) {
      issues.add(issue(
        DescriptorViolationType.EML_XSD_VIOLATION,
        "Unexpected error during XSD validation: " + e.getMessage(),
        null, detail("parseError", e.getMessage())));
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
