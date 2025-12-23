# HL7v2-to-FHIR Converter: Technical Deep Dive

**Purpose**: This document provides a comprehensive technical analysis of the hl7v2-fhir-converter architecture, designed for developers who need to extend the converter for validation, parallel pipelines (e.g., OMOP, IBM UDM), or custom canonical data models.

**Audience**: Developers building validation frameworks, custom transformation pipelines, or analytics platforms that require 100% data fidelity from HL7v2 messages.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Classes and Responsibilities](#core-classes-and-responsibilities)
3. [Conversion Flow: Step-by-Step](#conversion-flow-step-by-step)
4. [YAML Template System](#yaml-template-system)
5. [Expression Evaluation Engine](#expression-evaluation-engine)
6. [Extension Points for Custom Pipelines](#extension-points-for-custom-pipelines)
7. [Data Fidelity and Loss Prevention](#data-fidelity-and-loss-prevention)
8. [Validation Strategies](#validation-strategies)
9. [Parallel Pipeline Architecture (OMOP/UDM)](#parallel-pipeline-architecture)

---

## 1. Architecture Overview

### High-Level Architecture

```
┌─────────────────────┐
│   HL7v2 Message     │
│   (Raw String)      │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ HL7ToFHIRConverter  │ ◄─── Entry Point
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   HL7HapiParser     │ ◄─── Parses to HAPI Message (v2.6)
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  HL7DataExtractor   │ ◄─── Determines message type
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   ResourceReader    │ ◄─── Loads YAML templates
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  HL7MessageModel    │ ◄─── Message structure (ADT_A01, etc.)
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  HL7MessageEngine   │ ◄─── Coordinates resource generation
└──────────┬──────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  HL7DataBasedResourceModel (loop)  │ ◄─── One per FHIR resource
│  - Patient, Encounter, Observation  │
└──────────┬──────────────────────────┘
           │
           ▼
┌─────────────────────┐
│ ExpressionUtility   │ ◄─── Evaluates YAML expressions
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   FHIR Resources    │
│   (Patient, etc.)   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│    FHIRContext      │ ◄─── Serializes to JSON/XML
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   FHIR Bundle       │
│   (JSON/XML)        │
└─────────────────────┘
```

### Key Design Principles

1. **Template-Driven**: All mappings defined in YAML, not hard-coded
2. **Two-Phase Loading**: Message templates reference resource templates
3. **Expression-Based**: Field mappings use JEXL, HL7Spec, or resource references
4. **HAPI-Based Parsing**: Leverages HAPI HL7v2 library for parsing
5. **Version Normalization**: Parses all messages as v2.6 (limitation for extension)

---

## 2. Core Classes and Responsibilities

### 2.1 HL7ToFHIRConverter

**Location**: `src/main/java/io/github/linuxforhealth/hl7/HL7ToFHIRConverter.java`

**Responsibility**: Entry point for conversion, orchestrates the entire process.

**Key Methods**:
- `convert(String hl7MessageData, ConverterOptions options)`: Main conversion method, returns JSON string
- `convertToBundle(String hl7MessageData, ConverterOptions options, HL7MessageEngine engine)`: Returns FHIR Bundle object
- `getHl7Message(String hl7MessageData)`: Delegates to HL7HapiParser
- `getMessageEngine(ConverterOptions options)`: Creates engine with context and templates

**Constructor Behavior**:
```java
public HL7ToFHIRConverter() {
    // Loads all message templates from config.properties
    this.messagetemplates = new ResourceReader().getMessageTemplates();
}
```

**Critical Code Path**:
```java
public Bundle convertToBundle(String hl7MessageData, ConverterOptions options,
                                HL7MessageEngine engine) {
    // 1. Parse HL7 string to HAPI Message
    Message hl7message = getHl7Message(hl7MessageData);

    // 2. Determine message type (e.g., "ADT_A01")
    String messageType = HL7DataExtractor.getMessageType(hl7message);

    // 3. Get corresponding message template
    HL7MessageModel hl7MessageTemplateModel = messagetemplates.get(messageType);

    // 4. Convert using template
    return hl7MessageTemplateModel.convert(hl7message, engine);
}
```

**Extension Point**: To create a parallel pipeline (e.g., OMOP), you can subclass this or create a similar orchestrator that uses the same parsed `Message` object.

---

### 2.2 HL7HapiParser

**Location**: `src/main/java/io/github/linuxforhealth/hl7/parsing/HL7HapiParser.java`

**Responsibility**: Parses raw HL7 string into HAPI `Message` object.

**Critical Limitation**: Hard-coded to v2.6
```java
private static final String SUPPORTED_HL7_VERSION = "2.6";

public HL7HapiParser() {
    context = new DefaultHapiContext();
    // Forces all messages to be parsed as v2.6, ignoring MSH-12
    CanonicalModelClassFactory mcf = new CanonicalModelClassFactory(SUPPORTED_HL7_VERSION);
    context.setModelClassFactory(mcf);
    context.getParserConfiguration().setValidating(false);
    parser = context.getGenericParser();
}
```

**Key Methods**:
- `getParser()`: Returns the configured HAPI parser
- `getHapiMessage(String message)`: Parses HL7 string, returns `Message` object

**Extension Point**: For version-aware parsing, replace `CanonicalModelClassFactory` with dynamic version detection:
```java
// Extension example (not in original code):
String version = extractVersionFromMSH12(hl7String);
CanonicalModelClassFactory mcf = new CanonicalModelClassFactory(version);
```

**Why This Matters for Your Use Case**:
- For OMOP/analytics, you need to preserve the actual version
- Z segments may behave differently across versions
- Consider creating a `VersionAwareHL7Parser` for your pipeline

---

### 2.3 HL7DataExtractor

**Location**: `src/main/java/io/github/linuxforhealth/hl7/parsing/HL7DataExtractor.java`

**Responsibility**: Extracts segments, fields, and metadata from HAPI `Message` objects.

**Key Methods**:
```java
// Get message type (e.g., "ADT_A01")
public static String getMessageType(Message message) {
    MSH msh = (MSH) message.get("MSH");
    return msh.getMessageType().getMsg1_MessageCode().getValue() + "_"
            + msh.getMessageType().getMsg2_TriggerEvent().getValue();
}

// Get field value by HL7 spec (e.g., "PID.5.1")
public static String getFieldValue(Message message, String spec) {
    // Parses spec and navigates HAPI structure
}

// Extract repeating segments (e.g., all OBX segments)
public static List<Segment> getSegments(Message message, String segmentName) {
    // Returns all occurrences of a segment
}
```

**Deep Dive: Field Extraction**:
The extractor uses HAPI's reflection-based navigation:
```java
// For spec "PID.3.1" (Patient ID)
// 1. Get PID segment
Segment pid = message.get("PID");
// 2. Get field 3 (Patient Identifier List)
Type field = pid.getField(3, 0); // Field 3, repetition 0
// 3. Get component 1
Composite composite = (Composite) field;
Type component = composite.getComponent(0);
// 4. Get value
String value = Terser.getPrimitive(component, 1, 1).getValue();
```

**Extension Point**: For validation, you can use this to compare extracted values against your expected mappings.

---

### 2.4 ResourceReader

**Location**: `src/main/java/io/github/linuxforhealth/hl7/resource/ResourceReader.java`

**Responsibility**: Loads YAML templates from classpath and filesystem.

**Two-Phase Loading**:

**Phase 1: Load Message Templates**
```java
public Map<String, HL7MessageModel> getMessageTemplates() {
    Map<String, HL7MessageModel> messagetemplates = new HashMap<>();

    // Read from config.properties: "supported.hl7.messages"
    List<String> supportedMessageTemplates =
        ConverterConfiguration.getInstance().getSupportedMessageTemplates();

    // For each message type (e.g., "ADT_A01")
    for (String template : supportedMessageTemplates) {
        HL7MessageModel rm = getMessageModel(template);
        messagetemplates.put(
            Files.getNameWithoutExtension(template), rm);
    }
    return messagetemplates;
}
```

**Phase 2: Load Resource Templates** (called by message template parsing)
```java
private HL7DataBasedResourceModel getResourceModel(String templateName) {
    String yamlContent = getResourceInHl7Folder(
        Constants.RESOURCE_BASE_PATH + templateName + ".yml");
    return parseYamlToResourceModel(yamlContent);
}
```

**Configuration File**: `src/main/resources/config.properties`
```properties
supported.hl7.messages=ADT_A01, ADT_A03, ADT_A04, ADT_A08, ...
additional.resources.location=  # Optional filesystem path
```

**Template Locations**:
- Message templates: `src/main/resources/hl7/message/*.yml`
- Resource templates: `src/main/resources/hl7/resource/*.yml`
- Datatype templates: `src/main/resources/hl7/datatype/*.yml`

**Extension Point**: For custom templates (OMOP, UDM), you can:
1. Add new message types to `config.properties`
2. Create custom resource templates in a separate directory
3. Use `additional.resources.location` to point to external templates

---

### 2.5 HL7MessageModel

**Location**: `src/main/java/io/github/linuxforhealth/hl7/message/HL7MessageModel.java`

**Responsibility**: Represents the structure of an HL7 message type (e.g., ADT_A01).

**Structure**:
```java
public class HL7MessageModel {
    private List<FHIRResourceTemplate> resources;  // List of FHIR resources to generate
    private String messageName;  // e.g., "ADT_A01"

    public Bundle convert(Message hl7message, HL7MessageEngine engine) {
        // Iterates through resources and generates FHIR Bundle
    }
}
```

**Loaded from YAML** (e.g., `ADT_A01.yml`):
```yaml
resources:
  - resourceName: Patient
    segment: PID
    resourcePath: resource/Patient
    repeats: false
    isReferenced: true
    additionalSegments:
      - PD1
      - MSH

  - resourceName: Encounter
    segment: PV1
    resourcePath: resource/Encounter
    repeats: false
    isReferenced: true
    additionalSegments:
      - PV2
```

**Key Properties**:
- `resourceName`: FHIR resource type
- `segment`: Primary HL7 segment for this resource
- `resourcePath`: Path to resource template YAML
- `repeats`: Whether segment can repeat (e.g., OBX)
- `isReferenced`: Whether resource is referenced by others
- `additionalSegments`: Extra segments available for field mapping

**Conversion Logic**:
```java
public Bundle convert(Message hl7message, HL7MessageEngine engine) {
    engine.initBundle();

    for (FHIRResourceTemplate resourceTemplate : resources) {
        // 1. Extract primary segment (e.g., PID)
        List<Segment> segments = getSegments(hl7message, resourceTemplate.getSegment());

        // 2. For each segment occurrence
        for (Segment segment : segments) {
            // 3. Collect additional segments
            Map<String, Object> contextValues = collectAdditionalSegments(
                hl7message, resourceTemplate.getAdditionalSegments());

            // 4. Generate FHIR resource
            engine.generateResource(resourceTemplate, segment, contextValues);
        }
    }

    return engine.getBundle();
}
```

**Extension Point**: For OMOP, you'd create an `OMOPMessageModel` with similar structure:
```java
public class OMOPMessageModel {
    private List<OMOPTableTemplate> tables;  // person, visit_occurrence, etc.

    public Map<String, List<Map<String, Object>>> convert(Message hl7message) {
        // Similar iteration, but produces relational rows instead of FHIR
    }
}
```

---

### 2.6 HL7MessageEngine

**Location**: `src/main/java/io/github/linuxforhealth/hl7/message/HL7MessageEngine.java`

**Responsibility**: Coordinates resource generation, manages context, and builds FHIR Bundle.

**Key Responsibilities**:
1. Maintains FHIR context (for serialization)
2. Manages resource cache (for references)
3. Tracks generated resources
4. Builds final Bundle

**Core Methods**:
```java
public class HL7MessageEngine {
    private FHIRContext fhirContext;
    private Bundle bundle;
    private Map<String, Resource> resourceCache;  // For reference resolution

    public void initBundle() {
        this.bundle = new Bundle();
        this.bundle.setType(Bundle.BundleType.COLLECTION);
    }

    public void generateResource(FHIRResourceTemplate template,
                                  Segment primarySegment,
                                  Map<String, Object> contextValues) {
        // 1. Load resource model
        HL7DataBasedResourceModel resourceModel = template.getResourceModel();

        // 2. Evaluate expressions to generate FHIR resource
        Resource resource = resourceModel.evaluate(primarySegment, contextValues, this);

        // 3. Add to bundle
        addResourceToBundle(resource);

        // 4. Cache for reference resolution
        if (template.isReferenced()) {
            cacheResource(resource.getResourceType(), resource.getId(), resource);
        }
    }

    public Bundle getBundle() {
        return this.bundle;
    }
}
```

**Context Management**:
The engine maintains two types of context:
1. **Resource Context**: Cached resources for reference resolution
2. **Value Context**: Variables passed between resource generations

**Reference Resolution Example**:
```yaml
# In Encounter.yml
subject:
  reference: $ref:Patient  # Engine looks up Patient in cache
```

**Extension Point**: For OMOP, you'd create an `OMOPEngine` that:
- Maintains foreign key mappings instead of FHIR references
- Generates relational rows instead of FHIR resources
- Tracks primary keys for referential integrity

---

### 2.7 HL7DataBasedResourceModel

**Location**: `src/main/java/io/github/linuxforhealth/hl7/resource/HL7DataBasedResourceModel.java`

**Responsibility**: Represents a single FHIR resource template (e.g., Patient, Encounter).

**Structure**:
```java
public class HL7DataBasedResourceModel {
    private String resourceType;  // "Patient", "Encounter", etc.
    private Map<String, Expression> expressions;  // Field mappings

    public Resource evaluate(Segment primarySegment,
                            Map<String, Object> contextValues,
                            HL7MessageEngine engine) {
        // Generate FHIR resource by evaluating expressions
    }
}
```

**Loaded from YAML** (e.g., `Patient.yml`):
```yaml
resourceType: Patient

id:
  type: STRING
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL

identifier:
  valueOf: datatype/Identifier_SystemID
  generateList: true
  expressionType: resource
  specs: PID.3

name:
  valueOf: datatype/HumanName
  generateList: true
  expressionType: resource
  specs: PID.5

gender:
  type: STRING
  valueOf: PID.8
  expressionType: HL7Spec

birthDate:
  type: STRING
  valueOf: PID.7
  expressionType: HL7Spec
```

**Expression Types**:
1. **JEXL**: Java Expression Language (for computed values)
   ```yaml
   id:
     valueOf: "GeneralUtils.generateResourceId()"
     expressionType: JEXL
   ```

2. **HL7Spec**: Direct field extraction
   ```yaml
   gender:
     valueOf: PID.8
     expressionType: HL7Spec
   ```

3. **resource**: Reference to another template (datatype or resource)
   ```yaml
   identifier:
     valueOf: datatype/Identifier_SystemID
     expressionType: resource
     specs: PID.3
   ```

**Evaluation Process**:
```java
public Resource evaluate(Segment primarySegment,
                        Map<String, Object> contextValues,
                        HL7MessageEngine engine) {
    // 1. Create empty FHIR resource
    Resource resource = createResource(resourceType);

    // 2. Evaluate each expression
    for (Map.Entry<String, Expression> entry : expressions.entrySet()) {
        String fieldName = entry.getKey();
        Expression expression = entry.getValue();

        // 3. Get value from expression
        Object value = ExpressionUtility.evaluate(
            expression, primarySegment, contextValues, engine);

        // 4. Set field on resource
        setResourceField(resource, fieldName, value);
    }

    return resource;
}
```

**Extension Point**: For OMOP, you'd create `OMOPTableModel`:
```java
public class OMOPTableModel {
    private String tableName;  // "person", "visit_occurrence", etc.
    private Map<String, ColumnExpression> columns;

    public Map<String, Object> evaluate(Segment primarySegment,
                                        Map<String, Object> contextValues) {
        Map<String, Object> row = new HashMap<>();

        for (Map.Entry<String, ColumnExpression> entry : columns.entrySet()) {
            String columnName = entry.getKey();
            ColumnExpression expression = entry.getValue();

            Object value = evaluateExpression(expression, primarySegment, contextValues);
            row.put(columnName, value);
        }

        return row;
    }
}
```

---

### 2.8 ExpressionUtility

**Location**: `src/main/java/io/github/linuxforhealth/hl7/util/ExpressionUtility.java`

**Responsibility**: Evaluates expressions defined in YAML templates.

**Three Expression Types**:

**1. JEXL Expressions** (Computed Values)
```java
public static Object evaluateJEXL(String expression, Map<String, Object> context) {
    JexlEngine jexl = new JexlBuilder().create();
    JexlExpression jexlExpression = jexl.createExpression(expression);
    JexlContext jexlContext = new MapContext(context);
    return jexlExpression.evaluate(jexlContext);
}
```

Example:
```yaml
id:
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL
```

**2. HL7Spec Expressions** (Direct Field Extraction)
```java
public static Object evaluateHL7Spec(String spec, Segment segment) {
    // Parse spec (e.g., "PID.5.1")
    String[] parts = spec.split("\\.");
    String segmentName = parts[0];  // "PID"
    int fieldNum = Integer.parseInt(parts[1]);  // 5
    int componentNum = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;  // 1

    // Extract from HAPI segment
    Type field = segment.getField(fieldNum, 0);
    if (componentNum > 0 && field instanceof Composite) {
        Composite composite = (Composite) field;
        field = composite.getComponent(componentNum - 1);
    }

    return Terser.getPrimitive(field, 1, 1).getValue();
}
```

Example:
```yaml
gender:
  valueOf: PID.8
  expressionType: HL7Spec
```

**3. Resource Expressions** (Template References)
```java
public static Object evaluateResource(String resourcePath, String spec,
                                      Segment segment, Map<String, Object> context,
                                      HL7MessageEngine engine) {
    // 1. Load referenced template
    HL7DataBasedResourceModel referencedModel =
        engine.getResourceReader().getResourceModel(resourcePath);

    // 2. Extract data for referenced template
    Object data = evaluateHL7Spec(spec, segment);

    // 3. Evaluate referenced template
    return referencedModel.evaluate(data, context, engine);
}
```

Example:
```yaml
identifier:
  valueOf: datatype/Identifier_SystemID
  expressionType: resource
  specs: PID.3
```

**Datatype Template** (`datatype/Identifier_SystemID.yml`):
```yaml
system:
  valueOf: PID.3.4
  expressionType: HL7Spec

value:
  valueOf: PID.3.1
  expressionType: HL7Spec
```

**Extension Point**: For custom pipelines, you can add new expression types:
```java
public static Object evaluateOMOPConcept(String conceptExpression,
                                          Segment segment,
                                          OMOPVocabularyService vocabService) {
    // Map HL7 code to OMOP concept_id
    String code = evaluateHL7Spec(conceptExpression, segment);
    return vocabService.mapToConceptId(code);
}
```

---

### 2.9 FHIRContext

**Location**: HAPI FHIR library (`ca.uhn.fhir.context.FhirContext`)

**Responsibility**: Serializes and validates FHIR resources.

**Usage in Converter**:
```java
public class HL7MessageEngine {
    private FHIRContext fhirContext;

    public HL7MessageEngine() {
        // Initialize for FHIR R4
        this.fhirContext = FhirContext.forR4();
    }

    public String serializeBundle(Bundle bundle) {
        return fhirContext.newJsonParser()
            .setPrettyPrint(true)
            .encodeResourceToString(bundle);
    }

    public Bundle parseBundle(String json) {
        return fhirContext.newJsonParser()
            .parseResource(Bundle.class, json);
    }
}
```

**Key Methods**:
- `newJsonParser()`: Creates JSON serializer
- `newXmlParser()`: Creates XML serializer
- `newValidator()`: Creates FHIR validator

**Extension Point**: For validation, use the FHIR validator:
```java
FhirValidator validator = fhirContext.newValidator();
ValidationResult result = validator.validateWithResult(resource);

if (!result.isSuccessful()) {
    for (SingleValidationMessage message : result.getMessages()) {
        System.out.println("Issue: " + message.getMessage());
    }
}
```

---

## 3. Conversion Flow: Step-by-Step

Let's trace a complete conversion of an ADT_A01 message to understand the exact flow:

### Input Message
```
MSH|^~\&|SE050|050|PACS|050|20120912011230||ADT^A01|102|T|2.6|||AL|NE
EVN||201209122222
PID|1||123456^^^MRN||DOE^JOHN^A||19800202|M
PV1|1|I|2000^2012^01||||004777^ATTEND^AARON^A|||SUR
```

### Step 1: Entry Point
```java
HL7ToFHIRConverter converter = new HL7ToFHIRConverter();
String json = converter.convert(hl7Message, options);
```

**What Happens**:
- Converter is instantiated
- Constructor loads all message templates from `config.properties`
- `convert()` is called with message string

### Step 2: Parse HL7 Message
```java
Message hl7message = HL7HapiParser.getHapiMessage(hl7MessageData);
```

**What Happens**:
- HL7HapiParser creates HAPI context with v2.6 model
- HAPI parser tokenizes the message:
  - Splits by `\r` (segment separator)
  - Splits by `|` (field separator)
  - Splits by `^` (component separator)
- Creates HAPI `Message` object (specifically `ADT_A01` structure)
- Populates segment objects (MSH, EVN, PID, PV1)

**Resulting Object** (simplified):
```java
ADT_A01 message = {
    MSH: {
        field1: "|",
        field2: "^~\\&",
        field3: "SE050",
        // ... all MSH fields
        field12: "2.6"  // Version (ignored due to hard-coding)
    },
    EVN: {
        field2: "201209122222"
    },
    PID: {
        field1: "1",
        field3: [{ // Patient ID
            component1: "123456",
            component4: "MRN"
        }],
        field5: [{ // Patient Name
            component1: "DOE",
            component2: "JOHN",
            component3: "A"
        }],
        field7: "19800202",
        field8: "M"
    },
    PV1: {
        field1: "1",
        field2: "I",
        // ... all PV1 fields
    }
}
```

### Step 3: Determine Message Type
```java
String messageType = HL7DataExtractor.getMessageType(hl7message);
// Result: "ADT_A01"
```

**What Happens**:
```java
MSH msh = (MSH) hl7message.get("MSH");
String code = msh.getMessageType().getMsg1_MessageCode().getValue();  // "ADT"
String trigger = msh.getMessageType().getMsg2_TriggerEvent().getValue();  // "A01"
return code + "_" + trigger;  // "ADT_A01"
```

### Step 4: Load Message Template
```java
HL7MessageModel template = messagetemplates.get("ADT_A01");
```

**What Happens**:
- Looks up "ADT_A01" in pre-loaded templates
- Returns `HL7MessageModel` loaded from `ADT_A01.yml`

**Template Structure** (from `ADT_A01.yml`):
```yaml
resources:
  - resourceName: Patient
    segment: PID
    resourcePath: resource/Patient
    isReferenced: true
    additionalSegments:
      - PD1
      - MSH

  - resourceName: Encounter
    segment: PV1
    resourcePath: resource/Encounter
    isReferenced: true
    additionalSegments:
      - PV2
      - MSH
```

### Step 5: Convert Using Template
```java
Bundle bundle = template.convert(hl7message, engine);
```

**What Happens**:
```java
public Bundle convert(Message hl7message, HL7MessageEngine engine) {
    engine.initBundle();  // Create empty FHIR Bundle

    // Iterate through resource templates
    for (FHIRResourceTemplate resourceTemplate : resources) {
        // Resource 1: Patient (segment: PID)
        // Resource 2: Encounter (segment: PV1)

        generateResource(resourceTemplate, hl7message, engine);
    }

    return engine.getBundle();
}
```

### Step 6: Generate Patient Resource

**Step 6a: Extract Primary Segment**
```java
List<Segment> segments = getSegments(hl7message, "PID");
// Result: [PID segment object]
```

**Step 6b: Extract Additional Segments**
```java
Map<String, Object> contextValues = new HashMap<>();
contextValues.put("MSH", hl7message.get("MSH"));
contextValues.put("PD1", hl7message.get("PD1"));  // May be null
```

**Step 6c: Load Resource Model**
```java
HL7DataBasedResourceModel patientModel =
    resourceReader.getResourceModel("resource/Patient");
```

**Loaded from** `Patient.yml`:
```yaml
resourceType: Patient

id:
  type: STRING
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL

identifier:
  valueOf: datatype/Identifier_SystemID
  generateList: true
  expressionType: resource
  specs: PID.3

name:
  valueOf: datatype/HumanName
  generateList: true
  expressionType: resource
  specs: PID.5

gender:
  type: STRING
  valueOf: PID.8
  expressionType: HL7Spec

birthDate:
  type: STRING
  valueOf: PID.7
  expressionType: HL7Spec
```

**Step 6d: Evaluate Expressions**

**Field: `id`**
```java
Expression:
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL

Evaluation:
  JexlEngine.evaluate("GeneralUtils.generateResourceId()")
  → Result: "patient-123e4567-e89b-12d3-a456-426614174000"
```

**Field: `identifier`** (complex, references datatype template)
```java
Expression:
  valueOf: datatype/Identifier_SystemID
  expressionType: resource
  specs: PID.3

Evaluation:
  1. Extract PID.3 (all repetitions)
     → [{component1: "123456", component4: "MRN"}]

  2. Load datatype/Identifier_SystemID.yml

  3. For each repetition, evaluate:
     system:
       valueOf: PID.3.4
       → "MRN"

     value:
       valueOf: PID.3.1
       → "123456"

  4. Result: [{system: "MRN", value: "123456"}]
```

**Field: `name`** (complex, references datatype template)
```java
Expression:
  valueOf: datatype/HumanName
  expressionType: resource
  specs: PID.5

Evaluation:
  1. Extract PID.5 (all repetitions)
     → [{component1: "DOE", component2: "JOHN", component3: "A"}]

  2. Load datatype/HumanName.yml

  3. For each repetition, evaluate:
     family:
       valueOf: PID.5.1
       → "DOE"

     given:
       valueOf: PID.5.2
       generateList: true
       → ["JOHN", "A"]  (combines middle name too)

  4. Result: [{family: "DOE", given: ["JOHN", "A"]}]
```

**Field: `gender`** (simple HL7Spec)
```java
Expression:
  valueOf: PID.8
  expressionType: HL7Spec

Evaluation:
  1. Extract PID.8 from segment
     → "M"

  2. Apply code mapping (if configured)
     → "male" (FHIR requires lowercase)

  3. Result: "male"
```

**Field: `birthDate`** (simple HL7Spec)
```java
Expression:
  valueOf: PID.7
  expressionType: HL7Spec

Evaluation:
  1. Extract PID.7 from segment
     → "19800202"

  2. Format as FHIR date
     → "1980-02-02"

  3. Result: "1980-02-02"
```

**Step 6e: Construct FHIR Resource**
```java
Patient patient = new Patient();
patient.setId("patient-123e4567-e89b-12d3-a456-426614174000");
patient.addIdentifier(new Identifier().setSystem("MRN").setValue("123456"));
patient.addName(new HumanName().setFamily("DOE").addGiven("JOHN").addGiven("A"));
patient.setGender(Enumerations.AdministrativeGender.MALE);
patient.setBirthDate(new SimpleDateFormat("yyyy-MM-dd").parse("1980-02-02"));
```

**Step 6f: Add to Bundle**
```java
engine.addResourceToBundle(patient);
engine.cacheResource("Patient", patient.getId(), patient);
```

### Step 7: Generate Encounter Resource

**Similar Process**:
1. Extract PV1 segment
2. Load `Encounter.yml` template
3. Evaluate expressions:
   - `id`: Generate UUID
   - `status`: Map from PV1.2
   - `class`: Map from PV1.2
   - `subject`: **Reference to Patient** (from cache)
   - `period.start`: Extract from PV1.44
4. Construct Encounter resource
5. Add to bundle

**Reference Resolution**:
```yaml
# In Encounter.yml
subject:
  valueOf: $ref:Patient
  expressionType: reference
```

**Evaluation**:
```java
String patientId = engine.getCachedResourceId("Patient");
Reference ref = new Reference("Patient/" + patientId);
encounter.setSubject(ref);
```

### Step 8: Finalize Bundle
```java
Bundle bundle = engine.getBundle();
bundle.setType(Bundle.BundleType.COLLECTION);
bundle.setTimestamp(new Date());
```

**Resulting Bundle**:
```json
{
  "resourceType": "Bundle",
  "type": "collection",
  "timestamp": "2025-12-22T10:30:00Z",
  "entry": [
    {
      "resource": {
        "resourceType": "Patient",
        "id": "patient-123e4567-e89b-12d3-a456-426614174000",
        "identifier": [
          {
            "system": "MRN",
            "value": "123456"
          }
        ],
        "name": [
          {
            "family": "DOE",
            "given": ["JOHN", "A"]
          }
        ],
        "gender": "male",
        "birthDate": "1980-02-02"
      }
    },
    {
      "resource": {
        "resourceType": "Encounter",
        "id": "encounter-789e4567-e89b-12d3-a456-426614174001",
        "status": "in-progress",
        "class": {
          "code": "IMP",
          "display": "inpatient encounter"
        },
        "subject": {
          "reference": "Patient/patient-123e4567-e89b-12d3-a456-426614174000"
        }
      }
    }
  ]
}
```

### Step 9: Serialize to JSON
```java
FHIRContext fhirContext = engine.getFHIRContext();
String json = fhirContext.newJsonParser()
    .setPrettyPrint(true)
    .encodeResourceToString(bundle);
return json;
```

---

## 4. YAML Template System

The converter's flexibility comes from its template-driven architecture. Understanding the YAML structure is critical for extension.

### Template Hierarchy

```
hl7/
├── message/           # Message-level templates
│   ├── ADT_A01.yml
│   ├── ADT_A08.yml
│   ├── ORU_R01.yml
│   └── ...
├── resource/          # FHIR resource templates
│   ├── Patient.yml
│   ├── Encounter.yml
│   ├── Observation.yml
│   └── ...
└── datatype/          # FHIR datatype templates
    ├── HumanName.yml
    ├── Identifier.yml
    ├── CodeableConcept.yml
    └── ...
```

### Message Template Structure

**File**: `hl7/message/ADT_A01.yml`

```yaml
# List of FHIR resources to generate from this message type
resources:
  - resourceName: MessageHeader      # FHIR resource type
    segment: MSH                     # Primary HL7 segment
    resourcePath: resource/MessageHeader  # Path to resource template
    repeats: false                   # Whether segment repeats
    isReferenced: false              # Whether other resources reference this
    additionalSegments:              # Extra segments available for mapping
      - EVN

  - resourceName: Patient
    segment: PID
    resourcePath: resource/Patient
    repeats: false
    isReferenced: true               # Referenced by Encounter, Observation, etc.
    additionalSegments:
      - PD1                          # Patient Additional Demo
      - MSH                          # For facility info
      - INSURANCE.IN1                # Nested segments
      - INSURANCE.IN2

  - resourceName: Encounter
    segment: PV1
    resourcePath: resource/Encounter
    repeats: false
    isReferenced: true
    additionalSegments:
      - PV2
      - MSH

  - resourceName: AllergyIntolerance
    segment: AL1
    resourcePath: resource/AllergyIntolerance
    repeats: true                    # AL1 can repeat
    isReferenced: false
    additionalSegments:
      - MSH
      - PID                          # For patient context
```

**Key Properties Explained**:

1. **`segment`**: The HL7 segment that drives resource generation
   - Converter will loop through all occurrences of this segment
   - Each occurrence generates one FHIR resource (if `repeats: true`)

2. **`resourcePath`**: Path to resource template
   - Relative to `hl7/` directory
   - Does NOT include `.yml` extension

3. **`repeats`**: Segment repetition
   - `false`: Only first occurrence used (MSH, PID, PV1)
   - `true`: Each occurrence generates a resource (AL1, OBX, NTE)

4. **`isReferenced`**: Reference management
   - `true`: Resource is cached for reference by other resources
   - `false`: Resource is not referenced

5. **`additionalSegments`**: Context for field mapping
   - These segments are made available during expression evaluation
   - Allows accessing data from other segments (e.g., Encounter can access MSH for facility)

### Resource Template Structure

**File**: `hl7/resource/Patient.yml`

```yaml
# FHIR resource type
resourceType: Patient

# Each property corresponds to a FHIR field
id:
  type: STRING                       # Expected output type
  valueOf: "GeneralUtils.generateResourceId()"  # Expression to evaluate
  expressionType: JEXL               # Expression type

identifier:
  valueOf: datatype/Identifier_SystemID  # Reference to datatype template
  generateList: true                 # Output is an array
  expressionType: resource           # Expression type: resource reference
  specs: PID.3                       # HL7 field to extract
  vars:                              # Variables passed to datatype template
    assignerSystem: String, PID.3.4

identifier_2:
  valueOf: datatype/Identifier_var   # Different identifier mapping
  expressionType: resource
  specs: PID.2
  vars:
    system: String, PID.2.4

name:
  valueOf: datatype/HumanName
  generateList: true
  expressionType: resource
  specs: PID.5

gender:
  type: STRING
  valueOf: PID.8                     # Simple field extraction
  expressionType: HL7Spec            # Direct HL7 field reference

birthDate:
  type: STRING
  valueOf: PID.7
  expressionType: HL7Spec

address:
  valueOf: datatype/Address
  generateList: true
  expressionType: resource
  specs: PID.11

telecom:
  valueOf: datatype/ContactPoint
  generateList: true
  expressionType: resource
  specs: PID.13 | PID.14             # Multiple specs (phone | email)

maritalStatus:
  valueOf: datatype/CodeableConcept
  expressionType: resource
  specs: PID.16
  vars:
    code: String, PID.16
    system: SYSTEM_URL, "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus"

communication:
  valueOf: secondary/Communication   # Nested complex object
  generateList: true
  expressionType: resource
  specs: PID.15

extension:
  valueOf: extension/Patient_Race    # Extensions
  generateList: true
  expressionType: resource
  specs: PID.10
```

**Key Properties Explained**:

1. **`type`**: Output data type
   - `STRING`, `DATE`, `INTEGER`, `BOOLEAN`, `OBJECT`, `ARRAY`
   - Used for type coercion and validation

2. **`valueOf`**: Expression to evaluate
   - JEXL: Java expression (e.g., `"GeneralUtils.generateResourceId()"`)
   - HL7Spec: Direct field reference (e.g., `PID.8`)
   - resource: Template reference (e.g., `datatype/HumanName`)

3. **`expressionType`**: How to interpret `valueOf`
   - `JEXL`: Execute as Java code
   - `HL7Spec`: Extract from HL7 message
   - `resource`: Evaluate another template
   - `reference`: Resolve FHIR reference

4. **`generateList`**: Output as array
   - `true`: Wraps result in array (for repeating fields)
   - `false` or omitted: Single value

5. **`specs`**: HL7 field specification
   - Format: `SEGMENT.FIELD.COMPONENT.SUBCOMPONENT`
   - Examples:
     - `PID.3`: All of Patient ID field
     - `PID.5.1`: Family name component
     - `PID.13 | PID.14`: Multiple fields (OR logic)
   - Used only with `HL7Spec` or `resource` expression types

6. **`vars`**: Variables for sub-templates
   - Passed to datatype templates as context
   - Format: `variableName: Type, Source`
   - Examples:
     - `code: String, PID.16`: Pass PID.16 as "code" variable
     - `system: SYSTEM_URL, "http://..."`: Pass literal as "system"

### Datatype Template Structure

**File**: `hl7/datatype/HumanName.yml`

```yaml
# Corresponds to FHIR HumanName datatype
family:
  type: STRING
  valueOf: $field.1                  # $field refers to specs from parent
  expressionType: HL7Spec

given:
  type: STRING
  valueOf: $field.2                  # Given name
  generateList: true
  expressionType: HL7Spec

given_2:
  type: STRING
  valueOf: $field.3                  # Middle name
  generateList: true
  expressionType: HL7Spec

suffix:
  type: STRING
  valueOf: $field.4
  generateList: true
  expressionType: HL7Spec

prefix:
  type: STRING
  valueOf: $field.5
  generateList: true
  expressionType: HL7Spec

use:
  type: STRING
  valueOf: $field.7                  # Name type code
  expressionType: HL7Spec
```

**Special Variables in Datatype Templates**:

1. **`$field`**: Reference to parent's `specs`
   - If parent says `specs: PID.5`, then `$field.1` means `PID.5.1`
   - Allows reusable templates for different segments

2. **Custom variables**: From parent's `vars`
   ```yaml
   # In parent (Patient.yml):
   vars:
     assignerSystem: String, PID.3.4

   # In datatype (Identifier.yml):
   assigner:
     system:
       valueOf: $assignerSystem      # Uses passed variable
   ```

### Extension Templates

**File**: `hl7/extension/Patient_Race.yml`

```yaml
url:
  type: SYSTEM_URL
  valueOf: "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"
  expressionType: STRING

extension:
  generateList: true
  valueOf: secondary/RaceComponent
  expressionType: resource
  specs: $field
```

Extensions follow the same pattern but produce FHIR extension structures.

---

## 5. Expression Evaluation Engine

The expression system is the core of the template engine. Understanding how expressions are evaluated is critical for creating custom pipelines.

### Expression Interface

**Conceptual Structure**:
```java
public interface Expression {
    Object evaluate(Segment primarySegment,
                   Map<String, Object> contextValues,
                   HL7MessageEngine engine);
}
```

Each expression type implements this interface differently.

### JEXL Expressions (Computed Values)

**Purpose**: Execute Java code for computed fields (IDs, timestamps, transformations).

**Syntax**:
```yaml
id:
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL
```

**Evaluation**:
```java
public Object evaluateJEXL(String expression, Map<String, Object> context) {
    // 1. Create JEXL engine
    JexlEngine jexl = new JexlBuilder()
        .namespaces(createNamespaces())  // Register utility classes
        .create();

    // 2. Parse expression
    JexlExpression jexlExpression = jexl.createExpression(expression);

    // 3. Create context with variables
    JexlContext jexlContext = new MapContext(context);

    // 4. Evaluate
    return jexlExpression.evaluate(jexlContext);
}
```

**Available Utility Classes**:
- `GeneralUtils`: ID generation, string manipulation
- `DateUtil`: Date parsing and formatting
- `ValueExtractor`: Complex field extraction
- Custom functions can be registered

**Common JEXL Examples**:

```yaml
# Generate UUID
id:
  valueOf: "GeneralUtils.generateResourceId()"
  expressionType: JEXL

# Format timestamp
timestamp:
  valueOf: "DateUtil.formatDateTime(MSH.7)"
  expressionType: JEXL

# Conditional logic
status:
  valueOf: "PV1.2 == 'E' ? 'finished' : 'in-progress'"
  expressionType: JEXL

# String concatenation
fullAddress:
  valueOf: "'${PID.11.1}, ${PID.11.3}, ${PID.11.4}'"
  expressionType: JEXL
```

**Extension Point for OMOP**:
```java
// Register OMOP utilities
Map<String, Object> namespaces = new HashMap<>();
namespaces.put("OMOP", new OMOPUtils());
namespaces.put("Vocab", vocabularyService);

// In YAML:
person_id:
  valueOf: "OMOP.generatePersonId()"
  expressionType: JEXL

concept_id:
  valueOf: "Vocab.mapToConceptId(PID.10, 'Race')"
  expressionType: JEXL
```

### HL7Spec Expressions (Direct Field Extraction)

**Purpose**: Extract data directly from HL7 segments.

**Syntax**:
```yaml
gender:
  valueOf: PID.8
  expressionType: HL7Spec
```

**Spec Format**:
```
SEGMENT.FIELD[REPETITION].COMPONENT.SUBCOMPONENT
```

Examples:
- `PID.8`: Patient gender (simple field)
- `PID.3.1`: Patient ID value (component)
- `PID.5[1].1`: Family name of second name repetition
- `OBX.3.1`: Observation identifier code

**Evaluation**:
```java
public Object evaluateHL7Spec(String spec, Segment segment, Message message) {
    // 1. Parse spec
    SpecParser parser = new SpecParser(spec);
    String segmentName = parser.getSegment();      // "PID"
    int field = parser.getField();                 // 8
    int repetition = parser.getRepetition();       // 0 (default)
    int component = parser.getComponent();         // 0 (whole field)
    int subcomponent = parser.getSubcomponent();   // 0

    // 2. Get segment (may be different from primary)
    Segment targetSegment = segment;
    if (!segmentName.equals(segment.getName())) {
        targetSegment = (Segment) message.get(segmentName);
    }

    // 3. Extract field
    Type fieldValue = targetSegment.getField(field, repetition);

    // 4. Navigate to component/subcomponent
    if (component > 0) {
        Composite composite = (Composite) fieldValue;
        fieldValue = composite.getComponent(component - 1);

        if (subcomponent > 0) {
            composite = (Composite) fieldValue;
            fieldValue = composite.getComponent(subcomponent - 1);
        }
    }

    // 5. Extract primitive value
    return Terser.getPrimitive(fieldValue, 1, 1).getValue();
}
```

**Handling Repetitions**:
```yaml
# Extract first repetition
identifier:
  valueOf: PID.3[0].1
  expressionType: HL7Spec

# Extract all repetitions (with generateList)
identifiers:
  valueOf: PID.3
  generateList: true
  expressionType: HL7Spec
```

**Multi-Field Specs** (OR logic):
```yaml
# Try PID.13, fallback to PID.14
phone:
  valueOf: PID.13 | PID.14
  expressionType: HL7Spec
```

**Extension Point for Validation**:
```java
// Track extracted fields for coverage analysis
public class ValidationTracker {
    private Set<String> extractedFields = new HashSet<>();

    public Object evaluateHL7SpecWithTracking(String spec, Segment segment) {
        Object value = evaluateHL7Spec(spec, segment);
        if (value != null && !value.toString().isEmpty()) {
            extractedFields.add(spec);
        }
        return value;
    }

    public Set<String> getUnmappedFields(Segment segment) {
        Set<String> allFields = getAllFieldsInSegment(segment);
        allFields.removeAll(extractedFields);
        return allFields;
    }
}
```

### Resource Expressions (Template References)

**Purpose**: Reuse templates for complex datatypes or nested resources.

**Syntax**:
```yaml
name:
  valueOf: datatype/HumanName
  expressionType: resource
  specs: PID.5
```

**Evaluation**:
```java
public Object evaluateResource(String resourcePath, String spec,
                                Segment segment, Map<String, Object> context,
                                HL7MessageEngine engine) {
    // 1. Extract data using spec
    Object data = evaluateHL7Spec(spec, segment);

    // 2. Handle repetitions
    if (data instanceof List) {
        List results = new ArrayList();
        for (Object item : (List) data) {
            results.add(evaluateResourceSingle(resourcePath, item, context, engine));
        }
        return results;
    } else {
        return evaluateResourceSingle(resourcePath, data, context, engine);
    }
}

private Object evaluateResourceSingle(String resourcePath, Object data,
                                       Map<String, Object> context,
                                       HL7MessageEngine engine) {
    // 3. Load template
    HL7DataBasedResourceModel template =
        engine.getResourceReader().getResourceModel(resourcePath);

    // 4. Create new context with $field variable
    Map<String, Object> newContext = new HashMap<>(context);
    newContext.put("$field", data);

    // 5. Evaluate template
    return template.evaluate(data, newContext, engine);
}
```

**Variable Passing**:
```yaml
# Parent template (Patient.yml)
identifier:
  valueOf: datatype/Identifier_SystemID
  expressionType: resource
  specs: PID.3
  vars:
    assignerSystem: String, PID.3.4
    assignerDisplay: String, PID.3.5

# Child template (Identifier_SystemID.yml)
assigner:
  system:
    valueOf: $assignerSystem           # Uses passed variable
    expressionType: HL7Spec
  display:
    valueOf: $assignerDisplay
    expressionType: HL7Spec
```

**Extension Point for OMOP**:
You can create OMOP-specific templates:

```yaml
# omop/person.yml
person_id:
  valueOf: "OMOP.generatePersonId()"
  expressionType: JEXL

gender_concept_id:
  valueOf: omop/concept/Gender
  expressionType: resource
  specs: PID.8

race_concept_id:
  valueOf: omop/concept/Race
  expressionType: resource
  specs: PID.10

# omop/concept/Gender.yml
concept_id:
  valueOf: "Vocab.mapGenderToConceptId($field)"
  expressionType: JEXL
```

### Reference Expressions (FHIR References)

**Purpose**: Create references between FHIR resources.

**Syntax**:
```yaml
# In Encounter.yml
subject:
  valueOf: $ref:Patient
  expressionType: reference
```

**Evaluation**:
```java
public Reference evaluateReference(String resourceType, HL7MessageEngine engine) {
    // 1. Look up resource in cache
    Resource cachedResource = engine.getCachedResource(resourceType);

    if (cachedResource == null) {
        throw new IllegalStateException("Referenced resource not found: " + resourceType);
    }

    // 2. Create FHIR Reference
    Reference ref = new Reference();
    ref.setReference(resourceType + "/" + cachedResource.getId());

    return ref;
}
```

**Reference Cache**:
```java
public class HL7MessageEngine {
    private Map<String, Resource> resourceCache = new HashMap<>();

    public void cacheResource(String type, String id, Resource resource) {
        resourceCache.put(type, resource);
    }

    public Resource getCachedResource(String type) {
        return resourceCache.get(type);
    }
}
```

**Extension Point for OMOP**:
Instead of FHIR references, track foreign keys:

```java
public class OMOPEngine {
    private Map<String, Long> primaryKeys = new HashMap<>();

    public void registerPrimaryKey(String tableName, Long id) {
        primaryKeys.put(tableName, id);
    }

    public Long getForeignKey(String tableName) {
        return primaryKeys.get(tableName);
    }
}

// In OMOP templates:
// visit_occurrence.yml
person_id:
  valueOf: "$fk:person"
  expressionType: foreignKey
```

---

## 6. Extension Points for Custom Pipelines

Based on the architecture analysis, here are the key extension points for building parallel pipelines (OMOP, IBM UDM, etc.).

### 6.1 Shared Parsing Layer

**Leverage Existing Parser**:
```java
// Your custom converter
public class HL7ToOMOPConverter {
    private HL7HapiParser parser = new HL7HapiParser();

    public Map<String, List<Map<String, Object>>> convert(String hl7Message) {
        // 1. Use same parser
        Message parsedMessage = parser.getHapiMessage(hl7Message);

        // 2. Your custom logic
        return convertToOMOP(parsedMessage);
    }
}
```

**Benefits**:
- No need to reimplement HL7 parsing
- Same HAPI `Message` object as FHIR converter
- Enables side-by-side comparison for validation

### 6.2 Custom Template System

**Create OMOP-Specific Templates**:

**Message Template** (`omop/message/ADT_A01.yml`):
```yaml
tables:
  - tableName: person
    segment: PID
    templatePath: omop/table/person
    repeats: false
    isPrimaryKey: true
    additionalSegments:
      - MSH
      - PD1

  - tableName: visit_occurrence
    segment: PV1
    templatePath: omop/table/visit_occurrence
    repeats: false
    isPrimaryKey: true
    foreignKeys:
      person_id: person
    additionalSegments:
      - PV2
      - MSH

  - tableName: observation
    segment: OBX
    templatePath: omop/table/observation
    repeats: true
    isPrimaryKey: true
    foreignKeys:
      person_id: person
      visit_occurrence_id: visit_occurrence
```

**Table Template** (`omop/table/person.yml`):
```yaml
tableName: person

person_id:
  type: INTEGER
  valueOf: "OMOP.generatePersonId()"
  expressionType: JEXL
  primaryKey: true

gender_concept_id:
  type: INTEGER
  valueOf: "Vocab.mapToConceptId(PID.8, 'Gender')"
  expressionType: JEXL
  required: true

year_of_birth:
  type: INTEGER
  valueOf: "DateUtil.extractYear(PID.7)"
  expressionType: JEXL

month_of_birth:
  type: INTEGER
  valueOf: "DateUtil.extractMonth(PID.7)"
  expressionType: JEXL

day_of_birth:
  type: INTEGER
  valueOf: "DateUtil.extractDay(PID.7)"
  expressionType: JEXL

race_concept_id:
  type: INTEGER
  valueOf: "Vocab.mapToConceptId(PID.10, 'Race')"
  expressionType: JEXL
  required: true

ethnicity_concept_id:
  type: INTEGER
  valueOf: "Vocab.mapToConceptId(PID.22, 'Ethnicity')"
  expressionType: JEXL
  required: true

person_source_value:
  type: STRING
  valueOf: PID.3.1
  expressionType: HL7Spec
```

### 6.3 Custom Engine

**OMOP Engine**:
```java
public class OMOPEngine {
    private Map<String, Long> primaryKeys = new HashMap<>();
    private Map<String, List<Map<String, Object>>> tables = new HashMap<>();
    private OMOPVocabularyService vocabularyService;

    public void generateRow(OMOPTableTemplate template,
                           Segment primarySegment,
                           Map<String, Object> contextValues) {
        // 1. Load table model
        OMOPTableModel tableModel = template.getTableModel();

        // 2. Evaluate expressions to generate row
        Map<String, Object> row = tableModel.evaluate(
            primarySegment, contextValues, this);

        // 3. Add to table collection
        String tableName = tableModel.getTableName();
        tables.computeIfAbsent(tableName, k -> new ArrayList<>()).add(row);

        // 4. Track primary key
        if (template.isPrimaryKey()) {
            Long pk = (Long) row.get(tableModel.getPrimaryKeyColumn());
            primaryKeys.put(tableName, pk);
        }
    }

    public Map<String, List<Map<String, Object>>> getTables() {
        return tables;
    }

    public Long getForeignKey(String tableName) {
        return primaryKeys.get(tableName);
    }
}
```

### 6.4 Vocabulary Service

**OMOP Vocabulary Integration**:
```java
public class OMOPVocabularyService {
    private DataSource dataSource;
    private Map<String, Map<String, Integer>> codeToConceptCache;

    public Integer mapToConceptId(String code, String domain) {
        // 1. Check cache
        if (codeToConceptCache.containsKey(domain)) {
            Integer conceptId = codeToConceptCache.get(domain).get(code);
            if (conceptId != null) return conceptId;
        }

        // 2. Query OMOP vocabulary
        String sql = """
            SELECT c.concept_id
            FROM concept c
            JOIN concept_relationship cr ON c.concept_id = cr.concept_id_1
            WHERE cr.concept_id_2 IN (
                SELECT concept_id FROM concept
                WHERE domain_id = ? AND concept_code = ?
            )
            AND cr.relationship_id = 'Maps to'
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, domain);
            stmt.setString(2, code);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                int conceptId = rs.getInt("concept_id");
                cacheMapping(domain, code, conceptId);
                return conceptId;
            }
        }

        // 3. Return 0 (No matching concept) if not found
        return 0;
    }
}
```

### 6.5 Parallel Pipeline Architecture

**Unified Converter**:
```java
public class UnifiedHL7Converter {
    private HL7ToFHIRConverter fhirConverter;
    private HL7ToOMOPConverter omopConverter;
    private HL7HapiParser parser;

    public ConversionResult convert(String hl7Message) {
        // 1. Parse once
        Message parsedMessage = parser.getHapiMessage(hl7Message);

        // 2. Convert to FHIR (for transactional storage)
        Bundle fhirBundle = fhirConverter.convertToBundle(parsedMessage);

        // 3. Convert to OMOP (for analytics)
        Map<String, List<Map<String, Object>>> omopTables =
            omopConverter.convertToOMOP(parsedMessage);

        // 4. Store raw message (for 100% fidelity)
        String rawMessage = hl7Message;

        // 5. Return all formats
        return new ConversionResult(fhirBundle, omopTables, rawMessage);
    }
}
```

---

## 7. Data Fidelity and Loss Prevention

One of your key requirements is maintaining 100% data fidelity, including Z segments. Here's how to approach this:

### 7.1 Understanding Data Loss

**Where Data Loss Occurs**:
1. **Unmapped segments**: Z segments, custom segments
2. **Unmapped fields**: Fields not in YAML templates
3. **Repeating fields**: When only first repetition is used
4. **Subcomponents**: When mapping stops at component level
5. **Code mappings**: When HL7 codes don't map to FHIR/OMOP

### 7.2 Tracking Unmapped Data

**Field Coverage Tracker**:
```java
public class FieldCoverageTracker {
    private Map<String, Set<String>> extractedFields = new HashMap<>();
    private Map<String, Set<String>> availableFields = new HashMap<>();

    public void trackExtraction(String segmentName, String fieldSpec) {
        extractedFields.computeIfAbsent(segmentName, k -> new HashSet<>())
                      .add(fieldSpec);
    }

    public void analyzeSegment(Segment segment) {
        String segmentName = segment.getName();
        Set<String> available = new HashSet<>();

        for (int i = 1; i <= segment.numFields(); i++) {
            try {
                Type[] fields = segment.getField(i);
                for (int rep = 0; rep < fields.length; rep++) {
                    if (!isEmpty(fields[rep])) {
                        available.add(segmentName + "." + i + "[" + rep + "]");
                    }
                }
            } catch (HL7Exception e) {
                // Skip
            }
        }

        availableFields.put(segmentName, available);
    }

    public Map<String, Set<String>> getUnmappedFields() {
        Map<String, Set<String>> unmapped = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : availableFields.entrySet()) {
            String segmentName = entry.getKey();
            Set<String> available = entry.getValue();
            Set<String> extracted = extractedFields.getOrDefault(segmentName, new HashSet<>());

            Set<String> missing = new HashSet<>(available);
            missing.removeAll(extracted);

            if (!missing.isEmpty()) {
                unmapped.put(segmentName, missing);
            }
        }

        return unmapped;
    }
}
```

### 7.3 Z Segment Handling

**Option 1: Generic Z Segment Mapper**
```yaml
# In ADT_A01.yml
resources:
  - resourceName: Extension
    segment: ZZZ*                      # Wildcard for any Z segment
    resourcePath: resource/ZSegment
    repeats: true
    isReferenced: false
```

**Z Segment Template** (`resource/ZSegment.yml`):
```yaml
resourceType: Extension

url:
  type: STRING
  valueOf: "'http://your-organization.com/fhir/z-segments/' + $segmentName"
  expressionType: JEXL

extension:
  generateList: true
  valueOf: secondary/ZField
  expressionType: resource
  specs: $segment.*                    # All fields
```

**Option 2: Raw Storage**
```java
public class RawMessageStore {
    public void storeRawMessage(String messageId, String hl7Message) {
        // Store complete HL7 message
        String sql = "INSERT INTO hl7_raw_messages (message_id, content, received_at) VALUES (?, ?, ?)";
        // Execute...
    }

    public void storeSegmentInventory(String messageId, Message parsedMessage) {
        // Store segment inventory for querying
        String sql = "INSERT INTO hl7_segments (message_id, segment_name, segment_content, ordinal) VALUES (?, ?, ?, ?)";

        int ordinal = 0;
        for (String structureName : parsedMessage.getNames()) {
            Structure structure = parsedMessage.get(structureName);
            if (structure instanceof Segment) {
                Segment segment = (Segment) structure;
                // Store each segment
                ordinal++;
            }
        }
    }
}
```

### 7.4 Canonical Relational Model

**Three-Tier Storage Strategy**:

**Tier 1: Raw Transactional (100% Fidelity)**
```sql
CREATE TABLE hl7_raw_messages (
    message_id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    message_type VARCHAR(50),
    received_at TIMESTAMP,
    source_system VARCHAR(100)
);

CREATE TABLE hl7_segments (
    id BIGSERIAL PRIMARY KEY,
    message_id UUID REFERENCES hl7_raw_messages(message_id),
    segment_name VARCHAR(3),
    segment_content TEXT,
    ordinal INT
);

CREATE INDEX idx_segments_message ON hl7_segments(message_id);
CREATE INDEX idx_segments_name ON hl7_segments(segment_name);
```

**Tier 2: FHIR Normalized (Clinical Interoperability)**
```sql
-- Store as FHIR JSON
CREATE TABLE fhir_resources (
    id UUID PRIMARY KEY,
    resource_type VARCHAR(50),
    content JSONB,
    source_message_id UUID REFERENCES hl7_raw_messages(message_id)
);

CREATE INDEX idx_fhir_type ON fhir_resources(resource_type);
CREATE INDEX idx_fhir_content ON fhir_resources USING GIN(content);
```

**Tier 3: OMOP Denormalized (Analytics)**
```sql
-- Standard OMOP CDM tables
CREATE TABLE person (...);
CREATE TABLE visit_occurrence (...);
CREATE TABLE observation (...);
-- etc.

-- Link back to source
CREATE TABLE omop_source_mapping (
    omop_table VARCHAR(50),
    omop_id BIGINT,
    source_message_id UUID REFERENCES hl7_raw_messages(message_id),
    source_segment VARCHAR(3),
    PRIMARY KEY (omop_table, omop_id)
);
```

**Conversion with Full Lineage**:
```java
public class UnifiedConverter {
    public ConversionResult convertWithLineage(String hl7Message) {
        String messageId = UUID.randomUUID().toString();

        // 1. Store raw message
        rawMessageStore.store(messageId, hl7Message);

        // 2. Parse
        Message parsedMessage = parser.parse(hl7Message);
        rawMessageStore.storeSegmentInventory(messageId, parsedMessage);

        // 3. Convert to FHIR
        Bundle fhirBundle = fhirConverter.convert(parsedMessage);
        for (BundleEntryComponent entry : fhirBundle.getEntry()) {
            Resource resource = entry.getResource();
            fhirStore.store(resource, messageId);  // Link to source
        }

        // 4. Convert to OMOP
        Map<String, List<Map<String, Object>>> omopTables =
            omopConverter.convert(parsedMessage);
        for (Map.Entry<String, List<Map<String, Object>>> table : omopTables.entrySet()) {
            for (Map<String, Object> row : table.getValue()) {
                Long omopId = omopStore.insert(table.getKey(), row);
                omopStore.storeLineage(table.getKey(), omopId, messageId);
            }
        }

        // 5. Analyze coverage
        FieldCoverageReport report = coverageTracker.analyze(parsedMessage);
        coverageStore.store(messageId, report);

        return new ConversionResult(messageId, fhirBundle, omopTables, report);
    }
}
```

---

## 8. Validation Strategies

For validating that HL7 has been properly converted, you need multi-level validation:

### 8.1 Level 1: Field Coverage Validation

**Ensure all non-empty fields are mapped**:
```java
public class FieldCoverageValidator {
    public ValidationResult validate(Message hl7Message, Bundle fhirBundle) {
        ValidationResult result = new ValidationResult();

        // 1. Inventory all non-empty fields in HL7
        Map<String, Set<String>> hl7Fields = inventoryFields(hl7Message);

        // 2. Inventory all fields extracted to FHIR
        Map<String, Set<String>> fhirFields = inventoryFHIRSources(fhirBundle);

        // 3. Compare
        for (Map.Entry<String, Set<String>> entry : hl7Fields.entrySet()) {
            String segment = entry.getKey();
            Set<String> available = entry.getValue();
            Set<String> mapped = fhirFields.getOrDefault(segment, new HashSet<>());

            Set<String> unmapped = new HashSet<>(available);
            unmapped.removeAll(mapped);

            if (!unmapped.isEmpty()) {
                result.addWarning(segment, "Unmapped fields: " + unmapped);
            }
        }

        return result;
    }
}
```

### 8.2 Level 2: Semantic Validation

**Verify correctness of mapped values**:
```java
public class SemanticValidator {
    public ValidationResult validate(Message hl7Message, Bundle fhirBundle) {
        ValidationResult result = new ValidationResult();

        // Extract Patient from FHIR Bundle
        Patient patient = findResource(fhirBundle, Patient.class);

        // Extract PID from HL7
        PID pid = (PID) hl7Message.get("PID");

        // Validate mappings
        validateIdentifier(pid, patient, result);
        validateName(pid, patient, result);
        validateGender(pid, patient, result);
        validateBirthDate(pid, patient, result);

        return result;
    }

    private void validateIdentifier(PID pid, Patient patient, ValidationResult result) {
        // Get HL7 value
        String hl7Id = pid.getPatientIdentifierList(0).getIDNumber().getValue();

        // Get FHIR value
        String fhirId = patient.getIdentifierFirstRep().getValue();

        // Compare
        if (!hl7Id.equals(fhirId)) {
            result.addError("Patient.identifier",
                "Mismatch: HL7=" + hl7Id + ", FHIR=" + fhirId);
        }
    }
}
```

### 8.3 Level 3: Round-Trip Validation

**Convert back to HL7 and compare**:
```java
public class RoundTripValidator {
    private HL7ToFHIRConverter toFhir;
    private FHIRToHL7Converter toHl7;  // You'd need to build this

    public ValidationResult validate(String originalHl7) {
        // 1. Convert to FHIR
        Bundle fhirBundle = toFhir.convertToBundle(originalHl7);

        // 2. Convert back to HL7
        String reconvertedHl7 = toHl7.convert(fhirBundle);

        // 3. Parse both
        Message original = parser.parse(originalHl7);
        Message reconverted = parser.parse(reconvertedHl7);

        // 4. Compare segment by segment
        return compareMessages(original, reconverted);
    }
}
```

---

## 9. Parallel Pipeline Architecture

Here's a complete architecture for your use case:

### 9.1 Unified Processing Pipeline

```
┌──────────────────────┐
│   HL7v2 Message      │
│   (Raw String)       │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  Store Raw Message   │ ◄─── Tier 1: Raw Storage (100% Fidelity)
│  + Segment Inventory │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│   Parse with HAPI    │
│   (Message object)   │
└──────────┬───────────┘
           │
           ├────────────────────────────────┐
           │                                │
           ▼                                ▼
┌──────────────────────┐       ┌──────────────────────┐
│ FHIR Converter       │       │  OMOP Converter      │
│ (Interoperability)   │       │  (Analytics)         │
└──────────┬───────────┘       └──────────┬───────────┘
           │                                │
           ▼                                ▼
┌──────────────────────┐       ┌──────────────────────┐
│  FHIR Bundle         │       │  OMOP Tables         │
│  (JSONB storage)     │       │  (Relational)        │
└──────────┬───────────┘       └──────────┬───────────┘
           │                                │
           └────────────┬───────────────────┘
                        │
                        ▼
           ┌──────────────────────┐
           │  Validation Engine   │
           │  - Coverage          │
           │  - Semantic          │
           │  - Cross-format      │
           └──────────────────────┘
```

### 9.2 Implementation

**Main Controller**:
```java
@RestController
@RequestMapping("/api/hl7")
public class HL7ProcessingController {
    private UnifiedHL7Processor processor;

    @PostMapping("/process")
    public ResponseEntity<ProcessingResult> processMessage(@RequestBody String hl7Message) {
        ProcessingResult result = processor.process(hl7Message);
        return ResponseEntity.ok(result);
    }
}

public class UnifiedHL7Processor {
    private RawMessageStore rawStore;
    private HL7HapiParser parser;
    private HL7ToFHIRConverter fhirConverter;
    private HL7ToOMOPConverter omopConverter;
    private ValidationEngine validator;

    @Transactional
    public ProcessingResult process(String hl7Message) {
        String messageId = UUID.randomUUID().toString();

        try {
            // 1. Store raw
            rawStore.store(messageId, hl7Message);

            // 2. Parse
            Message parsed = parser.parse(hl7Message);
            rawStore.storeSegmentInventory(messageId, parsed);

            // 3. Convert to FHIR
            Bundle fhirBundle = fhirConverter.convertToBundle(parsed);

            // 4. Convert to OMOP
            Map<String, List<Map<String, Object>>> omopTables =
                omopConverter.convert(parsed);

            // 5. Validate
            ValidationReport report = validator.validate(parsed, fhirBundle, omopTables);

            // 6. Store if valid
            if (report.isValid()) {
                fhirStore.store(fhirBundle, messageId);
                omopStore.store(omopTables, messageId);
            }

            return new ProcessingResult(messageId, fhirBundle, omopTables, report);

        } catch (Exception e) {
            logger.error("Processing failed for message {}", messageId, e);
            throw new ProcessingException("Failed to process message", e);
        }
    }
}
```

---

## 10. Conclusion and Next Steps

This document has provided a comprehensive technical deep-dive into the hl7v2-fhir-converter architecture.

**Key Takeaways**:
1. **Template-driven**: All mappings are in YAML, making it extensible
2. **HAPI-based**: Leverages robust HL7 parsing library
3. **Expression engine**: Flexible evaluation system for complex mappings
4. **Reusable parser**: Same parser can feed multiple pipelines

**For Your Use Case** (Canonical Model + OMOP + Data Fidelity):
1. **Reuse**: Parser and HL7DataExtractor
2. **Parallel**: Create HL7ToOMOPConverter with similar structure
3. **Storage**: Three-tier (raw + FHIR + OMOP)
4. **Validation**: Multi-level (coverage + semantic + cross-format)
5. **Lineage**: Track source message for every output row/resource

**Next Steps**:
1. Create OMOP template system (message/table/concept)
2. Implement OMOPEngine for relational output
3. Build VocabularyService for concept mapping
4. Implement ValidationEngine for coverage tracking
5. Set up three-tier storage schema
6. Create unified processor that feeds both pipelines

This architecture gives you:
- **100% data fidelity** (raw storage)
- **Clinical interoperability** (FHIR)
- **Analytics capability** (OMOP)
- **Validation framework** (coverage tracking)
- **Extensibility** (template-driven)

Let me know if you need detailed implementation guidance for any specific component!

