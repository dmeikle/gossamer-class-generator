import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
            Map.entry("BigDecimal", "BigDecimal"),
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
            Map.entry("BigDecimal", "DECIMAL"),
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
        String migrationTimestamp = timestamp();
        int migrationSequence = nextMigrationSequence(
                Paths.get(outputDir, "migrations"), migrationTimestamp);
        List<Map<String, Object>> definitions = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(yamlDir), "*.yaml")) {
            for (Path yamlFile : stream) {
                System.out.println("Found: " + yamlFile);
                definitions.add(loadYaml(yamlFile));
            }

            if (definitions.isEmpty()) {
                System.out.println("No yaml files found.");
                return;
            }
        }

        Map<String, String> tableByDefinition = new HashMap<>();
        for (Map<String, Object> definition : definitions) {
            tableByDefinition.put((String) definition.get("name"), (String) definition.get("table"));
        }
        for (Map<String, Object> definition : definitions) {
            enrichDefinition(definition, tableByDefinition);
        }

        for (Map<String, Object> definition : orderDefinitionsByReferences(definitions)) {
            generateModule(definition, templateDir, outputDir, migrationTimestamp, migrationSequence++);
        }
    }

    private Map<String, Object> loadYaml(Path yamlFile) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return yaml.load(in);
        }
    }

    private void enrichDefinition(Map<String, Object> def, Map<String, String> tableByDefinition) {
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
            Object reference = field.get("reference");
            if (reference != null) {
                String referencedDefinition = reference.toString();
                field.put("referenceTable", tableByDefinition.getOrDefault(
                        referencedDefinition, conventionalTableName(referencedDefinition)));
                field.put("constraintName", "fk_" + def.get("table") + "_" + field.get("name"));
                field.put("indexName", "idx_" + def.get("table") + "_" + field.get("name"));
            }
        }

        def.put("migrationFields", fields.stream()
                .filter(field -> !Set.of("id", "createdAt", "updatedAt").contains(field.get("name")))
                .toList());
        def.put("migrationReferences", fields.stream()
                .filter(field -> field.get("reference") != null)
                .toList());
    }

    private List<Map<String, Object>> orderDefinitionsByReferences(List<Map<String, Object>> definitions) {
        List<Map<String, Object>> ordered = new ArrayList<>();
        Set<String> createdDefinitions = new HashSet<>();
        Set<String> definitionNames = new HashSet<>();
        for (Map<String, Object> definition : definitions) {
            definitionNames.add((String) definition.get("name"));
        }

        while (ordered.size() < definitions.size()) {
            boolean addedDefinition = false;
            for (Map<String, Object> definition : definitions) {
                String name = (String) definition.get("name");
                if (createdDefinitions.contains(name)) {
                    continue;
                }

                List<Map<String, Object>> fields = (List<Map<String, Object>>) definition.get("fields");
                boolean referencesReady = fields.stream()
                        .map(field -> field.get("reference"))
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(definitionNames::contains)
                        .filter(reference -> !reference.equals(name))
                        .allMatch(createdDefinitions::contains);
                if (referencesReady) {
                    ordered.add(definition);
                    createdDefinitions.add(name);
                    addedDefinition = true;
                }
            }

            if (!addedDefinition) {
                throw new IllegalArgumentException("Circular references between YAML definitions prevent migration ordering");
            }
        }
        return ordered;
    }

    private String conventionalTableName(String definitionName) {
        String snakeCase = definitionName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (snakeCase.endsWith("y")) {
            return snakeCase.substring(0, snakeCase.length() - 1) + "ies";
        }
        return snakeCase.endsWith("s") ? snakeCase : snakeCase + "s";
    }

    private void generateModule(
            Map<String, Object> def,
            String templateDir,
            String outputDir,
            String migrationTimestamp,
            int migrationSequence) throws Exception {
        String name = (String) def.get("name");

        generate("dto.mustache", def, outputDir + "/dtos/" + name + "Dto.java");
        generate("model.mustache", def, outputDir + "/models/" + name + ".java");
        //  generate("modelmeta.mustache", def, outputDir + "/model/" + name + "Meta.java");
        generate("dbservice.mustache", def, outputDir + "/data/" + name + "sDbService.java");
        generate("handler.mustache", def, outputDir + "/handlers/" + name + "sHandler.java");
        generate("controller.mustache", def, outputDir + "/adapters.inbound/" + name + "sController.java");
        generate("converter.mustache", def, outputDir + "/converters/" + name + "Converter.java");
        generate("migration.mustache", def, outputDir + "/migrations/V" + migrationTimestamp + migrationSequence
                + "__create_" + def.get("table") + ".sql");
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
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    }

    private int nextMigrationSequence(Path migrationDir, String migrationTimestamp) throws IOException {
        int nextSequence = 100;
        if (!Files.isDirectory(migrationDir)) {
            return nextSequence;
        }

        String versionPrefix = "V" + migrationTimestamp;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(migrationDir, versionPrefix + "*")) {
            for (Path migration : stream) {
                String filename = migration.getFileName().toString();
                int delimiter = filename.indexOf("__", versionPrefix.length());
                if (delimiter < 0) {
                    continue;
                }

                try {
                    nextSequence = Math.max(nextSequence,
                            Integer.parseInt(filename.substring(versionPrefix.length(), delimiter)) + 1);
                } catch (NumberFormatException ignored) {
                    // Ignore migration names that do not use a numeric sequence.
                }
            }
        }
        return nextSequence;
    }
}