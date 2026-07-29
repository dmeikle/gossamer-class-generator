import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final Map<String, String> JAVA_TYPES = Map.ofEntries(
            Map.entry("UUID", "UUID"),
            Map.entry("String", "String"),
            Map.entry("Text", "String"),
            Map.entry("Boolean", "Boolean"),
            Map.entry("Integer", "Integer"),
            Map.entry("Date", "LocalDate"),
            Map.entry("Instant", "Instant"),
            Map.entry("Timestamp", "Instant")
    );

    private static final Map<String, String> SQL_TYPES = Map.ofEntries(
            Map.entry("UUID", "UUID"),
            Map.entry("String", "VARCHAR"),
            Map.entry("Text", "TEXT"),
            Map.entry("Boolean", "BOOLEAN"),
            Map.entry("Integer", "INTEGER"),
            Map.entry("Date", "DATE"),
            Map.entry("Instant", "TIMESTAMP WITH TIME ZONE"),
            Map.entry("Timestamp", "TIMESTAMP WITH TIME ZONE")
    );

    // Matches things like String(50), String(200), Decimal(5,2), etc.
    private static final Pattern PARAMETERIZED_TYPE = Pattern.compile("^(\\w+)\\(([^)]+)\\)$");

    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();

    public static void main(String[] args) throws Exception {
        System.out.println("Working directory: " + Paths.get("").toAbsolutePath());
        System.out.println(Files.exists(Paths.get("generator/definitions")));
        System.out.println(Files.exists(Paths.get("generator/templates")));
        Main generator = new Main();
        generator.generateAll("generator/definitions", "generator/templates", "output/");
    }

    public void generateAll(String yamlDir, String templateDir, String outputDir) throws Exception {
        Files.createDirectories(Paths.get(outputDir));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(yamlDir), "*.yaml")) {
            boolean found = false;

            for (Path yamlFile : stream) {
                found = true;
                System.out.println("Found: " + yamlFile);
                Map<String, Object> definition = loadYaml(yamlFile);
                enrichDefinition(definition);
                generateModule(definition, templateDir, outputDir);
            }

            if (!found) {
                System.out.println("No yaml files found.");
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
            String rawType = (String) field.get("type");
            System.out.println(rawType);

            String baseType = rawType;
            String param = null;

            Matcher m = PARAMETERIZED_TYPE.matcher(rawType);
            if (m.matches()) {
                baseType = m.group(1);
                param = m.group(2);
            }

            String javaType = JAVA_TYPES.get(baseType);
            String sqlType = SQL_TYPES.get(baseType);

            if (javaType == null || sqlType == null) {
                throw new IllegalArgumentException(
                        "Unknown type '" + rawType + "' in field '" + field.get("name") + "'");
            }

            // Append the parameter back onto the SQL type, e.g. VARCHAR(50), DECIMAL(5,2)
            if (param != null && ("VARCHAR".equals(sqlType) || "DECIMAL".equals(sqlType) || "NUMERIC".equals(sqlType))) {
                sqlType = sqlType + "(" + param + ")";
            }

            field.put("javaType", javaType);
            field.put("sqlType", sqlType);
            field.put("javaClass", javaType);
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
        System.out.println("Generating " + outputPath);
        Mustache mustache = mustacheFactory.compile("generator/templates/" + templateName);

        Path out = Paths.get(outputPath);
        Files.createDirectories(out.getParent());

        try (Writer writer = new FileWriter(out.toFile())) {
            mustache.execute(writer, data).flush();
        }

        System.out.println("Created " + out.toAbsolutePath());
    }

    private String timestamp() {
        return String.valueOf(System.currentTimeMillis());
    }
}