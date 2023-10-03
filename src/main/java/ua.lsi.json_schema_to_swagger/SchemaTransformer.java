package ua.lsi.json_schema_to_swagger;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.CaseFormat;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class SchemaTransformer {

    public ObjectMapper mapper;
    public static final String TEMPLATE_PATH = "swagger.json.template";
    private String sourcePath;
    private String destPath;


    public void setUp(String sourcePath, String destPath) {
        mapper = new ObjectMapper();
        DefaultPrettyPrinter pp = new MyPrettyPrinter();
        pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        mapper.setDefaultPrettyPrinter(pp);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.sourcePath = sourcePath;
        this.destPath = destPath;
    }

    public static class MyPrettyPrinter extends DefaultPrettyPrinter {

        public MyPrettyPrinter() {
            _arrayIndenter = DefaultIndenter.SYSTEM_LINEFEED_INSTANCE;
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new MyPrettyPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
            jg.writeRaw(": ");
        }

    }

    public void process() {
        try {
            String templateData = getResourceFile(TEMPLATE_PATH);
            JsonNode templateNode = mapper.readTree(templateData);

            Set<String> jsonSchemaFiles = Stream.of(new File(sourcePath).listFiles())
                    .filter(file -> !file.isDirectory())
                    .map(File::getName)
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.replace(".json", ""))
                    .collect(Collectors.toSet());
            for (String schemaFileName : jsonSchemaFiles) {
                processJsonSchema((ObjectNode) templateNode, schemaFileName + ".json", underscoresToCamelCase(schemaFileName) + "Payload");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getResourceFile(String fileName)
    {
        //Get file from resources folder
        ClassLoader classLoader = this.getClass().getClassLoader();

        InputStream stream = classLoader.getResourceAsStream(fileName);

        try
        {
            if (stream == null)
            {
                throw new Exception("Cannot find file " + fileName);
            }

            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            e.printStackTrace();

            System.exit(1);
        }

        return null;
    }

    public void processJsonSchema(ObjectNode templateNode, String schemaFileName, String name) throws IOException {
        File schemaFile = new File(sourcePath + schemaFileName);
        String schemaData = FileUtils.readFileToString(schemaFile, "UTF-8");
        ObjectNode definitions = mapper.createObjectNode();

        JsonNode schemaNode = mapper.readTree(schemaData);

        if (schemaNode.isMissingNode()) {
            throw new IllegalStateException("Supplied file is not json-schema file. File name: " + schemaFileName);
        }

        if (schemaNode.get("$schema").asText().equals("http://json-schema.org/draft-03/schema")) {
            //some pre processing needs to be done by npm json-schema-compatibility
            schemaNode = draft3toDraft4(schemaNode);
        }

        if (schemaNode.get("$schema").asText().equals("http://json-schema.org/draft-06/schema#")) {
            //some pre processing needs to be done by npm json-schema-compatibility
            schemaNode = draft6or7toDraft4(schemaNode);
        }

        if (schemaNode.get("$schema").asText().equals("http://json-schema.org/draft-07/schema#")) {
            //some pre processing needs to be done by npm json-schema-compatibility
            schemaNode = draft6or7toDraft4(schemaNode);
        }

        parseAndReference(name, schemaNode, definitions);

        templateNode.set("definitions", definitions);
        ObjectNode metadata = (ObjectNode) templateNode.get("paths").get("/api").get("get");
        ((ObjectNode) templateNode.get("tags").get(0)).put("name", name);
        ((ObjectNode) templateNode.get("tags").get(0)).put("description", name);
        ((ArrayNode) metadata.get("tags")).add(name).remove(0);
        metadata.put("summary", name);
        metadata.put("operationId", name + "UsingGET");
        ((ObjectNode) metadata.get("responses").get("200").get("schema")).put("$ref", "#/definitions/" + name);

        writeToSwaggerFile(mapper.writeValueAsString(templateNode), schemaFileName);
    }

    private void parseAndReference(String name, JsonNode schemaNode, ObjectNode definitions) {
        ObjectNode preparedNode = mapper.createObjectNode();

        if (schemaNode.has("additionalProperties")) {
            //maybe change to explicitly enabling?
            preparedNode.put("x-disableAdditionalProperties", !schemaNode.get("additionalProperties").asBoolean());
            preparedNode.put("x-additionalProperties", schemaNode.get("additionalProperties").asBoolean());
        } else {
            preparedNode.put("x-additionalProperties", true);
        }
        preparedNode.set("type", schemaNode.get("type"));
        if (schemaNode.has("required")) {
            preparedNode.set("required", schemaNode.get("required"));
        }
        if (schemaNode.has("title")) {
            preparedNode.set("title", schemaNode.get("title"));
        }
        if (schemaNode.has("description")) {
            preparedNode.set("description", schemaNode.get("description"));
        }
        preparedNode.set("properties", mapper.createObjectNode());
        preparedNode.put("title", name);
        if (schemaNode.has("definitions")) {
            schemaNode.get("definitions").fields().forEachRemaining(d -> definitions.put(d.getKey(), d.getValue()));
        }

        if (schemaNode.has("properties")) {
            schemaNode.get("properties").fields().forEachRemaining(nodeEntry -> {
                JsonNode value = nodeEntry.getValue();
                String key = nodeEntry.getKey();
                if (value.has("$ref")) {
                    ((ObjectNode) preparedNode.get("properties")).set(key, value);
                } else if (value.get("type").asText().equals("object") &&
                        value.has("properties")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    String preparedKey = key;
                    preparedKey = findKey(definitions, preparedKey);
                    nestedNode.put("$ref", "#/definitions/" + preparedKey);
                    parseAndReference(preparedKey, value, definitions);
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                    //put in definitions
                } else if (value.get("type").asText().equals("array") &&
                        value.has("items") &&
                        value.get("items").has("type") &&
                        value.get("items").get("type").asText().equals("object") &&
                        value.get("items").has("properties")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    ObjectNode itemNode = mapper.createObjectNode();
                    String preparedKey = toSingularForm(key);
                    preparedKey = findKey(definitions, preparedKey);
                    itemNode.put("$ref", "#/definitions/" + preparedKey);
                    nestedNode.put("type", "array");
                    nestedNode.set("items", itemNode);

                    parseAndReference(preparedKey, value.get("items"), definitions);
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (value.get("type").asText().equals("number")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> {
                        nestedNode.set(n.getKey(), n.getValue());
                    });
                    nestedNode.put("format", "double");
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (value.get("type").asText().equals("integer") && value.has("maximum") &&
                        value.get("maximum").asText().equals("9223372036854775807")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> {
                        nestedNode.set(n.getKey(), n.getValue());
                    });
                    nestedNode.put("format", "int64");
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (value.has("javaName")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> {
                        if (n.getKey().equals("javaName")) {
                            nestedNode.put("x-addCorresponds", true);
                            nestedNode.put("x-javaName", underscoresToSnakeCase(value.get("javaName").asText()));
                            nestedNode.put("x-javaCamelName", underscoresToCamelCase(value.get("javaName").asText()));
                        } else {
                            nestedNode.set(n.getKey(), n.getValue());
                        }
                    });
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (startsWithTwoUppercase(key)) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> nestedNode.set(n.getKey(), n.getValue()));
                    nestedNode.put("x-javaName", twoFirstUnderscoresToCase(key, true));
                    nestedNode.put("x-javaCamelName", twoFirstUnderscoresToCase(key, false));
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (value.has("examples")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> {
                        if (n.getKey().equals("examples")) {
                            nestedNode.set("example", n.getValue().get(0));
                        } else {
                            nestedNode.set(n.getKey(), n.getValue());
                        }
                    });
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else if (value.has("javaType") && value.get("javaType").asText().equals("java.lang.Long")) {
                    ObjectNode nestedNode = mapper.createObjectNode();
                    value.fields().forEachRemaining(n -> {
                        if (n.getKey().equals("type")) {
                            nestedNode.put("type", "integer");
                            nestedNode.put("format", "int64");
                        } else if (!n.getKey().equals("javaType")) {
                            nestedNode.set(n.getKey(), n.getValue());
                        }
                    });
                    ((ObjectNode) preparedNode.get("properties")).set(key, nestedNode);
                } else {
                    ((ObjectNode) preparedNode.get("properties")).set(key, value);
                }
            });
        }

        definitions.set(name, preparedNode);
    }

    private static String findKey(ObjectNode definitions, String preparedKey) {
        if (definitions.has(preparedKey)) {
            preparedKey = preparedKey + "_";
        }
        if (definitions.has(preparedKey)) {
            return findKey(definitions, preparedKey);
        }
        return preparedKey;
    }

    public String underscoresToCamelCase(String underscored) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, underscored);
    }

    public String underscoresToSnakeCase(String underscored) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, underscored);
    }

    public boolean startsWithTwoUppercase(String key) {
        return key.substring(0, 2).equals(key.substring(0, 2).toUpperCase());
    }

    public String twoFirstUnderscoresToCase(String text, boolean firstLower) {
        boolean shouldConvertNextCharToUpper = false;
        StringBuilder builder = new StringBuilder();
        char delimiter = '_';
        if (firstLower) {
            builder.append(Character.toLowerCase(text.charAt(0)));
        } else {
            builder.append(Character.toUpperCase(text.charAt(0)));
        }
        for (int i = 1; i < text.length(); i++) {
            char currentChar = text.charAt(i);
            if (currentChar == delimiter) {
                shouldConvertNextCharToUpper = true;
            } else if (shouldConvertNextCharToUpper) {
                builder.append(Character.toUpperCase(currentChar));
                shouldConvertNextCharToUpper = false;
            } else if (currentChar != ' ') {
                builder.append(currentChar);
            }
        }
        return builder.toString();
    }

    public JsonNode draft3toDraft4(JsonNode schema3) {
        ObjectNode preparedNode = mapper.createObjectNode();

        if (schema3.isTextual()) {
            return schema3;
        }

        schema3.fields().forEachRemaining(f -> {
            if (f.getValue().has("type") && f.getValue().get("type").asText().equals("object")) {
                preparedNode.set(f.getKey(), draft3toDraft4(f.getValue()));
            } else if (f.getValue().has("type") && f.getValue().get("type").asText().equals("array")) {
                JsonNode value = draft3toDraft4(f.getValue());
                if (!f.getValue().has("items")) {
                    ObjectNode objectTypeNode = mapper.createObjectNode();
                    objectTypeNode.put("type", "object");
                    ((ObjectNode) value).set("items", objectTypeNode);
                }
                preparedNode.set(f.getKey(), value);
            } else if (f.getKey().equals("properties")) {
                preparedNode.set(f.getKey(), draft3toDraft4(f.getValue()));
            } else if (!f.getKey().equals("id")) {
                preparedNode.set(f.getKey(), draft3toDraft4(f.getValue()));
            }
        });

        return preparedNode;
    }

    public JsonNode draft6or7toDraft4(JsonNode schema7) {
        ObjectNode preparedNode = mapper.createObjectNode();

        if (schema7.isTextual()) {
            return schema7;
        }

        schema7.fields().forEachRemaining(f -> {
            String key = f.getKey();
            if (f.getValue().has("$id")) {
                String id = f.getValue().get("$id").asText();
                key = id.substring(id.lastIndexOf('/') + 1);
            }
            if (f.getValue().has("type") && f.getValue().get("type").asText().equals("object")) {
                preparedNode.set(key, draft6or7toDraft4(f.getValue()));
            } else if (f.getValue().has("type") && f.getValue().get("type").asText().equals("array")) {
                JsonNode value = draft6or7toDraft4(f.getValue());
                if (!f.getValue().has("items")) {
                    ObjectNode objectTypeNode = mapper.createObjectNode();
                    objectTypeNode.put("type", "object");
                    ((ObjectNode) value).set("items", objectTypeNode);
                }
                preparedNode.set(key, value);
            } else if (key.equals("properties")) {
                preparedNode.set(key, draft6or7toDraft4(f.getValue()));
            } else if (key.equals("required") || key.equals("examples") || key.equals("minimum") ||
                    key.equals("maximum") || key.equals("enum") ||
                    key.equals("maxLength") || key.equals("default")) {
                preparedNode.set(key, f.getValue());
            } else if (!key.equals("$id")) {
                preparedNode.set(key, draft6or7toDraft4(f.getValue()));
            }
        });

        return preparedNode;
    }

    public String toSingularForm(String name) {
        if (name.endsWith("ies")) {
            return name.replaceFirst("ies$", "y");
        } else if (name.endsWith("sses")) {
            return name.replaceFirst("es$", "");
        } else if (name.endsWith("s")) {
            return name.replaceFirst("s$", "");
        }
        return name;
    }

    public void writeToSwaggerFile(String swaggerJson, String schemaName) {
        try {
            File destDir = new File(destPath);
            if (!destDir.exists()){
                Files.createDirectories(Paths.get(destPath));
            }
            File myObj = new File(destPath + schemaName);
            if (myObj.createNewFile()) {
                System.out.println("File created: " + myObj.getName());
            }
            FileWriter myWriter = new FileWriter(destPath + schemaName);
            myWriter.write(swaggerJson);
            myWriter.close();
            System.out.println("Successfully wrote to the file." + schemaName);
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    }
}
