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

import org.gbif.dp.validator.dwcdp.ProfileResolution.Reason;
import org.gbif.dp.validator.dwcdp.ProfileResolution.Resolved;
import org.gbif.dp.validator.dwcdp.ProfileResolution.Unresolved;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;

/**
 * Resolves a descriptor's {@code profile} URI to the {@link DwcDpSchemaVersion} that knows
 * how to validate it.
 *
 * <p><strong>Bundled versions</strong> are a hardcoded list rather than classpath-discovered —
 * with only two versions today, a manifest-driven registry would be extra machinery for no
 * current payoff. They never touch the network.
 *
 * <p><strong>Remote fallback.</strong> A profile URI that is not bundled may be fetched at
 * runtime, if the configured {@link SchemaFetchPolicy} allows it (default:
 * {@link SchemaFetchPolicy#defaults()}). The remote layout is assumed to mirror the bundled
 * jar: {@code index.json} and {@code table-schemas/*.json} next to the profile document.
 * {@code http://} profile URIs are fetched over HTTPS when the policy only allows that.
 * Successfully loaded remote versions are reused for 24 hours; failed fetches are retried
 * after a minute (see {@link RemoteSchemaFetcher}). Use {@link #resolveDetailed} to learn
 * <em>why</em> a profile could not be resolved.
 *
 * <p>A version can also declare {@code aliasProfileUris} — other profile URIs that should
 * resolve to the exact same {@link DwcDpSchemaVersion} (same loaded {@code Schema} instance,
 * same table-schema classpath base), e.g. the {@code http://} spelling of a bundled profile.
 *
 * <p>Only 0.1 and 1.0_DEV are bundled. From 1.0 on, versions are published at
 * {@code https://rs.tdwg.org/dwc-dp/<version>/dwc-dp-profile.json} and resolved remotely:
 * their tables are the profile's {@code $defs.dwc-dp-resource-names}, each at
 * {@code table-schemas/<name>.json} next to the profile (see {@link TableSchemaSource.Remote}).
 * Earlier builds aliased the 1.0 URIs to 1.0_DEV; the published 1.0 differs, so that alias
 * was removed.
 */
public class DwcDpProfileRegistry {

