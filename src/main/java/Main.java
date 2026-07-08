import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static final Map<String, String> JAVA_TYPES = Map.of(
            "UUID", "UUID",
            "String", "String",
            "Boolean", "Boolean",
            "Date", "LocalDate",
            "Instant", "Instant"
    );

    private static final Map<String, String> SQL_TYPES = Map.of(
            "UUID", "UUID",
            "String", "VARCHAR",
            "Boolean", "BOOLEAN",
            "Date", "DATE",
            "Instant", "TIMESTAMP WITH TIME ZONE"
    );

    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();

    public static void main(String[] args) throws Exception {
        Main generator = new Main();
        generator.generateAll("generator/definitions", "generator/templates", "output/");
    }

    public void generateAll(String yamlDir, String templateDir, String outputDir) throws Exception {
        Files.createDirectories(Paths.get(outputDir));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(yamlDir), "*.yaml")) {
            for (Path yamlFile : stream) {
                Map<String, Object> definition = loadYaml(yamlFile);
                enrichDefinition(definition);
                generateModule(definition, templateDir, outputDir);
            }
        }
    }

    private Map<String, Object> loadYaml(Path yamlFile) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return yaml.load(in);
        }
    }

    private void enrichDefinition(Map<String, Object> def) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) def.get("fields");

        for (Map<String, Object> field : fields) {
            String type = (String) field.get("type");
            field.put("javaType", JAVA_TYPES.get(type));
            field.put("sqlType", SQL_TYPES.get(type));
            field.put("javaClass", JAVA_TYPES.get(type));
        }
    }

    private void generateModule(Map<String, Object> def, String templateDir, String outputDir) throws Exception {
        String name = (String) def.get("name");

        generate("dto.mustache", def, outputDir + "/dtos/" + name + "Dto.java");
        generate("model.mustache", def, outputDir + "/models/" + name + ".java");
      //  generate("modelmeta.mustache", def, outputDir + "/model/" + name + "Meta.java");
        generate("dbservice.mustache", def, outputDir + "/data/" + name + "sDbService.java");
        generate("handler.mustache", def, outputDir + "/handlers/" + name + "sHandler.java");
        generate("controller.mustache", def, outputDir + "/adapters.inbound/" + name + "sController.java");
        generate("converter.mustache", def, outputDir + "/converters/" + name + "Converter.java");
        generate("migration.mustache", def, outputDir + "/migrations/V" + timestamp() + "__create_" + def.get("table") + ".sql");
    }

    private void generate(String templateName, Map<String, Object> data, String outputPath) throws Exception {
        Mustache mustache = mustacheFactory.compile("generator/templates/" + templateName);

        Path out = Paths.get(outputPath);
        Files.createDirectories(out.getParent());

        try (Writer writer = new FileWriter(out.toFile())) {
            mustache.execute(writer, data).flush();
        }
    }

    private String timestamp() {
        return String.valueOf(System.currentTimeMillis());
    }
}
