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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Where one DwC-DP version's {@code index.json} and {@code table-schemas/*.json} are read
 * from. Paths are relative to the version root (the directory holding
 * {@code dwc-dp-profile.json}).
 */
public interface TableSchemaSource {

  /**
   * @param relativePath e.g. {@code index.json} or {@code table-schemas/event.json}
   * @return the document content, or empty if it does not exist
   * @throws IOException if it exists but could not be read (or, remotely, could not be fetched)
   */
  Optional<String> read(String relativePath) throws IOException;

  /** Human-readable location for {@code relativePath}, used in issue messages. */
  String describe(String relativePath);

  /** Bundled schemas under a classpath root such as {@code /schemas/0.1}. */
  static TableSchemaSource classpath(String classpathBase) {
    return new Classpath(classpathBase);
  }

  /** Classpath-backed source — the bundled {@code dwc-dp-schemas} jar. */
  record Classpath(String base) implements TableSchemaSource {

    public Classpath {
      Objects.requireNonNull(base, "base");
    }

    @Override
    public Optional<String> read(String relativePath) throws IOException {
      try (InputStream is = TableSchemaSource.class.getResourceAsStream(base + "/" + relativePath)) {
        if (is == null) {
          return Optional.empty();
        }
        return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
      }
    }

    @Override
    public String describe(String relativePath) {
      return "classpath:" + base + "/" + relativePath;
    }
  }

  /**
   * Remote source rooted at a profile's parent URL; every fetch goes through the policy.
   *
   * <p>Published DwC-DP versions (1.0 onwards) don't ship an {@code index.json}: the profile's
   * {@code $defs.dwc-dp-resource-names} enum lists the tables, and each lives at
   * {@code table-schemas/<name>.json}. When {@code resourceNames} is non-empty, reading
   * {@code index.json} returns an index built from those names, without a network request.
   * Otherwise {@code index.json} is fetched next to the profile.
   */
  final class Remote implements TableSchemaSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI base;
    private final RemoteSchemaFetcher fetcher;
    private final List<String> resourceNames;

    Remote(URI base, RemoteSchemaFetcher fetcher) {
      this(base, fetcher, List.of());
    }

    Remote(URI base, RemoteSchemaFetcher fetcher, List<String> resourceNames) {
      this.base = Objects.requireNonNull(base, "base");
      this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
      this.resourceNames = List.copyOf(resourceNames);
    }

    URI base() {
      return base;
    }

    /** Table names declared by the profile; empty if it declared none. */
    List<String> resourceNames() {
      return resourceNames;
    }

    @Override
    public Optional<String> read(String relativePath) throws IOException {
      if ("index.json".equals(relativePath) && !resourceNames.isEmpty()) {
        return Optional.of(indexFromResourceNames());
      }
      URI target;
      try {
        target = base.resolve(relativePath);
      } catch (IllegalArgumentException e) {
        throw new IOException("invalid schema path '" + relativePath + "'", e);
      }
      RemoteSchemaFetcher.Result result = fetcher.fetch(target);
      if (result instanceof RemoteSchemaFetcher.Ok ok) {
        return Optional.of(ok.content());
      }
      if (result instanceof RemoteSchemaFetcher.Failed f && f.reason().equals("HTTP 404")) {
        return Optional.empty();
      }
      String reason = result instanceof RemoteSchemaFetcher.Denied d ? d.reason()
                      : ((RemoteSchemaFetcher.Failed) result).reason();
      throw new IOException(result.uri() + ": " + reason);
    }

    /** Same shape as the bundled {@code index.json}: {@code {"tableSchemas":[{name,url}...]}}. */
    private String indexFromResourceNames() {
      ObjectNode index = MAPPER.createObjectNode();
      ArrayNode tables = index.putArray("tableSchemas");
      for (String name : resourceNames) {
        tables.addObject().put("name", name).put("url", "table-schemas/" + name + ".json");
      }
      return index.toString();
    }

    @Override
    public String describe(String relativePath) {
      if ("index.json".equals(relativePath) && !resourceNames.isEmpty()) {
        return "the profile's dwc-dp-resource-names (tables under " + base.resolve("table-schemas/") + ")";
      }
      try {
        return base.resolve(relativePath).toString();
      } catch (IllegalArgumentException e) {
        return base + relativePath;
      }
    }
  }
}
