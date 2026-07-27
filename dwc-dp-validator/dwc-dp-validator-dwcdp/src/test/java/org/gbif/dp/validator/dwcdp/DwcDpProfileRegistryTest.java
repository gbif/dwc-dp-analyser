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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DwcDpProfileRegistryTest {

  private static final String CANONICAL_URI = "https://example.org/dwc-dp/1.0_DEV/dwc-dp-profile.json";
  private static final String ALIAS_URI = "https://example.org/dwc-dp/1.0/dwc-dp-profile.json";
  private static final String OTHER_URI = "https://example.org/dwc-dp/0.1/dwc-dp-profile.json";
  private static final String UNKNOWN_URI = "https://example.org/dwc-dp/9.9/dwc-dp-profile.json";

  /**
   * Uses fake classpath bases that don't resolve to real schema resources — the registry's
   * schema-loading failure path (log + null schema) is exercised elsewhere; this test only
   * cares about identity resolution of {@link DwcDpSchemaVersion} instances across a
   * canonical/alias pair, which does not depend on the schema having actually loaded.
   */
  private static DwcDpProfileRegistry registryWithAlias() {
    List<DwcDpProfileRegistry.VersionConfig> versions = List.of(
      new DwcDpProfileRegistry.VersionConfig(
        CANONICAL_URI,
        "https://example.org/dwc-dp",
        "/schemas/1.0_DEV",
        List.of(ALIAS_URI)),
      new DwcDpProfileRegistry.VersionConfig(
        OTHER_URI,
        "https://example.org/dwc-dp",
        "/schemas/0.1")
    );
    return new DwcDpProfileRegistry(versions);
  }

  @Test
  void resolvesCanonicalUri() {
    DwcDpProfileRegistry registry = registryWithAlias();

    Optional<DwcDpSchemaVersion> resolved = registry.resolve(CANONICAL_URI);

    assertTrue(resolved.isPresent());
    assertEquals(CANONICAL_URI, resolved.get().profileUri());
  }

  @Test
  void resolvesAliasUriToSameVersionInstanceAsCanonical() {
    DwcDpProfileRegistry registry = registryWithAlias();

    Optional<DwcDpSchemaVersion> canonical = registry.resolve(CANONICAL_URI);
    Optional<DwcDpSchemaVersion> alias = registry.resolve(ALIAS_URI);

    assertTrue(canonical.isPresent());
    assertTrue(alias.isPresent());
    // Same instance, not just equal fields: proves the alias re-uses the already-loaded
    // schema rather than attempting a second load from a classpath path that doesn't exist.
    assertSame(canonical.get(), alias.get());
  }

  @Test
  void aliasResolutionPreservesCanonicalProfileUri() {
    DwcDpProfileRegistry registry = registryWithAlias();

    Optional<DwcDpSchemaVersion> alias = registry.resolve(ALIAS_URI);

    assertTrue(alias.isPresent());
    // The resolved version still reports its own canonical URI, not the alias used to look
    // it up — callers comparing descriptor.profile() against version.profileUri() can detect
    // that an alias was used, which is what a future "not yet officially published" advisory
    // issue would key off.
    assertEquals(CANONICAL_URI, alias.get().profileUri());
  }

  @Test
  void versionWithoutAliasesIsUnaffected() {
    DwcDpProfileRegistry registry = registryWithAlias();

    Optional<DwcDpSchemaVersion> resolved = registry.resolve(OTHER_URI);

    assertTrue(resolved.isPresent());
    assertEquals(OTHER_URI, resolved.get().profileUri());
  }

  @Test
  void unknownProfileUriResolvesEmpty() {
    DwcDpProfileRegistry registry = registryWithAlias();

    assertFalse(registry.resolve(UNKNOWN_URI).isPresent());
  }

  @Test
  void defaultConstructorAliasesProductionOneDotZeroUriToOneDotZeroDev() {
    // Exercises the real KNOWN_VERSIONS table (default no-arg constructor) rather than a
    // fake VersionConfig list, to catch drift if the production alias entry is ever edited
    // or removed from DwcDpProfileRegistry itself.
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry();

    Optional<DwcDpSchemaVersion> devVersion =
      registry.resolve("https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json");
    Optional<DwcDpSchemaVersion> productionAlias =
      registry.resolve("https://rs.tdwg.org/dwc-dp/1.0/dwc-dp-profile.json");

    assertTrue(devVersion.isPresent());
    assertTrue(productionAlias.isPresent());
    assertSame(devVersion.get(), productionAlias.get());
  }
}
