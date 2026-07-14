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
package org.gbif.dp.common.descriptor;

import org.gbif.dp.descriptor.Contributor;
import org.gbif.dp.descriptor.DataPackageDescriptor;
import org.gbif.dp.descriptor.DataPackageParser;
import org.gbif.dp.descriptor.DialectDescriptor;
import org.gbif.dp.descriptor.FieldConstraints;
import org.gbif.dp.descriptor.FieldDescriptor;
import org.gbif.dp.descriptor.ForeignKeyDescriptor;
import org.gbif.dp.descriptor.License;
import org.gbif.dp.descriptor.MissingValueDescriptor;
import org.gbif.dp.descriptor.PackageSource;
import org.gbif.dp.descriptor.PrimaryKeyDescriptor;
import org.gbif.dp.descriptor.ReferenceDescriptor;
import org.gbif.dp.descriptor.ResourceDescriptor;
import org.gbif.dp.descriptor.SchemaDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-based {@link DataPackageParser}, using the JSON tree ({@link JsonNode}) as an
 * intermediate representation rather than direct databind onto the model. Direct databind
 * was considered and rejected: several fields are polymorphic (string-or-array — {@code path},
 * {@code primaryKey}, {@code enum}), dialect resolution falls back across sibling nodes
 * (resource dialect → package dialect → file-extension guess), and malformed entries are
 * skipped rather than surfaced — none of which reduces cleanly to annotations, and all of
 * which stays visible and centralized here instead.
 *
 * <p>Lives in {@code dwc-dp-common}, not alongside the {@code org.gbif.dp.descriptor} model —
 * the model and {@link DataPackageParser} are deliberately framework-agnostic.
 */
public class JacksonDataPackageParser implements DataPackageParser {

  private final ObjectMapper mapper;

  public JacksonDataPackageParser() {
    this(new ObjectMapper());
  }

  public JacksonDataPackageParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public DataPackageDescriptor parse(String descriptorContent) throws IOException {
    JsonNode root = mapper.readTree(descriptorContent);

    DataPackageDescriptor.Builder builder = DataPackageDescriptor.builder()
      .name(textOrNull(root, "name"))
      .profile(textOrNull(root, "profile"))
      .id(textOrNull(root, "id"))
      .title(textOrNull(root, "title"))
      .description(textOrNull(root, "description"))
      .homepage(textOrNull(root, "homepage"))
      .created(textOrNull(root, "created"))
      .contributors(parseContributors(root.path("contributors")))
      .keywords(normalizeToList(root.path("keywords")))
      .image(textOrNull(root, "image"))
      .licenses(parseLicenses(root.path("licenses")))
      .sources(parsePackageSources(root.path("sources")));

    JsonNode resourceNodes = root.path("resources");
    if (!resourceNodes.isArray()) {
      return builder.resources(List.of()).build();
    }

    DialectDescriptor defaultDialect = root.has("dialect") ? parseDialect(root.path("dialect"), null, null) : null;

    List<ResourceDescriptor> resources = new ArrayList<>();
    for (JsonNode resourceNode : resourceNodes) {
      String name = resourceNode.path("name").asText("");
      if (name.isBlank()) {
        continue;
      }

      List<String> paths = parsePaths(resourceNode.path("path"));
      if (paths.isEmpty() && !resourceNode.has("data")) {
        continue; // neither a path nor inline data — nothing to describe
      }

      resources.add(parseResourceDescriptor(resourceNode, name, paths, defaultDialect));
    }

    return builder.resources(List.copyOf(resources)).build();
  }

