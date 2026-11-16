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

import org.gbif.dp.common.io.FileSystemDataPackageSource;
import org.gbif.dp.validator.api.DescriptorValidationResult;
import org.gbif.dp.validator.api.DescriptorViolationType;
import org.gbif.dp.validator.api.ValidationIssue;
import org.gbif.dp.validator.dwcdp.ProfileResolution.Reason;
import org.gbif.dp.validator.dwcdp.ProfileResolution.Resolved;
import org.gbif.dp.validator.dwcdp.ProfileResolution.Unresolved;
import org.gbif.dp.validator.frictionless.FrictionlessDescriptorValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the policy-gated remote fallback against a local HTTP server that serves a copy of
 * the bundled 0.1 schemas under a fake, non-bundled version path ({@code /dwc-dp/9.9/}).
 */
class RemoteProfileFallbackTest {

  private static final String BUNDLED_0_1 = "https://rs.tdwg.org/dwc-dp/0.1/dwc-dp-profile.json";

  private static HttpServer server;
  private static String base;
  private static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

  /** Allows plain-http loopback only — what a test needs, never a sensible production policy. */
  private static final SchemaFetchPolicy LOOPBACK = uri -> "127.0.0.1".equals(uri.getHost());

  @TempDir Path tempDir;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", RemoteProfileFallbackTest::handle);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  private static void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
    try (ex) {
      if (path.startsWith("/dwc-dp/9.9/")) {
        byte[] body = classpath("/schemas/0.1/" + path.substring("/dwc-dp/9.9/".length()));
        if (body == null) {
          send(ex, 404, "");
        } else {
          send(ex, 200, body);
        }
      } else if (path.startsWith("/no-index/")) {
        // Published-version layout (1.0+): profile + table-schemas/, but no index.json.
        String rel = path.substring("/no-index/".length());
        byte[] body = rel.equals("index.json") ? null : classpath("/schemas/0.1/" + rel);
        if (body == null) {
          send(ex, 404, "");
        } else {
          send(ex, 200, body);
        }
      } else if (path.equals("/wrong-id/dwc-dp-profile.json")) {
        // 1.0_DEV declares $id = https://dwc-prerelease.rs.tdwg.org/... which doesn't match.
        send(ex, 200, classpath("/schemas/1.0_DEV/dwc-dp-profile.json"));
      } else if (path.equals("/redirect-in/dwc-dp-profile.json")) {
        ex.getResponseHeaders().add("Location", "/dwc-dp/9.9/dwc-dp-profile.json");
        send(ex, 302, "");
      } else if (path.equals("/redirect-out/dwc-dp-profile.json")) {
        ex.getResponseHeaders().add("Location", "http://evil.example/dwc-dp-profile.json");
        send(ex, 302, "");
      } else if (path.equals("/evil-ref/dwc-dp-profile.json")) {
        send(ex, 200, """
            { "$schema": "https://json-schema.org/draft/2020-12/schema",
              "$ref": "http://evil.example/schema.json" }
            """);
      } else if (path.equals("/not-json/dwc-dp-profile.json")) {
        send(ex, 200, "<html>hello</html>");
      } else if (path.equals("/big/dwc-dp-profile.json")) {
        send(ex, 200, "{\"$schema\":\"x\",\"pad\":\"" + "a".repeat(4096) + "\"}");
      } else {
        send(ex, 404, "");
      }
    }
  }

  private static byte[] classpath(String path) throws IOException {
    try (InputStream is = RemoteProfileFallbackTest.class.getResourceAsStream(path)) {
      return is == null ? null : is.readAllBytes();
    }
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    send(ex, status, body.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    }
  }

  private static int hitCount(String path) {
    AtomicInteger n = hits.get(path);
    return n == null ? 0 : n.get();
  }

  private static DwcDpProfileRegistry registry(SchemaFetchPolicy policy) {
    return new DwcDpProfileRegistry(policy);
  }

  private DwcDpDescriptorValidator validator(DwcDpProfileRegistry registry) {
    return new DwcDpDescriptorValidator(
      new FrictionlessDescriptorValidator(new ObjectMapper(), Map.of()), registry, new ObjectMapper(), Map.of());
  }

  private DescriptorValidationResult validate(DwcDpDescriptorValidator validator, String profile, String fieldType)
    throws IOException {
    Files.writeString(tempDir.resolve("event.csv"), "eventID\n1\n");
    Files.writeString(tempDir.resolve("datapackage.json"), """
        {
          "name": "remote-dwcdp",
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
                "type": "%s",
                "format": "default",
                "dcterms:isVersionOf": "http://rs.tdwg.org/dwc/terms/eventID"
              }]
            }
          }]
        }
        """.formatted(profile, fieldType));
    try (var src = new FileSystemDataPackageSource(tempDir.resolve("datapackage.json"))) {
      return validator.validate(src);
    }
  }

  // ── Resolution ────────────────────────────────────────────────────────────

  @Test
  void allowedUnknownProfileIsFetchedAndValidatesLikeBundled() throws Exception {
    String remote = base + "/dwc-dp/9.9/dwc-dp-profile.json";
    int indexHitsBefore = hitCount("/dwc-dp/9.9/index.json");
    DwcDpDescriptorValidator v = validator(registry(LOOPBACK));

    DescriptorValidationResult ok = validate(v, remote, "string");
    assertTrue(ok.isValid(), "Expected no errors, got: " + ok.issues());

    // A deliberate type mismatch must be caught by the remote table schema, exactly like bundled.
    List<DescriptorViolationType> remoteTypes = validate(v, remote, "integer").issues().stream()
      .map(ValidationIssue::violationType).sorted().toList();
    List<DescriptorViolationType> bundledTypes = validate(v, BUNDLED_0_1, "integer").issues().stream()
      .map(ValidationIssue::violationType).sorted().toList();
    assertTrue(remoteTypes.contains(DescriptorViolationType.FIELD_TYPE_MISMATCH), remoteTypes.toString());
    assertEquals(bundledTypes, remoteTypes);
    // The profile lists its tables, so index.json is never needed.
    assertEquals(indexHitsBefore, hitCount("/dwc-dp/9.9/index.json"));
    assertTrue(hitCount("/dwc-dp/9.9/table-schemas/event.json") > 0);
  }

  @Test
  void indexJsonIsFetchedWhenProfileDeclaresNoResourceNames() throws Exception {
    TableSchemaSource source = new TableSchemaSource.Remote(
      URI.create(base + "/index-only/"), new RemoteSchemaFetcher(LOOPBACK), List.of());
    // Served via the /dwc-dp/9.9/ route through a relative path the base resolves against.
    TableSchemaSource real = new TableSchemaSource.Remote(
      URI.create(base + "/dwc-dp/9.9/"), new RemoteSchemaFetcher(LOOPBACK), List.of());

    assertTrue(real.read("index.json").orElseThrow().contains("tableSchemas"));
    assertEquals(base + "/dwc-dp/9.9/index.json", real.describe("index.json"));
    assertTrue(source.read("index.json").isEmpty(), "404 should read as absent");
  }

  @Test
  void tablesComeFromProfileResourceNamesWhenThereIsNoIndex() throws Exception {
    String remote = base + "/no-index/dwc-dp-profile.json";
    DwcDpDescriptorValidator v = validator(registry(LOOPBACK));

    DescriptorValidationResult ok = validate(v, remote, "string");
    assertTrue(ok.isValid(), "Expected no errors, got: " + ok.issues());
    assertTrue(ok.issues().stream()
                 .noneMatch(i -> i.violationType() == DescriptorViolationType.TABLE_SCHEMA_UNAVAILABLE),
               ok.issues().toString());

    List<DescriptorViolationType> types = validate(v, remote, "integer").issues().stream()
      .map(ValidationIssue::violationType).toList();
    assertTrue(types.contains(DescriptorViolationType.FIELD_TYPE_MISMATCH), types.toString());
    assertEquals(0, hitCount("/no-index/index.json"), "index.json should not be requested");
    assertTrue(hitCount("/no-index/table-schemas/event.json") > 0);
  }

  @Test
  void remoteVersionIsCachedAndMarkedNotBundled() {
    DwcDpProfileRegistry registry = registry(LOOPBACK);
    String remote = base + "/dwc-dp/9.9/dwc-dp-profile.json";
    int before = hitCount("/dwc-dp/9.9/dwc-dp-profile.json");

    ProfileResolution first = registry.resolveDetailed(remote);
    ProfileResolution second = registry.resolveDetailed(remote);

    assertInstanceOf(Resolved.class, first);
    assertSame(((Resolved) first).version(), ((Resolved) second).version());
    assertFalse(((Resolved) first).version().bundled());
    assertEquals(1, hitCount("/dwc-dp/9.9/dwc-dp-profile.json") - before);
  }

  @Test
  void bundledProfilesNeverConsultThePolicy() {
    List<URI> consulted = new CopyOnWriteArrayList<>();
    DwcDpProfileRegistry registry = registry(uri -> consulted.add(uri) && false);

    ProfileResolution resolved = registry.resolveDetailed(BUNDLED_0_1);

    assertInstanceOf(Resolved.class, resolved);
    assertTrue(((Resolved) resolved).version().bundled());
    assertTrue(consulted.isEmpty(), "Policy consulted for a bundled profile: " + consulted);
  }

  @Test
  void defaultPolicyBlocksNonAllowlistedHostsWithoutNetworkAccess() {
    int before = hitCount("/dwc-dp/9.9/dwc-dp-profile.json");
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry();

    ProfileResolution local = registry.resolveDetailed(base + "/dwc-dp/9.9/dwc-dp-profile.json");
    ProfileResolution other = registry.resolveDetailed("https://example.org/dwc-dp/9.9/dwc-dp-profile.json");

    assertEquals(Reason.NOT_ALLOWED, ((Unresolved) local).reason());
    assertEquals(Reason.NOT_ALLOWED, ((Unresolved) other).reason());
    assertEquals(before, hitCount("/dwc-dp/9.9/dwc-dp-profile.json"));
  }

  @Test
  void nonePolicyBlocksEverything() {
    ProfileResolution r = registry(SchemaFetchPolicy.none())
      .resolveDetailed("https://rs.tdwg.org/dwc-dp/9.9/dwc-dp-profile.json");
    assertEquals(Reason.REMOTE_DISABLED, ((Unresolved) r).reason());
  }

  @Test
  void profileWithQueryStringIsRejected() {
    Unresolved r = (Unresolved) registry(LOOPBACK).resolveDetailed(base + "/dwc-dp/9.9/dwc-dp-profile.json?v=1");
    assertEquals(Reason.NOT_A_URL, r.reason());
    assertTrue(r.detail().contains("query string"), r.detail());
  }

  @Test
  void httpProfileIsFetchedOverHttpsWhenPolicyOnlyAllowsHttps() {
    SchemaFetchPolicy httpsLoopback =
      uri -> "https".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry(
      List.of(), new RemoteSchemaFetcher(httpsLoopback, Duration.ofSeconds(2), 1024 * 1024));

    // The test server speaks plain HTTP, so the HTTPS attempt fails at the TLS handshake. What
    // matters is that the URL passed the policy and was fetched, i.e. not NOT_ALLOWED.
    Unresolved r = (Unresolved) registry.resolveDetailed(base + "/dwc-dp/9.9/dwc-dp-profile.json");
    assertEquals(Reason.FETCH_FAILED, r.reason(), r.detail());
  }

  @Test
  void httpsUpgradeIsNotAttemptedWhenHttpsIsAlsoBlocked() {
    Unresolved r = (Unresolved) registry(SchemaFetchPolicy.defaults())
      .resolveDetailed("http://example.org/dwc-dp/9.9/dwc-dp-profile.json");
    assertEquals(Reason.NOT_ALLOWED, r.reason());
  }

  // ── Caching ───────────────────────────────────────────────────────────────

  /** A clock the test can move forward. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
  }

  private static RemoteSchemaFetcher fetcher(MutableClock clock) {
    return new RemoteSchemaFetcher(LOOPBACK, Duration.ofSeconds(5), 1024 * 1024, clock,
                                   RemoteSchemaFetcher.DEFAULT_OK_TTL, RemoteSchemaFetcher.DEFAULT_FAILED_TTL);
  }

  @Test
  void failedFetchesAreCachedBriefly() {
    MutableClock clock = new MutableClock();
    RemoteSchemaFetcher fetcher = fetcher(clock);
    URI missing = URI.create(base + "/negative-cache/dwc-dp-profile.json");

    assertInstanceOf(RemoteSchemaFetcher.Failed.class, fetcher.fetch(missing));
    assertInstanceOf(RemoteSchemaFetcher.Failed.class, fetcher.fetch(missing));
    assertEquals(1, hitCount("/negative-cache/dwc-dp-profile.json"));

    clock.advance(RemoteSchemaFetcher.DEFAULT_FAILED_TTL.plusSeconds(1));
    fetcher.fetch(missing);
    assertEquals(2, hitCount("/negative-cache/dwc-dp-profile.json"));
  }

  @Test
  void successfulFetchesAndRemoteVersionsExpireAfterTtl() {
    MutableClock clock = new MutableClock();
    DwcDpProfileRegistry registry = new DwcDpProfileRegistry(List.of(), fetcher(clock));
    String remote = base + "/dwc-dp/9.9/dwc-dp-profile.json";
    int before = hitCount("/dwc-dp/9.9/dwc-dp-profile.json");

    DwcDpSchemaVersion first = ((Resolved) registry.resolveDetailed(remote)).version();
    clock.advance(Duration.ofHours(23));
    assertSame(first, ((Resolved) registry.resolveDetailed(remote)).version());
    assertEquals(1, hitCount("/dwc-dp/9.9/dwc-dp-profile.json") - before);

    clock.advance(Duration.ofHours(2));
    DwcDpSchemaVersion refreshed = ((Resolved) registry.resolveDetailed(remote)).version();
    assertNotSame(first, refreshed);
    assertEquals(2, hitCount("/dwc-dp/9.9/dwc-dp-profile.json") - before);
  }

  @Test
  void nonUrlProfileIsReportedAsSuch() {
    assertEquals(Reason.NOT_A_URL, ((Unresolved) registry(LOOPBACK).resolveDetailed("not-a-uri")).reason());
    assertEquals(Reason.NOT_A_URL,
                 ((Unresolved) registry(LOOPBACK).resolveDetailed("file:///etc/passwd")).reason());
    assertEquals(Reason.MISSING, ((Unresolved) registry(LOOPBACK).resolveDetailed(null)).reason());
  }

  @Test
  void missingRemoteProfileIsAFetchFailure() {
    Unresolved r = (Unresolved) registry(LOOPBACK).resolveDetailed(base + "/nope/dwc-dp-profile.json");
    assertEquals(Reason.FETCH_FAILED, r.reason());
    assertTrue(r.detail().contains("404"), r.detail());
  }

  @Test
  void redirectWithinPolicyIsFollowedAndTableSchemasResolveFromFinalUrl() {
    ProfileResolution r = registry(LOOPBACK).resolveDetailed(base + "/redirect-in/dwc-dp-profile.json");
    assertInstanceOf(Resolved.class, r, r.toString());
    TableSchemaSource source = ((Resolved) r).version().tableSchemaSource();
    assertEquals(base + "/dwc-dp/9.9/table-schemas/event.json", source.describe("table-schemas/event.json"));
  }

  @Test
  void redirectOutsidePolicyIsBlocked() {
    Unresolved r = (Unresolved) registry(LOOPBACK).resolveDetailed(base + "/redirect-out/dwc-dp-profile.json");
    assertEquals(Reason.NOT_ALLOWED, r.reason());
    assertTrue(r.detail().contains("evil.example"), r.detail());
  }

  @Test
  void refToDisallowedHostFailsToLoad() {
    Unresolved r = (Unresolved) registry(LOOPBACK).resolveDetailed(base + "/evil-ref/dwc-dp-profile.json");
    assertEquals(Reason.LOAD_FAILED, r.reason());
    assertTrue(r.detail().contains("evil.example") && r.detail().contains("not allowed"), r.detail());
  }

  @Test
  void nonSchemaDocumentsAreRejected() {
    assertEquals(Reason.NOT_A_PROFILE,
                 ((Unresolved) registry(LOOPBACK).resolveDetailed(base + "/not-json/dwc-dp-profile.json")).reason());
    Unresolved wrongId =
      (Unresolved) registry(LOOPBACK).resolveDetailed(base + "/wrong-id/dwc-dp-profile.json");
    assertEquals(Reason.NOT_A_PROFILE, wrongId.reason());
    assertTrue(wrongId.detail().contains("$id"), wrongId.detail());
  }

  @Test
  void oversizedResponseIsAFetchFailure() {
    RemoteSchemaFetcher small = new RemoteSchemaFetcher(LOOPBACK, Duration.ofSeconds(5), 1024);
    RemoteSchemaFetcher.Result r = small.fetch(URI.create(base + "/big/dwc-dp-profile.json"));
    assertInstanceOf(RemoteSchemaFetcher.Failed.class, r);
    assertTrue(((RemoteSchemaFetcher.Failed) r).reason().contains("too large"), r.toString());
  }

  @Test
  void fetcherNormalizesBeforeCheckingPolicy() {
    RemoteSchemaFetcher fetcher = new RemoteSchemaFetcher(LOOPBACK);
    assertInstanceOf(RemoteSchemaFetcher.Ok.class,
                     fetcher.fetch(URI.create(base + "/other/../dwc-dp/9.9/dwc-dp-profile.json#frag")));
    assertInstanceOf(RemoteSchemaFetcher.Denied.class,
                     fetcher.fetch(URI.create(base + "/../dwc-dp/9.9/dwc-dp-profile.json")));
    assertInstanceOf(RemoteSchemaFetcher.Denied.class,
                     fetcher.fetch(URI.create("http://user:pw@127.0.0.1:1/x.json")));
  }

  // ── Issue messages ────────────────────────────────────────────────────────

  @Test
  void blockedProfileIssueExplainsHowToAllowIt() throws Exception {
    DescriptorValidationResult result =
      validate(validator(registry(LOOPBACK)), "https://example.org/dwc-dp/9.9/dwc-dp-profile.json", "string");

    ValidationIssue issue = result.issues().stream()
      .filter(i -> i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION)
      .findFirst().orElseThrow();
    assertTrue(issue.message().contains("not a bundled version"), issue.message());
    assertTrue(issue.message().contains("--schema-host"), issue.message());
  }

  @Test
  void offlineIssueSaysFetchingIsDisabledWithoutHostHint() throws Exception {
    DescriptorValidationResult result =
      validate(validator(registry(SchemaFetchPolicy.none())), "https://rs.tdwg.org/dwc-dp/9.9/dwc-dp-profile.json", "string");

    ValidationIssue issue = result.issues().stream()
      .filter(i -> i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION)
      .findFirst().orElseThrow();
    assertTrue(issue.message().contains("remote schema fetching is disabled"), issue.message());
    assertFalse(issue.message().contains("--schema-host"), issue.message());
  }

  @Test
  void hardRuleDenialHasNoHostHint() throws Exception {
    DescriptorValidationResult result =
      validate(validator(registry(LOOPBACK)), "https://user@example.org/dwc-dp/9.9/dwc-dp-profile.json", "string");

    ValidationIssue issue = result.issues().stream()
      .filter(i -> i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION)
      .findFirst().orElseThrow();
    assertTrue(issue.message().contains("user-info"), issue.message());
    assertFalse(issue.message().contains("--schema-host"), issue.message());
  }

  @Test
  void failedFetchIssueSaysFetchFailed() throws Exception {
    DescriptorValidationResult result =
      validate(validator(registry(LOOPBACK)), base + "/nope/dwc-dp-profile.json", "string");

    ValidationIssue issue = result.issues().stream()
      .filter(i -> i.violationType() == DescriptorViolationType.UNRECOGNIZED_PROFILE_VERSION)
      .findFirst().orElseThrow();
    assertTrue(issue.message().contains("fetching it failed"), issue.message());
  }

  // ── Policy factories ─────────────────────────────────────────────────────

  @Test
  void defaultPolicyAllowsOnlyHttpsToKnownHostsOnDefaultPort() {
    SchemaFetchPolicy p = SchemaFetchPolicy.defaults();
    assertTrue(p.allows(URI.create("https://rs.tdwg.org/dwc-dp/2.0/dwc-dp-profile.json")));
    assertTrue(p.allows(URI.create("https://RS.TDWG.ORG/dwc-dp/2.0/dwc-dp-profile.json")));
    assertTrue(p.allows(URI.create("https://rs.tdwg.org:443/x")));
    assertFalse(p.allows(URI.create("http://rs.tdwg.org/dwc-dp/2.0/dwc-dp-profile.json")));
    assertFalse(p.allows(URI.create("https://rs.tdwg.org:8443/x")));
    assertFalse(p.allows(URI.create("https://rs.tdwg.org.evil.example/x")));
    assertFalse(p.allows(URI.create("https://u@rs.tdwg.org/x")));
  }

  @Test
  void policiesCompose() {
    SchemaFetchPolicy p = SchemaFetchPolicy.defaults().or(SchemaFetchPolicy.allowHosts("mirror.example.org"));
    assertTrue(p.allows(URI.create("https://mirror.example.org/x")));
    assertTrue(p.allows(URI.create("https://rs.tdwg.org/x")));
    assertFalse(p.allows(URI.create("https://other.example.org/x")));
  }
}
