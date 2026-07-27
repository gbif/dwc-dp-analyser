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
 *
 * <p>A version can also declare {@code aliasProfileUris} — other profile URIs that should
 * resolve to the exact same {@link DwcDpSchemaVersion} (same loaded {@code Schema} instance,
 * same table-schema classpath base). This exists for the 1.0 / 1.0_DEV situation: some
 * datasets already declare the eventual production profile URI
 * ({@code https://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json}) even though only the
 * prerelease 1.0_DEV schemas exist on the classpath today. Aliasing re-uses the already-loaded
 * 1.0_DEV schema under that extra key instead of trying to load a second copy from a classpath
 * path that doesn't exist — the version segment in the classpath is derived from the profile
 * URI's own tail (see {@link DwcDpProfileSchemaLoader}), so a genuinely separate
 * {@code VersionConfig} for "1.0" would require duplicating schema resources under both
 * {@code /schemas/1.0} and {@code /schemas/1.0_DEV}. Remove the alias once 1.0 is officially
 * published with its own schema files.
 */
public class DwcDpProfileRegistry {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileRegistry.class);

  /** Shared, unversioned classpath root — the version comes from the profile URI's own
   *  suffix (e.g. "/0.1/dwc-dp-profile.json"), not from this ref. Do not append a version
   *  segment here, or you'll double it (see incident: schemas/0.1/0.1/...). */
  private static final String SCHEMA_CLASSPATH_REF = "classpath:schemas";

  /**
   * Registration for one known DwC-DP profile version.
   *
   * @param aliasProfileUris additional profile URIs that resolve to this same version without
   *                         a separate schema load — see class-level Javadoc.
   */
  record VersionConfig(
    String profileUri,
    String tdwgSchemaBaseUrl,
    String tableSchemaClasspathBase,
    List<String> aliasProfileUris) {

    VersionConfig(String profileUri, String tdwgSchemaBaseUrl, String tableSchemaClasspathBase) {
      this(profileUri, tdwgSchemaBaseUrl, tableSchemaClasspathBase, List.of());
    }
  }

  private static final List<VersionConfig> KNOWN_VERSIONS = List.of(
    new VersionConfig(
      "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json",
      "https://rs.tdwg.org/dwc-dp",
      "/schemas/0.1"),
    new VersionConfig(
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json",
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp",
      "/schemas/1.0_DEV",
      // TODO: remove once DwC-DP 1.0 is officially published with its own schema files.
      List.of("https://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json"))
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

      DwcDpSchemaVersion version =
        new DwcDpSchemaVersion(config.profileUri(), schema, config.tableSchemaClasspathBase());
      map.put(config.profileUri(), version);

      for (String alias : config.aliasProfileUris()) {
        log.debug("Aliasing DwC-DP profile '{}' -> '{}'", alias, config.profileUri());
        map.put(alias, version);
      }
    }
    this.byProfileUri = Map.copyOf(map);
  }

  public Optional<DwcDpSchemaVersion> resolve(String profileUri) {
    return Optional.ofNullable(byProfileUri.get(profileUri));
  }
}
