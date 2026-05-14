package org.gbif.dp.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

public class JacksonDataPackageParser implements DataPackageParser {

  private final ObjectMapper mapper;

  public JacksonDataPackageParser() {
    this(new ObjectMapper());
  }

  public JacksonDataPackageParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public DataPackageDescriptor parse(Path descriptorPath) throws IOException {
    JsonNode root = mapper.readTree(descriptorPath.toFile());
    String packageName = root.path("name").asText("");

    List<ResourceDescriptor> resources = new ArrayList<>();

    JsonNode resourceNodes = root.path("resources");
    if (!resourceNodes.isArray()) {
      return new DataPackageDescriptor(packageName, List.of());
    }

    DialectDescriptor defaultDialect = null;
    if (root.has("dialect")) {
      defaultDialect = parseDialect(root.path("dialect"), null, null);
    }
    for (JsonNode resourceNode : resourceNodes) {
      String name = resourceNode.path("name").asText("");
      if (name.isBlank()) {
        continue;
      }
      List<Path> paths = new ArrayList<>();
      JsonNode pathNode = resourceNode.path("path");
      if (pathNode.isArray()) {
        StreamSupport.stream(pathNode.spliterator(), false)
                .map(node -> node.asText(""))
                .filter(Predicate.not(String::isBlank))
                .map(str -> descriptorPath.getParent().resolve(str).normalize())
                .forEach(paths::add);
      } else {
        String relativePath = pathNode.asText("");
        if (!relativePath.isBlank()) {
          Path resourcePath = descriptorPath.getParent().resolve(relativePath).normalize();
          paths.add(resourcePath);
        }
      }
      if (paths.isEmpty()) {
        continue;
      }

      ResourceDescriptor descriptor = parseResourceDescriptor(resourceNode, name, paths, defaultDialect);
      resources.add(descriptor);
    }

    return new DataPackageDescriptor(packageName, List.copyOf(resources));
  }

  private ResourceDescriptor parseResourceDescriptor(JsonNode resourceNode, String name, List<Path> paths, DialectDescriptor defaultDialect) {
    JsonNode schemaNode = resourceNode.path("schema");
    List<MissingValueDescriptor> defaultMissingValues = parseMissingValues(
            schemaNode.path("missingValues"),
            MissingValueDescriptor.Source.SCHEMA,
            List.of(MissingValueDescriptor.NULL));
    List<FieldDescriptor> fields = parseFields(schemaNode.path("fields"), defaultMissingValues);
    List<ForeignKeyDescriptor> foreignKeys = parseForeignKeys(schemaNode.path("foreignKeys"));
    PrimaryKeyDescriptor primaryKey = parsePrimaryKey(resourceNode.path("primaryKey"));
    DialectDescriptor dialect = parseDialect(resourceNode.path("dialect"), paths.stream().findFirst().orElse(null), defaultDialect);
    return new ResourceDescriptor(name, paths, fields, foreignKeys, primaryKey, dialect);
  }

  private String getExtension(Path path) {
    String fileName = path.getFileName().toString();
    return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }

  private DialectDescriptor parseDialect(JsonNode node, Path path, DialectDescriptor defaultDialect) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      if (defaultDialect != null) {
        return defaultDialect;
      }
      if (path == null) {
        return null;
      }
      return switch (getExtension(path)) {
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

  private static List<MissingValueDescriptor> parseMissingValues(
            JsonNode node,
            MissingValueDescriptor.Source source,
            List<MissingValueDescriptor> defaultValues) {
        List<String> missingValues = normalizeToList(node);
        if (missingValues.isEmpty()) {
          return List.copyOf(defaultValues);
        }
        return missingValues.stream()
                .map(str -> new MissingValueDescriptor(str, source))
                .toList();
    }

  private PrimaryKeyDescriptor parsePrimaryKey(JsonNode node) {
      List<String> keys = normalizeToList(node);
      if (keys.isEmpty()) {
        return null;
      }
      return new PrimaryKeyDescriptor(keys);
    }

  private static List<FieldDescriptor> parseFields(JsonNode fieldsNode, List<MissingValueDescriptor> defaultMissingValues) {
    if (!fieldsNode.isArray()) {
      return List.of();
    }

    List<FieldDescriptor> fields = new ArrayList<>();
    for (JsonNode fieldNode : fieldsNode) {
      String fieldName = fieldNode.path("name").asText("").trim();
      String fieldType = fieldNode.path("type").asText("string").trim();
      String fieldFormat = fieldNode.path("format").asText("default").trim();
      List<MissingValueDescriptor> missingValues = parseMissingValues(
                fieldNode.path("missingValues"), MissingValueDescriptor.Source.FIELD, defaultMissingValues);
      if (!fieldName.isBlank()) {
        fields.add(new FieldDescriptor(fieldName, fieldType, fieldFormat, missingValues));
      }
    }
    return List.copyOf(fields);
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
        keys.add(new ForeignKeyDescriptor(fields, new ReferenceDescriptor(referenceResource, referenceFields)));
      }
    }
    return List.copyOf(keys);
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

