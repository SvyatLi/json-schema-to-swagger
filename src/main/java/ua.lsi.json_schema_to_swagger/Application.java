package ua.lsi.json_schema_to_swagger;

public class Application {

    public static final String SOURCE_PATH = "./";
    public static final String DEST_PATH = "./swagger/";

    public static void main(String[] args) {
        SchemaTransformer schemaTransformer = new SchemaTransformer();
        schemaTransformer.setUp(SOURCE_PATH, DEST_PATH);
        schemaTransformer.process();

    }
}
