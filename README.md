# evolve-hub

XSLT Library to detect and tag headlines, lists, tables and figures

The combined `xsl/evolve-hub.xsl` entry imports the list bodies in
`lists-by-indent/xsl/lists-core.xsl` and `lists-by-role/xsl/lists-core.xsl`.
The original `lists-main.xsl` entry points retain their standalone dependency
order. Applications importing a standalone list module still supply shared
evolve functions and variables; these modules are not independent applications.

The composition regressions run XSLT 2.0 stylesheets with Saxon HE 12.10 on
Java 17. The Maven wrapper builds only the Kotlin/JVM test harness; it does not
rewrite stylesheets or package a replacement XSLT runtime. The first run fetches
Maven dependencies and a checksum-verified public `transpect/xslt-util` archive.
Subsequent runs reuse the verified archive. No private documents or fonts are
needed.

Run the same non-writing checks as GitHub Actions:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

For focused execution and Kotlin formatting:

```sh
./mvnw -Dtest=CompositionTest#combinedTabContract test
./mvnw ktlint:format
```

Commands, diagnostics and XML outputs are retained under
`target/composition-results/`; CI uploads these alongside JUnit reports.
`-Dcomposition.source=/absolute/source/tree` executes the same callers against
another unmodified source snapshot. `-Dcomposition.buildDirectory=/absolute/output`
keeps all build and proof files outside the source tree.

See the [composition regression oracles](tests/README.md) for fixture contracts,
compatibility expectations, diagnostic requirements and coverage limits.
