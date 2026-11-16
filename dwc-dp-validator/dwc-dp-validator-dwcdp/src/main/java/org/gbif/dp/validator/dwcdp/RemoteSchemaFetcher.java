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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches schema documents over HTTP(S), gated by a {@link SchemaFetchPolicy}.
 *
 * <p>Redirects are followed manually (at most {@value #MAX_REDIRECTS} hops) so the policy is
 * re-checked on every hop. Responses are bounded by a timeout and a maximum body size.
 *
 * <p>Successful responses are cached for {@link #DEFAULT_OK_TTL}, so a long-running validator
 * eventually picks up a republished schema. Failures (network errors, non-200, too large) are
 * cached only briefly ({@link #DEFAULT_FAILED_TTL}): long enough that an unreachable host
 * doesn't cost a full timeout on every validation, short enough that a transient outage
 * doesn't stick. Policy denials are never cached — they are cheap to recompute.
 */
final class RemoteSchemaFetcher {

  private static final Logger log = LoggerFactory.getLogger(RemoteSchemaFetcher.class);

  static final int MAX_REDIRECTS = 3;
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
  static final Duration DEFAULT_OK_TTL = Duration.ofHours(24);
  static final Duration DEFAULT_FAILED_TTL = Duration.ofSeconds(60);

  /** {@link Denied#reason()} when the policy (rather than a hard rule) rejected the URL. */
  static final String POLICY_DENIED = "not allowed by the schema fetch policy";

  private static final String INTERRUPTED = "interrupted";

  sealed interface Result permits Ok, Denied, Failed {
    URI uri();
  }

  record Ok(URI uri, String content) implements Result {}

  record Denied(URI uri, String reason) implements Result {}

  record Failed(URI uri, String reason) implements Result {}

  private final SchemaFetchPolicy policy;
  private final HttpClient client;
  private final Duration timeout;
  private final long maxBytes;
  private final Clock clock;
  private final Duration okTtl;
  private final Duration failedTtl;
  private final Map<URI, CacheEntry> cache = new ConcurrentHashMap<>();

  /** A cached {@link Ok} or {@link Failed}, valid until {@code expiresAt}. */
  private record CacheEntry(Result result, Instant expiresAt) {}

  RemoteSchemaFetcher(SchemaFetchPolicy policy) {
    this(policy, DEFAULT_TIMEOUT, DEFAULT_MAX_BYTES);
  }

  RemoteSchemaFetcher(SchemaFetchPolicy policy, Duration timeout, long maxBytes) {
    this(policy, timeout, maxBytes, Clock.systemUTC(), DEFAULT_OK_TTL, DEFAULT_FAILED_TTL);
  }

  RemoteSchemaFetcher(SchemaFetchPolicy policy, Duration timeout, long maxBytes,
                      Clock clock, Duration okTtl, Duration failedTtl) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.timeout = timeout;
    this.maxBytes = maxBytes;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.okTtl = Objects.requireNonNull(okTtl, "okTtl");
    this.failedTtl = Objects.requireNonNull(failedTtl, "failedTtl");
    this.client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(timeout)
      .build();
  }

  /** Policy check only (after the same normalization {@link #fetch} applies) — no I/O. */
  Optional<Denied> checkAllowed(URI uri) {
    Result checked = normalizeAndCheck(uri);
    return checked instanceof Denied d ? Optional.of(d) : Optional.empty();
  }

  /** {@code true} if the policy is {@link SchemaFetchPolicy#none()}, i.e. fetching is switched off. */
  boolean isDisabled() {
    return policy == SchemaFetchPolicy.none();
  }

  Clock clock() {
    return clock;
  }

  /** How long a successfully fetched document (and anything built from it) stays valid. */
  Duration okTtl() {
    return okTtl;
  }

  Result fetch(URI requested) {
    Result checked = normalizeAndCheck(requested);
    if (!(checked instanceof Ok)) {
      return checked;
    }
    URI key = checked.uri();
    Instant now = clock.instant();
    CacheEntry cached = cache.get(key);
    if (cached != null) {
      if (now.isBefore(cached.expiresAt())) {
        return cached.result();
      }
      cache.remove(key, cached);
    }

    Result result = doFetch(requested, key);
    if (result instanceof Ok ok) {
      CacheEntry entry = new CacheEntry(ok, now.plus(okTtl));
      cache.put(key, entry);
      cache.putIfAbsent(ok.uri(), entry);
    } else if (result instanceof Failed f && !INTERRUPTED.equals(f.reason())) {
      cache.put(key, new CacheEntry(f, now.plus(failedTtl)));
    }
    return result;
  }

  /** Performs the request from {@code start}, following redirects. Does not touch the cache. */
  private Result doFetch(URI requested, URI start) {
    URI current = start;

    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      HttpResponse<InputStream> response;
      try {
        HttpRequest request = HttpRequest.newBuilder(current)
          .timeout(timeout)
          .header("Accept", "application/json, application/schema+json;q=0.9, */*;q=0.1")
          .GET()
          .build();
        log.debug("Fetching remote schema {}", current);
        response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Failed(current, INTERRUPTED);
      } catch (IOException | IllegalArgumentException e) {
        return new Failed(current, describe(e));
      }

      int status = response.statusCode();
      if (isRedirect(status)) {
        closeQuietly(response.body());
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty()) {
          return new Failed(current, "HTTP " + status + " without Location header");
        }
        URI next;
        try {
          next = current.resolve(new URI(location.get().trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
          return new Failed(current, "invalid redirect Location '" + location.get() + "'");
        }
        Result nextChecked = normalizeAndCheck(next);
        if (!(nextChecked instanceof Ok)) {
          return nextChecked;
        }
        log.debug("Remote schema {} redirected to {}", current, nextChecked.uri());
        current = nextChecked.uri();
        continue;
      }

      try (InputStream body = response.body()) {
        if (status != 200) {
          return new Failed(current, "HTTP " + status);
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
          return new Failed(current, "response too large (" + declared + " bytes, limit " + maxBytes + ")");
        }
        byte[] bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        if (bytes.length > maxBytes) {
          return new Failed(current, "response too large (limit " + maxBytes + " bytes)");
        }
        return new Ok(current, new String(bytes, StandardCharsets.UTF_8));
      } catch (IOException e) {
        return new Failed(current, describe(e));
      }
    }
    return new Failed(requested, "too many redirects (max " + MAX_REDIRECTS + ")");
  }

  /**
   * Normalizes {@code uri} and applies the hard rules plus the policy.
   *
   * @return {@link Ok} with an empty body and the normalized URI if allowed, otherwise
   *         {@link Denied}
   */
  private Result normalizeAndCheck(URI uri) {
    if (uri == null || !uri.isAbsolute() || uri.isOpaque()) {
      return new Denied(uri, "not an absolute hierarchical URL");
    }
    String scheme = uri.getScheme().toLowerCase();
    if (!scheme.equals("https") && !scheme.equals("http")) {
      return new Denied(uri, "scheme '" + scheme + "' is never fetched");
    }
    if (uri.getRawUserInfo() != null) {
      return new Denied(uri, "URLs with user-info are never fetched");
    }
    if (uri.getHost() == null) {
      return new Denied(uri, "URL has no host");
    }
    URI normalized;
    try {
      // Lower-case scheme, collapse dot-segments, drop the fragment. Built from raw parts so
      // existing percent-escapes are preserved as-is.
      URI n = uri.normalize();
      normalized = new URI(scheme + "://" + n.getRawAuthority()
                           + (n.getRawPath() == null ? "" : n.getRawPath())
                           + (n.getRawQuery() == null ? "" : "?" + n.getRawQuery()));
    } catch (URISyntaxException e) {
      return new Denied(uri, "malformed URL");
    }
    String path = normalized.getRawPath();
    if (path != null && (path.startsWith("/..") || path.contains("/../"))) {
      return new Denied(normalized, "path escapes its root");
    }
    if (!policy.allows(normalized)) {
      return new Denied(normalized, POLICY_DENIED);
    }
    return new Ok(normalized, "");
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  private static String describe(Exception e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
  }

  private static void closeQuietly(InputStream in) {
    try {
      if (in != null) in.close();
    } catch (IOException ignored) {
      // nothing useful to do
    }
  }
}
