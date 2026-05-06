package org.gbif.dp.validator.eml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.EmlValidationResult;
import org.gbif.dp.validator.api.ValidationIssue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the {@code eml.xml} metadata file that may accompany a DwC-DP.
 *
 * <p>Per the DwC-DP spec eml.xml is optional (MAY). Absence is not an error.
 * When present, checks run in order:
 * <ol>
 *   <li>Well-formed XML</li>
 *   <li>Required dataset elements present: title, creator</li>
 *   <li>XSD validation against the bundled EML 2.2.0 schema</li>
 * </ol>
 *
 * <p>Locations use JSON Pointer-style notation adapted for XML
 * (e.g. {@code /dataset/title}).
 *
 * <p>The EML XSD {@link Schema} object is loaded once at class initialisation and
 * reused across all validate calls. {@link Schema} is thread-safe for concurrent
 * {@link Validator} creation per the JAXP spec.
 */
public class EmlValidator {

  private static final Logger log = LoggerFactory.getLogger(EmlValidator.class);

  public static final String EML_FILENAME = "eml.xml";

  private static final String EML_SCHEMA_CLASSPATH = "/eml-2.2.0/xsd/eml.xsd";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Schema EML_SCHEMA = loadSchemaOnce();

  private static Schema loadSchemaOnce() {
    try (InputStream is = EmlValidator.class.getResourceAsStream(EML_SCHEMA_CLASSPATH)) {
      if (is == null) {
        log.warn("Bundled EML 2.2.0 schema not found at classpath:{}. "
            + "XSD validation will be skipped. "
            + "Add eml.xsd to src/main/resources/eml-2.2.0/xsd to enable it.",
          EML_SCHEMA_CLASSPATH);
        return null;
      }
      Schema schema = SchemaFactory
        .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        .newSchema(new StreamSource(is, EML_SCHEMA_CLASSPATH));
      log.debug("EML 2.2.0 schema loaded from classpath");
      return schema;
    } catch (Exception e) {
      log.warn("Failed to load bundled EML schema — XSD validation will be skipped: {}",
        e.getMessage());
      return null;
    }
  }

  public EmlValidationResult validate(Path descriptorPath) {
    Path emlPath = descriptorPath.getParent().resolve(EML_FILENAME);

    if (!Files.exists(emlPath)) {
      log.debug("No eml.xml found alongside {}", descriptorPath);
      return EmlValidationResult.absent();
    }

    log.info("Validating EML: {}", emlPath);
    List<ValidationIssue> issues = new ArrayList<>();

    Document doc = parseXml(emlPath, issues);
    if (doc == null) {
      return EmlValidationResult.of(issues);
    }

    checkRequiredElement(doc, "title", "/dataset/title", issues);
    checkRequiredElement(doc, "creator", "/dataset/creator", issues);

    validateXsd(emlPath, issues);

    return EmlValidationResult.of(issues);
  }

  private Document parseXml(Path emlPath, List<ValidationIssue> issues) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      return builder.parse(emlPath.toFile());
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

  private record XsdError(int line, int column, boolean fatal, String message) {
  }

  private void validateXsd(Path emlPath, List<ValidationIssue> issues) {
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

      try (InputStream is = Files.newInputStream(emlPath)) {
        validator.validate(new StreamSource(is));
      }

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