  private ResourceDescriptor parseResourceDescriptor(JsonNode resourceNode, String name, List<String> paths,
                                                     DialectDescriptor defaultDialect) {
    JsonNode schemaNode = resourceNode.path("schema");
    List<MissingValueDescriptor> schemaMissingValues = parseMissingValues(
      schemaNode.path("missingValues"), MissingValueDescriptor.Source.SCHEMA, List.of(MissingValueDescriptor.NULL));

    SchemaDescriptor schema = SchemaDescriptor.builder()
      .fields(parseFields(schemaNode.path("fields"), schemaMissingValues))
      .primaryKey(parsePrimaryKey(schemaNode.path("primaryKey")))
      .weakPrimaryKey(parsePrimaryKey(schemaNode.path("weakPrimaryKey")))
      .foreignKeys(parseForeignKeys(schemaNode.path("foreignKeys")))
      .weakForeignKeys(parseForeignKeys(schemaNode.path("weakForeignKeys")))
      .missingValues(schemaMissingValues)
      .build();

    DialectDescriptor dialect = parseDialect(resourceNode.path("dialect"), paths.stream().findFirst().orElse(null), defaultDialect);

    return ResourceDescriptor.builder()
      .name(name)
      .profile(textOrNull(resourceNode, "profile"))
      .paths(paths)
      .data(parseInlineData(resourceNode.path("data")))
      .title(textOrNull(resourceNode, "title"))
      .description(textOrNull(resourceNode, "description"))
      .homepage(textOrNull(resourceNode, "homepage"))
      .format(textOrNull(resourceNode, "format"))
      .mediatype(textOrNull(resourceNode, "mediatype"))
      .encoding(textOrNull(resourceNode, "encoding"))
      .bytes(resourceNode.hasNonNull("bytes") ? resourceNode.path("bytes").asLong() : null)
      .hash(textOrNull(resourceNode, "hash"))
      .schema(schema)
      .dialect(dialect)
      .sources(parsePackageSources(resourceNode.path("sources")))
      .licenses(parseLicenses(resourceNode.path("licenses")))
      .build();
  }

  private List<String> parsePaths(JsonNode pathNode) {
    List<String> paths = new ArrayList<>();
    if (pathNode.isArray()) {
      StreamSupport.stream(pathNode.spliterator(), false)
        .map(node -> node.asText(""))
        .filter(Predicate.not(String::isBlank))
        .forEach(paths::add);
    } else {
      String rawPath = pathNode.asText("");
      if (!rawPath.isBlank()) {
        paths.add(rawPath);
      }
    }
    return List.copyOf(paths);
  }