  private static final Logger log = LoggerFactory.getLogger(DwcDpProfileRegistry.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
      "/schemas/0.1",
      List.of("http://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json")),
    new VersionConfig(
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json",
      "https://dwc-prerelease.rs.tdwg.org/dwc-dp",
      "/schemas/1.0_DEV",
      // The published 1.0 (https://rs.tdwg.org/dwc-dp/1.0/...) differs from 1.0_DEV and is
      // deliberately NOT aliased here: it, and later versions, are fetched remotely.
      List.of("http://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_DEV/dwc-dp-profile.json")
    )
  );

  private final Map<String, DwcDpSchemaVersion> byProfileUri;
  private final RemoteSchemaFetcher fetcher;
  private final Map<String, RemoteVersion> remoteVersions = new ConcurrentHashMap<>();

  /** A remotely loaded version, reused until {@code expiresAt} (see {@link RemoteSchemaFetcher#okTtl()}). */
  private record RemoteVersion(DwcDpSchemaVersion version, Instant expiresAt) {}

  /** Bundled versions + remote fallback under {@link SchemaFetchPolicy#defaults()}. */
  public DwcDpProfileRegistry() {
    this(SchemaFetchPolicy.defaults());
  }

  /**
   * Bundled versions + remote fallback under {@code policy}. Pass
   * {@link SchemaFetchPolicy#none()} for bundled-only (no network access at all).
   */
  public DwcDpProfileRegistry(SchemaFetchPolicy policy) {
    this(KNOWN_VERSIONS, new RemoteSchemaFetcher(policy));
  }

  /** Test hook: custom bundled versions, no remote fallback. */
  DwcDpProfileRegistry(List<VersionConfig> versions) {
    this(versions, new RemoteSchemaFetcher(SchemaFetchPolicy.none()));
  }

  DwcDpProfileRegistry(List<VersionConfig> versions, RemoteSchemaFetcher fetcher) {
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
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

  /** @return the version for {@code profileUri}, bundled or (if allowed) fetched remotely */
  public Optional<DwcDpSchemaVersion> resolve(String profileUri) {
    return resolveDetailed(profileUri) instanceof Resolved r ? Optional.of(r.version()) : Optional.empty();
  }

  /** Like {@link #resolve}, but explains why a profile could not be resolved. */
  public ProfileResolution resolveDetailed(String profileUri) {
    if (profileUri == null || profileUri.isBlank()) {
      return new Unresolved(Reason.MISSING, "no 'profile' declared");
    }
    String key = profileUri.trim();

    DwcDpSchemaVersion bundled = byProfileUri.get(key);
    if (bundled != null) {
      return new Resolved(bundled);
    }
    Instant now = fetcher.clock().instant();
    RemoteVersion cached = remoteVersions.get(key);
    if (cached != null) {
      if (now.isBefore(cached.expiresAt())) {
        return new Resolved(cached.version());
      }
      remoteVersions.remove(key, cached);
    }

    URI uri;
    try {
      uri = new URI(key);
    } catch (URISyntaxException e) {
      return new Unresolved(Reason.NOT_A_URL, "it is not a valid URL");
    }
    if (!uri.isAbsolute() || uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
      return new Unresolved(Reason.NOT_A_URL, "it is not an absolute http(s) URL");
    }
    if (uri.getRawQuery() != null) {
      // Each distinct query would be a separate cache entry for the same document.
      return new Unresolved(Reason.NOT_A_URL, "profile URLs with a query string are not supported");
    }

    if (fetcher.isDisabled()) {
      return new Unresolved(Reason.REMOTE_DISABLED, "remote schema fetching is disabled");
    }

    Optional<RemoteSchemaFetcher.Denied> denied = fetcher.checkAllowed(uri);
    if (denied.isPresent()) {
      // Profile URIs are conventionally written as http://rs.tdwg.org/..., but policies
      // (including the default) only allow HTTPS. Fetch the HTTPS equivalent instead.
      Optional<URI> https = httpsEquivalent(uri).filter(u -> fetcher.checkAllowed(u).isEmpty());
      if (https.isEmpty()) {
        return new Unresolved(Reason.NOT_ALLOWED, "fetching it is blocked: " + denied.get().reason());
      }
      log.debug("Fetching DwC-DP profile {} over HTTPS as {}", key, https.get());
      uri = https.get();
    }

    RemoteSchemaFetcher.Result fetched = fetcher.fetch(uri);
    if (fetched instanceof RemoteSchemaFetcher.Denied d) {
      // e.g. a redirect to a host outside the policy
      return new Unresolved(Reason.NOT_ALLOWED,
                            "fetching it is blocked: it redirects to " + d.uri() + ", " + d.reason());
    }
    if (fetched instanceof RemoteSchemaFetcher.Failed f) {
      log.warn("Fetching DwC-DP profile {} failed: {}", key, f.reason());
      return new Unresolved(Reason.FETCH_FAILED, "fetching it failed (" + f.reason() + ")");
    }
    RemoteSchemaFetcher.Ok ok = (RemoteSchemaFetcher.Ok) fetched;

    Optional<String> notAProfile = checkLooksLikeProfile(uri, ok.content());
    if (notAProfile.isPresent()) {
      return new Unresolved(Reason.NOT_A_PROFILE, notAProfile.get());
    }

    Schema schema;
    try {
      schema = DwcDpProfileSchemaLoader.loadRemote(uri, fetcher);
    } catch (Exception e) {
      String msg = rootMessage(e);
      log.warn("Loading remote DwC-DP profile {} failed: {}", key, msg);
      return new Unresolved(Reason.LOAD_FAILED, "it could not be loaded as a JSON Schema (" + msg + ")");
    }

    URI versionRoot = ok.uri().resolve(".");
    DwcDpSchemaVersion version = new DwcDpSchemaVersion(
      key, schema, new TableSchemaSource.Remote(versionRoot, fetcher, declaredResourceNames(ok.content())), false);
    RemoteVersion previous =
      remoteVersions.putIfAbsent(key, new RemoteVersion(version, now.plus(fetcher.okTtl())));
    log.info("Using remotely fetched DwC-DP profile {} (table schemas from {})", key, versionRoot);
    return new Resolved(previous != null ? previous.version() : version);
  }

  /** {@code http://host[:80]/p} → {@code https://host/p}; other ports are kept as-is. */
  private static Optional<URI> httpsEquivalent(URI uri) {
    if (!"http".equalsIgnoreCase(uri.getScheme())) {
      return Optional.empty();
    }
    try {
      int port = uri.getPort() == 80 ? -1 : uri.getPort();
      return Optional.of(new URI("https", null, uri.getHost(), port,
                                 uri.getPath(), uri.getQuery(), null));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /**
   * Cheap sanity check before handing the document to networknt: it must be a JSON object
   * declaring {@code $schema}, and if it declares {@code $id} that must name the requested
   * profile (scheme-insensitive) — otherwise we'd validate against something the descriptor
   * did not ask for.
   */
  /**
   * The table names a profile declares in {@code $defs.dwc-dp-resource-names.enum} — from 1.0
   * on, this is how a published version lists its tables (there is no {@code index.json}).
   *
   * @return the names, or an empty list if the profile doesn't declare them
   */
  static List<String> declaredResourceNames(String profileContent) {
    try {
      JsonNode names = MAPPER.readTree(profileContent).path("$defs").path("dwc-dp-resource-names").path("enum");
      List<String> result = new ArrayList<>();
      for (JsonNode n : names) {
        // Only plain table names: they become a path segment under table-schemas/.
        if (n.isTextual() && n.asText().matches("[a-z0-9][a-z0-9-]*")) {
          result.add(n.asText());
        }
      }
      return result;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static Optional<String> checkLooksLikeProfile(URI requested, String content) {
    JsonNode root;
    try {
      root = MAPPER.readTree(content);
    } catch (Exception e) {
      return Optional.of("the document at that URL is not valid JSON");
    }
    if (root == null || !root.isObject()) {
      return Optional.of("the document at that URL is not a JSON object");
    }
    if (!root.path("$schema").isTextual()) {
      return Optional.of("the document at that URL is not a JSON Schema (no '$schema')");
    }
    JsonNode id = root.get("$id");
    if (id != null && id.isTextual() && !sameIgnoringScheme(requested.toString(), id.asText())) {
      return Optional.of("the document at that URL declares a different $id ('" + id.asText() + "')");
    }
    return Optional.empty();
  }

  private static boolean sameIgnoringScheme(String a, String b) {
    return stripScheme(a).equalsIgnoreCase(stripScheme(b));
  }

  private static String stripScheme(String s) {
    String t = s.trim().replaceFirst("(?i)^https?://", "");
    int hash = t.indexOf('#');
    return hash >= 0 ? t.substring(0, hash) : t;
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String msg = t.getMessage();
    if (msg == null || msg.isBlank()) {
      msg = e.getMessage();
    }
    return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
  }
}
