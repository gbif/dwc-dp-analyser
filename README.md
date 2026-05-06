# Darwin Core - Datapackage Analyser

A multi-layer Java library for validating and analysing [Darwin Core Data Packages (DwC-DP)](https://rs.tdwg.org/dwc-dp/),
built on [Frictionless Data Package v1](https://specs.frictionlessdata.io/) and [DuckDB](https://duckdb.org/).

## What it does

Validation and analysis runs in multiple layers:

### Layer 0 — Frictionless structural validation
Validates `datapackage.json` against the Frictionless Data Package v1 spec:
- File exists and is valid JSON
- `resources` array is non-empty
- Each resource has a `name` and its declared `path` exists on disk
- Foreign key `reference.resource` values resolve to declared resource names
- Field `type` values are from the Frictionless v1 vocabulary

### Layer 1 — DwC-DP JSON Schema profile validation
Validates the descriptor against the bundled `dwc-dp-profile.json` schema via [networknt json-schema-validator](https://github.com/networknt/json-schema-validator):
- Top-level `profile` is present and a valid URI
- Each DwC-DP named resource declares `profile: tabular-data-resource`
- Each field declares `dcterms:isVersionOf` as a valid URI

### Layer 2 — DwC-DP table schema cross-validation
For each resource whose `name` matches a reserved DwC-DP table name, validates against the bundled canonical table schemas:
- All `constraints.required=true` fields are declared
- Declared field types match the canonical type
- Required foreign keys are declared

### Layer 3 — EML metadata validation
Validates the optional `eml.xml` file if present alongside `datapackage.json`:
- Well-formed XML
- Required `<title>` and `<creator>` elements present
- Conformance with the bundled EML 2.2.0 XSD schema

### Layer 4 — Data analysis (DuckDB)
Runs directly over data files (CSV, TSV, Parquet) without loading data into JVM memory:
- **Foreign key validation** — `NOT EXISTS` checks for each `schema.foreignKeys` rule
- **Primary key uniqueness** — duplicate detection across declared primary key fields
- **Data type validation** — `TRY_CAST` checks for each typed field (`integer`, `number`, `boolean`, `date`, `datetime`, `time`, `year`, `object`, `array`)
- **Column statistics** — populated value counts and distinct value counts per field

All violation results include sample rows/values and structured JSON detail for machine consumption.

## Module structure

| Module | Responsibility                                                       |
|---|----------------------------------------------------------------------|
| `dwc-dp-analyser-api` | Result types, feature flags, analysis interfaces                     |
| `dwc-dp-analyser-lib` | Orchestration & Layer 4 - DuckDB-backed data analysis implementation |
| `dwc-dp-validator-api` | `ValidationIssue`, `DescriptorViolationType`, severity model         |
| `dwc-dp-validator-frictionless` | Layer 0 — Frictionless structural validation                         |
| `dwc-dp-validator-dwcdp` | Layers 1 & 2 — DwC-DP JSON Schema and table schema validation        |
| `dwc-dp-validator-eml` | Layer 3 — EML metadata validation                                    |
| `dwc-dp-analyser-cli` | CLI runner                                                           |

## Key classes

| Class | Responsibility                                                  |
|---|-----------------------------------------------------------------|
| `DefaultDataPackageAnalysisOrchestrator` | Sequences all validation and analysis layers                    |
| `FrictionlessDescriptorValidator` | Layer 0 structural checks                                       |
| `DwcDpDescriptorValidator` | Layers 1 & 2 DwC-DP checks                                      |
| `DwcDpProfileValidator` | JSON Schema profile validation via networknt                    |
| `DwcDpTableSchemaValidator` | Canonical table schema cross-validation                         |
| `EmlValidator` | Layer 3 EML well-formedness, required elements, XSD             |
| `JacksonDataPackageParser` | Parses and normalises `datapackage.json`                        |
| `DuckDbDataPackageAnalyser` | Layer 4 DuckDB-backed data analysis                             |
| `DuckDbResourceLoader` | Binds data files as DuckDB temp tables                          |
| `DuckDbDataTypeValidator` | Column type checks via `TRY_CAST`                               |
| `ValidationIssue` | Single structured issue with severity, location, and JSON detail |
| `ValidationCli` | CLI entry point                                                 |

## Validation issues

Every `ValidationIssue` carries:

| Field | Description |
|---|---|
| `severity` | `ERROR`, `WARNING`, or `INFO` |
| `violationType` | Machine-readable `DescriptorViolationType` enum entry |
| `message` | Human-readable explanation |
| `location` | JSON Pointer into the document, e.g. `/resources/0/schema/fields/1/type` |
| `detail` | Structured JSON with context, e.g. `{"keyword":"enum","evaluationPath":"...","actualValue":"object"}` |

Severity defaults are defined in `DefaultSeverities` and can be overridden per deployment
by passing a `Map<DescriptorViolationType, ValidationIssue.Severity>` to any validator constructor.

## Quick start

```bash
mvn test

# Text output (default)
mvn -q exec:java \
  -Dexec.mainClass=org.gbif.dp.cli.ValidationCli \
  -Dexec.args="/absolute/path/to/datapackage.json"

# JSON output
mvn -q exec:java \
  -Dexec.mainClass=org.gbif.dp.cli.ValidationCli \
  -Dexec.args="/absolute/path/to/datapackage.json --output-format JSON"
```

The CLI exits with:

| Code | Meaning |
|---|---|
| `0` | All checks passed |
| `1` | Program error (bad arguments etc.) |
| `2` | Validation or data violations found |

## Configuration

DuckDB resource usage can be tuned via CLI flags or environment variables:

| Flag | Env var | Default | Description |
|---|---|---|---|
| `--duckdb-url` | `DUCKDB_URL` | `jdbc:duckdb:` | JDBC connection URL |
| `--duckdb-memory` | `DUCKDB_MEMORY_LIMIT` | `1500MB` | Memory limit |
| `--duckdb-threads` | `DUCKDB_THREADS` | `2` | Thread count |
| `--duckdb-temp-dir` | `DUCKDB_TEMP_DIR` | `./tmp` | Temp directory |
| `--duckdb-max-temp` | `DUCKDB_MAX_TEMP_SIZE` | `20GB` | Max temp size |

## Notes for large datasets

- Data analysis runs entirely inside DuckDB with file-backed scans — no data is loaded into JVM memory.
- Only small violation samples are materialised in Java (default: 20 rows, configurable via `ValidationOptions`).
- Parquet resources are supported alongside CSV and TSV.
- CSV dialect (delimiter, quote character, escape character, null sequence) is read from the `dialect` descriptor field, with automatic fallback to tab-separated for `.tsv` extensions.
