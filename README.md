# 📦 **Class Generator — README**

## Overview
The Class Generator is a lightweight code‑generation tool designed to automate the creation of module boilerplate for the GossamerCMS framework.  
Given a simple YAML definition, the generator produces:

- DTO classes
- Entity models
- ModelMeta definitions
- Database services
- Handlers
- Controllers
- Converters
- SQL migration files

This eliminates repetitive coding and ensures consistent structure across all modules.

---

## 📁 Project Structure

Your generator expects the following directory layout:

```
project-root/
  generator/
    definitions/     # YAML module definitions
    templates/       # Mustache templates
  output/            # Generated files
  src/
  pom.xml
```

### Definitions
Each module is described using a YAML file inside:

```
generator/definitions/
```

Example:

```yaml
name: PatientProvider
table: patient_providers
fields:
  - name: patientId
    type: UUID
  - name: providerId
    type: UUID
  - name: relationship
    type: String
    length: 255
```

### Templates
Mustache templates live inside:

```
generator/templates/
```

Each template corresponds to a generated artifact:

```
dto.mustache
model.mustache
modelmeta.mustache
dbservice.mustache
handler.mustache
controller.mustache
converter.mustache
migration.mustache
```

---

## ⚙️ How It Works

### 1. Load YAML
The generator scans the `definitions` directory and loads each YAML file.

### 2. Enrich Field Metadata
Each field is mapped to:

- Java type
- SQL type
- Java class name

This ensures templates receive fully‑resolved type information.

### 3. Render Templates
Mustache templates are rendered using the enriched definition and written to the `output` directory.

### 4. Output Structure
Generated files are placed into:

```
output/dto/
output/model/
output/db/
output/handler/
output/controller/
output/converter/
output/migration/
```

Each module gets its own set of files.

---

## ▶️ Running the Generator

From the project root:

```
mvn -q exec:java -Dexec.mainClass="generator.Main"
```

Or run the `Main` class directly from your IDE.

The generator will:

- scan `generator/definitions/`
- load all `.yaml` files
- generate all module classes into `output/`

---

## 🧩 Adding a New Module

1. Create a new YAML file in `generator/definitions/`
2. Define fields and table name
3. Run the generator
4. Copy generated files from `output/` into your project’s module directory

---

## 🔧 Customizing Templates

All generated code is driven by Mustache templates.  
To change the structure of any generated class, edit the corresponding template in:

```
generator/templates/
```

You can add:

- imports
- annotations
- method bodies
- additional metadata

The generator will apply your changes to all future modules.

---

## 🧱 Base Classes

The generator assumes your project uses shared base classes such as:

- `BaseDbService`
- `BaseHandler`
- `BaseController`
- `BaseConverter`
- `DtoWithId`
- `ModelMeta`

These allow the generated classes to remain small and consistent.

---

## 🛠 Extending the Generator

You can easily extend the generator to:

- support new field types
- generate additional artifacts
- add validation metadata
- generate frontend models
- produce OpenAPI specs

All logic is contained in `ModuleGenerator.java`, making it simple to modify.

---

## 🧪 Troubleshooting

### Templates not found
Ensure your templates are located at:

```
generator/templates/
```

### Definitions not found
Ensure your YAML files are located at:

```
generator/definitions/
```

### Converter not called
Ensure generated converters explicitly implement:

```java
Converter<String, YourDto>
```

Spring only registers converters with concrete generic types.

---

## 📜 License
Internal use within GossamerCMS.  
Not intended for external distribution.

---
