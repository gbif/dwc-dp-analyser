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
package org.gbif.dp.validator.dwcdp;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what {@link DwcDpProfileValidatorTest} does not: that both JSON Schema dialects in
 * play across DwC-DP profile versions (Draft 4 for 0.1, Draft 2020-12 for 1.0_DEV) load
 * successfully through the same {@link DwcDpProfileSchemaLoader}, and that the Frictionless
 * keyword registration on {@link DwcDpProfileSchemaLoader#DIALECT_REGISTRY} actually silences
 * the {@code UnknownKeywordFactory} warning rather than just changing behavior incidentally.
 *
 * <p>Uses the real classpath schemas via {@link DwcDpProfileRegistry}'s default constructor —
 * not a fake/reduced fixture — because the thing under test is precisely whether cross-dialect
 * resolution against the actual bundled 0.1 and 1.0_DEV schemas still works.
 */
class DwcDpProfileSchemaLoaderTest {

  private static final String PROFILE_0_1 = "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json";
  private static final String PROFILE_1_0_DEV =
    "https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json";

  @Test
  void draft4ProfileLoadsSuccessfully() {
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry();

    Optional<DwcDpSchemaVersion> version = registry.resolve(PROFILE_0_1);

    assertTrue(version.isPresent());
    assertNotNull(version.get().profileSchema(), "0.1 profile schema (Draft 4) failed to load");
  }

  @Test
  void draft202012ProfileLoadsSuccessfully() {
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry();

    Optional<DwcDpSchemaVersion> version = registry.resolve(PROFILE_1_0_DEV);

    assertTrue(version.isPresent());
    assertNotNull(version.get().profileSchema(),
                  "1.0_DEV profile schema (Draft 2020-12) failed to load — "
                  + "regression check for forcing a single dialect onto both versions");
  }

  @Test
  void draft4SchemaStillValidatesAfterKeywordRegistration() {
    // Regression guard: adding keywords to the Draft 4 dialect must not disturb ordinary
    // Draft 4 validation behavior (e.g. accidentally clearing existing keywords instead of
    // adding to them).
    Schema schema = new DwcDpProfileRegistry().resolve(PROFILE_0_1).orElseThrow().profileSchema();

    String validDescriptor = """
        {
          "name": "valid-dwcdp",
          "profile": "%s",
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
        """.formatted(PROFILE_0_1);

    List<Error> errors = schema.validate(validDescriptor, InputFormat.JSON);

    assertTrue(errors.isEmpty(), "Expected a conformant descriptor to validate cleanly, got: " + errors);
  }

  @Test
  void loadingBothDialectsProducesNoUnknownKeywordWarnings() {
    Logger unknownKeywordLogger =
      (Logger) LoggerFactory.getLogger("com.networknt.schema.keyword.UnknownKeywordFactory");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    unknownKeywordLogger.addAppender(appender);

    try {
      // Fresh registry construction to force schema (re)loading through the appender's window.
      new DwcDpProfileRegistry();
    } finally {
      unknownKeywordLogger.detachAppender(appender);
    }

    List<String> unknownKeywordWarnings = appender.list.stream()
      .map(ILoggingEvent::getFormattedMessage)
      .filter(message -> message != null && message.contains("Unknown keyword"))
      .toList();

    assertEquals(List.of(), unknownKeywordWarnings,
                 "Expected no 'Unknown keyword' warnings for Frictionless keywords "
                 + "(propertyOrder/context/examples/options) on either dialect");
  }
}
