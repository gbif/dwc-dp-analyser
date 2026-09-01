# Changelog

All notable changes to `dwc-dp-analyser` are documented in this file.

## [0.0.12]

### Changed
- Added `version` for schema version to analysis execution report

## [0.0.11]

### Fixed
- Ensure registration of `jdbc:duckdb` which in rare cases, eg. in tomcat would not allow a session without explicit registration

### Changed
- Updated Duckdb from 0.10.0 -> 1.5.5.0
- Added `AnalysisExecution` as root analysis report, including `AnalysisMetadata` which includes UTC for start/stop, validity of the analysis and list of `AnalysisFeature` included in the report

## [0.0.10] - 2026-08-10

### Fixed
- Primary key validation now also flags `NULL` primary key values, not just duplicates.
- CLI text output (`--output-format text`, the default) now reports whether the dataset is valid — previously this was only visible via exit code — and now includes primary key violations in the report, which were silently omitted before.

## [0.0.9] - 2026-07-30

### Fixed
- CSV loading no longer forces a full-file type sniff (`sample_size=-1`) on every resource, which was doubling I/O regardless of whether it was needed. Resources now load with sampled auto-detection first, and only retry with `all_varchar=true` on a conversion error — since type conformance is independently re-validated downstream via `TRY_CAST` anyway, the upfront sniff was never load-bearing.
- Data type validation was running at O(n²) across all resources per resource; fixed to scale linearly.
- Descriptor errors are now printed when analysis cannot proceed, instead of failing silently.
- Added `http`→`https` aliasing for profile URIs.
- Added alias for the `1.0` profile to `1.0_DEV` until `1.0` is officially released; fixed networknt validator keyword warnings and JSON Schema version selection (draft-4 vs 2020-12) based on the profile's declared schema version.
- Various release script and macOS tag-format fixes.

### Changed
- `release.sh` now also updates the Homebrew tap repo as part of a release.

## [0.0.8] - 2026-07-14 — First official release

This is the first release distributed as a pre-packaged install rather than a bare jar.

### Added
- Packaged distribution: `.deb`/`.rpm` (via nfpm) for Linux with a bash wrapper script installed to `/usr/bin`, and a `.zip` portable distribution for Windows with a `.bat` wrapper.
- Multi-schema/profile support — validation can now target a specific DwC-DP profile version instead of a single hardcoded schema (`DwcDpSchemaVersion`, `DwcDpProfileRegistry`, `DwcDpProfileSchemaLoader`).

### Changed
- Decoupled core model and validators from `java.nio.Path`, introducing a `DataPackageSource` abstraction so packages can be read from local file, HDFS, S3, HTTP, etc. without touching validation logic.
- Extracted descriptor parsing (`JacksonDataPackageParser`) and new I/O abstractions (`DataPackageSource`, `FileSystemDataPackageSource`, `ResourceLocationResolver`) into a new shared `dwc-dp-common` module.
- Descriptor model expanded with `Contributor`, `License`, `PackageSource`, `SchemaDescriptor`, and richer `FieldConstraints`.
- Applied `spotless` formatting across the codebase and added it as a required CI check.

## [0.0.6] - 2026-06-01

### Added
- CLI report mode (`--report-mode`) to select `VERIFY` (validation only), `STATS` (statistics only), or both.
- ASCII table output for statistics in text mode.
- Better exception detail for failing SQL queries during analysis.

### Fixed
- Package mismatch in the CLI runner manifest that broke the `main` method entry point.
- Time conversion issue in the row-count SQL.

## [0.0.5] - 2026-05-26
- Minor logging adjustments for the CLI.

## [0.0.3] - 2026-05-06

### Added
- Multi-layer validation pipeline ahead of DuckDB data analysis:
    - **Layer 0 (Frictionless):** structural checks on `datapackage.json` — file existence, valid JSON, resource paths, FK references, field types.
    - **Layer 1 (DwC-DP JSON Schema):** descriptor validation against the bundled DwC-DP profile via networknt, reporting enum/required/URI-format violations with JSON Pointer locations.
    - **Layer 2 (DwC-DP table schema):** cross-validation of reserved resource names against canonical table schemas, required fields, type matches, and FK declarations.
    - **Layer 3 (EML):** well-formedness and EML 2.2.0 XSD conformance for optional `eml.xml`.
- Structured `ValidationIssue` reporting with severity, violation type, JSON Pointer location, and contextual detail (keyword, evaluation path, actual value, line/column). Severity defaults centralized in `DefaultSeverities` and overridable per deployment.
- `dwc-dp-descriptor` extracted into its own module so descriptors can be used without pulling in DuckDB and validator dependencies.
- Dialect support for CSV/TSV resources (including per-resource dialect overrides), with dialect correctly omitted for Parquet resources.
- Primary key, foreign key, and missing-value validation, plus column statistics.

### Fixed
- Deserialization issues on `ResourceAnalysisResult` caused by using Java records with extra methods.

## [0.0.2] - 2026-05-04

### Added
- Split into multi-module Maven layout: `dwc-dp-analyser-api`, `dwc-dp-analyser-lib`, `dwc-dp-analyser-cli`.
- Jenkins CI/CD pipeline and snapshot deploy.
- Support for partitioned resources.
- Performance: use a DuckDB temp table instead of a view for CSV/TSV loading; added run-monitoring tooling and metrics exploration scripts.

## [0.0.1] - April 2026 — Initial implementation
- Initial DuckDB-based data package validator: FK, PK, data type, and EML checks.
- First CLI (`ValidationCli`) and Jackson-based descriptor parsing.
- Basic statistics on data package fields.
