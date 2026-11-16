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

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Decides which URLs the validator may fetch schemas from when a descriptor declares a
 * DwC-DP {@code profile} that is not bundled in {@code dwc-dp-schemas}.
 *
 * <p>Bundled profiles never touch the network regardless of policy. The policy is consulted
 * for the profile URL itself, its {@code index.json} and table schemas, every transitive
 * {@code $ref}, and every redirect hop.
 *
 * <p>Independent of the policy, the fetcher only ever issues {@code http}/{@code https}
 * requests, and only for normalized, absolute URIs without user-info.
 *
 * <pre>{@code
 * // Defaults + an extra trusted host
 * SchemaFetchPolicy policy = SchemaFetchPolicy.defaults().or(SchemaFetchPolicy.allowHosts("schemas.example.org"));
 * // Fully custom
 * SchemaFetchPolicy policy = uri -> uri.toString().startsWith("https://mirror.example.org/dwc-dp/");
 * }</pre>
 */
@FunctionalInterface
public interface SchemaFetchPolicy {

  /** Hosts trusted by {@link #defaults()}: the TDWG DwC-DP hosts and the Frictionless specs. */
  Set<String> DEFAULT_HOSTS = Set.of(
    "rs.tdwg.org",
    "dwc-prerelease.rs.tdwg.org",
    "specs.frictionlessdata.io");

  /**
   * @param uri a normalized, absolute {@code http}/{@code https} URI
   * @return {@code true} if the validator may fetch it
   */
  boolean allows(URI uri);

  default SchemaFetchPolicy or(SchemaFetchPolicy other) {
    Objects.requireNonNull(other, "other");
    return uri -> allows(uri) || other.allows(uri);
  }

  default SchemaFetchPolicy and(SchemaFetchPolicy other) {
    Objects.requireNonNull(other, "other");
    return uri -> allows(uri) && other.allows(uri);
  }

  /** Adapts an arbitrary predicate. */
  static SchemaFetchPolicy of(Predicate<URI> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    return predicate::test;
  }

  /**
   * Denies everything — validation uses only the bundled schemas. Always returns the same
   * instance, so the validator can tell "fetching is switched off" apart from "this URL is not
   * allowed" and word its messages accordingly.
   */
  static SchemaFetchPolicy none() {
    return NonePolicy.INSTANCE;
  }

  /** Holder for the shared {@link #none()} instance (interface fields can't be private). */
  final class NonePolicy {
    private static final SchemaFetchPolicy INSTANCE = uri -> false;

    private NonePolicy() {}
  }

  /** HTTPS on the default port to one of {@link #DEFAULT_HOSTS}. */
  static SchemaFetchPolicy defaults() {
    return allowHosts(DEFAULT_HOSTS);
  }

  /** HTTPS on the default port to one of the given hosts (exact, case-insensitive match). */
  static SchemaFetchPolicy allowHosts(String... hosts) {
    return allowHosts(Set.of(hosts));
  }

  /** HTTPS on the default port to one of the given hosts (exact, case-insensitive match). */
  static SchemaFetchPolicy allowHosts(Collection<String> hosts) {
    Set<String> allowed = hosts.stream()
      .map(h -> h.trim().toLowerCase(Locale.ROOT))
      .filter(h -> !h.isEmpty())
      .collect(Collectors.toUnmodifiableSet());
    return uri -> "https".equalsIgnoreCase(uri.getScheme())
                  && uri.getRawUserInfo() == null
                  && (uri.getPort() == -1 || uri.getPort() == 443)
                  && uri.getHost() != null
                  && allowed.contains(uri.getHost().toLowerCase(Locale.ROOT));
  }
}