  private List<Map<String, Object>> parseInlineData(JsonNode dataNode) {
    if (!dataNode.isArray()) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode rowNode : dataNode) {
      if (rowNode.isObject()) {
        rows.add(mapper.convertValue(rowNode, new TypeReference<LinkedHashMap<String, Object>>() {}));
      }
    }
    return List.copyOf(rows);
  }

  private String getExtension(String rawPath) {
    String fileName = rawPath;
    int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    if (lastSlash >= 0) {
      fileName = fileName.substring(lastSlash + 1);
    }
    int lastDot = fileName.lastIndexOf('.');
    return lastDot < 0 ? "" : fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
  }

  private DialectDescriptor parseDialect(JsonNode node, String rawPath, DialectDescriptor defaultDialect) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      if (defaultDialect != null) {
        return defaultDialect;
      }
      if (rawPath == null) {
        return null;
      }
      return switch (getExtension(rawPath)) {
        case "tsv" -> DialectDescriptor.builder().delimiter("\t").build();
        case "csv" -> DialectDescriptor.builder().delimiter(",").build();
        case "parquet", "pq" -> null;
        default -> DialectDescriptor.defaults();
      };
    }

    return DialectDescriptor.builder()
      .delimiter(node.path("delimiter").asText(","))
      .quoteChar(node.path("quoteChar").asText("\""))
      .escapeChar(node.has("escapeChar") ? node.get("escapeChar").asText() : null)
      .doubleQuote(node.path("doubleQuote").asBoolean(true))
      .lineTerminator(node.path("lineTerminator").asText("\r\n"))
      .skipInitialSpace(node.path("skipInitialSpace").asBoolean(false))
      .nullSequence(node.has("nullSequence") ? node.get("nullSequence").asText() : null)
      .build();
  }

  private static List<MissingValueDescriptor> parseMissingValues(JsonNode node, MissingValueDescriptor.Source source,
                                                                 List<MissingValueDescriptor> defaultValues) {
    List<String> missingValues = normalizeToList(node);
    if (missingValues.isEmpty()) {
      return List.copyOf(defaultValues);
    }
    return missingValues.stream().map(str -> new MissingValueDescriptor(str, source)).toList();
  }

  private static PrimaryKeyDescriptor parsePrimaryKey(JsonNode node) {
    List<String> keys = normalizeToList(node);
    return keys.isEmpty() ? null : new PrimaryKeyDescriptor(keys);
  }

  private static List<FieldDescriptor> parseFields(JsonNode fieldsNode, List<MissingValueDescriptor> defaultMissingValues) {
    if (!fieldsNode.isArray()) {
      return List.of();
    }
    List<FieldDescriptor> fields = new ArrayList<>();
    for (JsonNode fieldNode : fieldsNode) {
      String fieldName = fieldNode.path("name").asText("").trim();
      if (fieldName.isBlank()) {
        continue;
      }
      List<MissingValueDescriptor> missingValues = parseMissingValues(
        fieldNode.path("missingValues"), MissingValueDescriptor.Source.FIELD, defaultMissingValues);

      fields.add(FieldDescriptor.builder()
                   .name(fieldName)
                   .type(fieldNode.path("type").asText("string").trim())
                   .format(fieldNode.path("format").asText("default").trim())
                   .missingValues(missingValues)
                   .constraints(parseConstraints(fieldNode.path("constraints")))
                   .title(textOrNull(fieldNode, "title"))
                   .description(textOrNull(fieldNode, "description"))
                   .dctermsIsVersionOf(textOrNull(fieldNode, "dcterms:isVersionOf"))
                   .dctermsReferences(textOrNull(fieldNode, "dcterms:references"))
                   .build());
    }
    return List.copyOf(fields);
  }

  private static FieldConstraints parseConstraints(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return FieldConstraints.none();
    }
    FieldConstraints.Builder builder = FieldConstraints.builder()
      .required(node.path("required").asBoolean(false))
      .unique(node.path("unique").asBoolean(false))
      .pattern(textOrNull(node, "pattern"))
      .enumValues(normalizeToList(node.path("enum")));
    if (node.hasNonNull("minLength")) builder.minLength(node.path("minLength").asInt());
    if (node.hasNonNull("maxLength")) builder.maxLength(node.path("maxLength").asInt());
    if (node.hasNonNull("minimum"))   builder.minimum(node.path("minimum").asDouble());
    if (node.hasNonNull("maximum"))   builder.maximum(node.path("maximum").asDouble());
    return builder.build();
  }

  private static List<ForeignKeyDescriptor> parseForeignKeys(JsonNode foreignKeyNodes) {
    if (!foreignKeyNodes.isArray()) {
      return List.of();
    }
    List<ForeignKeyDescriptor> keys = new ArrayList<>();
    for (JsonNode fkNode : foreignKeyNodes) {
      List<String> fields = normalizeToList(fkNode.path("fields"));
      JsonNode referenceNode = fkNode.path("reference");
      String referenceResource = referenceNode.path("resource").asText("");
      List<String> referenceFields = normalizeToList(referenceNode.path("fields"));

      if (!fields.isEmpty() && fields.size() == referenceFields.size()) {
        keys.add(ForeignKeyDescriptor.builder()
                   .fields(fields)
                   .reference(new ReferenceDescriptor(referenceResource, referenceFields))
                   .predicate(textOrNull(fkNode, "predicate"))
                   .build());
      }
    }
    return List.copyOf(keys);
  }

  private List<Contributor> parseContributors(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<Contributor> contributors = new ArrayList<>();
    for (JsonNode c : node) {
      String title = c.path("title").asText("");
      if (title.isBlank()) {
        continue;
      }
      contributors.add(Contributor.builder()
                         .title(title)
                         .path(textOrNull(c, "path"))
                         .email(textOrNull(c, "email"))
                         .organization(textOrNull(c, "organization"))
                         .role(c.path("role").asText("contributor"))
                         .build());
    }
    return List.copyOf(contributors);
  }

  private List<License> parseLicenses(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<License> licenses = new ArrayList<>();
    for (JsonNode l : node) {
      String name = textOrNull(l, "name");
      String path = textOrNull(l, "path");
      if (name == null && path == null) {
        continue;
      }
      licenses.add(License.builder().name(name).path(path).title(textOrNull(l, "title")).build());
    }
    return List.copyOf(licenses);
  }

  private List<PackageSource> parsePackageSources(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<PackageSource> sources = new ArrayList<>();
    for (JsonNode s : node) {
      String title = s.path("title").asText("");
      if (title.isBlank()) {
        continue;
      }
      sources.add(PackageSource.builder().title(title).path(textOrNull(s, "path")).email(textOrNull(s, "email")).build());
    }
    return List.copyOf(sources);
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText(null);
  }

  private static List<String> normalizeToList(JsonNode valueNode) {
    if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
      return List.of();
    }
    if (valueNode.isArray()) {
      List<String> values = new ArrayList<>();
      for (JsonNode item : valueNode) {
        String value = item.asText("").trim();
        if (!value.isBlank()) {
          values.add(value);
        }
      }
      return List.copyOf(values);
    }
    String single = valueNode.asText("").trim();
    return single.isBlank() ? List.of() : List.of(single);
  }
}
