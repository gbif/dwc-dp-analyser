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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a descriptor's {@code profile} URI to the {@link DwcDpSchemaVersion} that knows
 * how to validate it.
 *
 * <p>Known versions are a hardcoded list rather than classpath-discovered — with only two
 * versions today, a manifest-driven registry would be extra machinery for no current payoff.
 * Worth revisiting if the number of supported versions grows enough that adding one without
 * a code change becomes valuable.
 */
public class DwcDpProfileRegistry {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileRegistry.class);

  /** Shared, unversioned classpath root — the version comes from the profile URI's own
   *  suffix (e.g. "/0.1/dwc-dp-profile.json"), not from this ref. Do not append a version
   *  segment here, or you'll double it (see incident: schemas/0.1/0.1/...). */
  private static final String SCHEMA_CLASSPATH_REF = "classpath:schemas";

  /** Registration for one known DwC-DP profile version. */
  record VersionConfig(String profileUri, String tdwgSchemaBaseUrl, String tableSchemaClasspathBase) {}

  private static final List<VersionConfig> KNOWN_VERSIONS = List.of(
    new VersionConfig(
      "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json",
      "https://rs.tdwg.org/dwc-dp",
      "/schemas/0.1"),
    new VersionConfig(
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json",
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp",
      "/schemas/1.0_DEV")
  );

  private final Map<String, DwcDpSchemaVersion> byProfileUri;

  public DwcDpProfileRegistry() {
    this(KNOWN_VERSIONS);
  }

  DwcDpProfileRegistry(List<VersionConfig> versions) {
    Map<String, DwcDpSchemaVersion> map = new LinkedHashMap<>();
    for (VersionConfig config : versions) {
      var schema = DwcDpProfileSchemaLoader.load(
        config.profileUri(), config.tdwgSchemaBaseUrl(), SCHEMA_CLASSPATH_REF);
      if (schema == null) {
        log.warn("Skipping DwC-DP version {} — profile schema failed to load", config.profileUri());
      }
      log.debug("Loaded DwC-DP profile schema: {}", schema);
      map.put(config.profileUri(),
              new DwcDpSchemaVersion(config.profileUri(), schema, config.tableSchemaClasspathBase()));
    }
    this.byProfileUri = Map.copyOf(map);
  }

  public Optional<DwcDpSchemaVersion> resolve(String profileUri) {
    return Optional.ofNullable(byProfileUri.get(profileUri));
  }
}
