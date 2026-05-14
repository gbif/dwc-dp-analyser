package org.gbif.dp.validator.dwcdp;

import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DwcDpProfileValidatorTest {

  final DwcDpProfileValidator validator = new DwcDpProfileValidator();

  @TempDir Path tempDir;

  @Test
  void shouldReturnNoIssuesForConformantDescriptor() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "valid-dwcdp",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource",
            "mediatype": "text/csv",
            "schema": {
              "fields": [{
                "name": "eventID",
                "title": "Event ID",
                "description": "An identifier for a dwc:Event.",
                "type": "string",
                "format": "default",
                "dcterms:isVersionOf": "http://rs.tdwg.org/dwc/terms/eventID"
              }]
            }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(issues.stream().noneMatch(
        i -> i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION),
      "Expected no JSON Schema violations, got: " + issues);
  }

  @Test
  void shouldReportViolationWhenProfileMissing() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "no-profile",
          "resources": [{ "name": "data", "path": "data.csv" }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(
      issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && ("/".equals(i.location()) || "".equals(i.location()))
          && i.message().contains("required")
          && i.message().contains("profile")),
      "Expected required 'profile' violation at root, got: " + issues);
  }

  @Test
  void shouldReportViolationWhenRootProfileIsNotAUri() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "bad-profile",
          "profile": "not-a-uri",
          "resources": [{ "name": "data", "path": "data.csv" }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(
      issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && "/profile".equals(i.location())
          && i.message().toLowerCase().contains("uri")),
      "Expected URI format violation at /profile, got: " + issues);
  }

  @Test
  void shouldReportViolationWhenResourceProfileIsNotInEnum() throws Exception {
    Files.writeString(tempDir.resolve("data.csv"), "id\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "bad-resource-profile",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "data.csv",
            "profile": "object"
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(
      issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && "/resources/0/profile".equals(i.location())
          && i.message().toLowerCase().contains("enum")),
      "Expected enum violation at $.resources0.profile, got: " + issues);
  }

  @Test
  void shouldReportViolationWhenDwcResourceMissingRequiredResourceProfile() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-resource-profile",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv"
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(
      issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && i.location() != null && i.location().startsWith("/resources/0")
          && i.message().contains("required")
          && i.message().contains("profile")),
      "Expected required 'profile' violation under /resources/0, got: " + issues);
  }

  @Test
  void shouldReportViolationWhenFieldMissingRequiredDcTermsIsVersionOf() throws Exception {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "missing-field-uri",
          "profile": "http://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json",
          "resources": [{
            "name": "event",
            "path": "event.csv",
            "profile": "tabular-data-resource",
            "mediatype": "text/csv",
            "schema": {
              "fields": [{
                "name": "eventID",
                "title": "Event ID",
                "description": "An identifier for a dwc:Event.",
                "type": "string"
              }]
            }
          }]
        }
        """);

    List<ValidationIssue> issues = validator.validate(tempDir.resolve("datapackage.json"));

    assertTrue(
      issues.stream().anyMatch(i ->
        i.violationType() == DescriptorViolationType.JSON_SCHEMA_VIOLATION
          && i.location() != null && i.location().startsWith("/resources/0/schema/fields/0")
          && i.message().contains("required")
          && i.message().contains("dcterms:isVersionOf")),
      "Expected required 'dcterms:isVersionOf' violation at /resources/0/schema/fields/0, got: " + issues);
  }

  @Test
  void shouldSkipValidationGracefullyWhenSchemaUnavailable() {
    List<ValidationIssue> issues = validator.validate(tempDir.resolve("nonexistent.json"));
    assertNotNull(issues);
  }
}
