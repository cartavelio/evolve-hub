import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Runs real Saxon CLI callers; expectations describe the public tab/list/table contracts. */
class CompositionTest {
    private val root = Path.of("").toAbsolutePath()
    private val build = Path.of(System.getProperty("composition.outputDirectory", root.resolve("target").toString()))
    private val source = Path.of(System.getProperty("composition.source", root.toString()))
    private val results = build.resolve("composition-results").also { Files.createDirectories(it) }
    private val fixtures = root.resolve("tests/fixtures")

    private fun dependencies(): Path {
        val commit = "8cc588959ce03dd734228c3c4d0b3ea2c8ecb6b8"
        val dir = build.resolve("dependencies")
        val archive = dir.resolve("xslt-util.zip")
        Files.createDirectories(dir)
        if (!Files.exists(archive)) {
            val request =
                HttpRequest
                    .newBuilder(URI("https://codeload.github.com/transpect/xslt-util/zip/$commit"))
                    .timeout(Duration.ofSeconds(120))
                    .build()
            val response =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() == 200) { "xslt-util download: ${response.statusCode()}" }
            Files.write(archive, response.body())
        }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(archive))
                .joinToString("") { "%02x".format(it) }
        check(digest == "44f52bb2ea387a027e2210044a1a0c2d10234edd8e43f1f8bc6a4f71148a3130")
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = dir.resolve(entry.name).normalize()
                check(output.startsWith(dir))
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    Files.newOutputStream(output).use { zip.copyTo(it) }
                }
            }
        }
        return dir.resolve("xslt-util-$commit")
    }

    private fun run(
        name: String,
        stylesheet: Path,
        compileOnly: Boolean = false,
        input: String = "tabs.xml",
        initialMode: String? = "{http://transpect.io/hub}tabs-to-indent",
        inputPath: Path = fixtures.resolve(input),
    ): Pair<Int, Path> {
        val catalog = results.resolve("$name-catalog.xml")
        Files.writeString(
            catalog,
            """
            <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
              <rewriteURI uriStartString="http://transpect.io/evolve-hub/" rewritePrefix="${source.toUri()}/"/>
              <rewriteURI uriStartString="http://transpect.io/xslt-util/" rewritePrefix="${dependencies().toUri()}/"/>
            </catalog>
            """.trimIndent(),
        )
        val output = results.resolve("$name.xml")
        val command =
            mutableListOf(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx512m",
                "-XX:ActiveProcessorCount=3",
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "net.sf.saxon.Transform",
                "-catalog:$catalog",
                "-xsl:$stylesheet",
            )
        if (compileOnly) {
            command += "-nogo"
        } else {
            command +=
                listOf(
                    "-s:$inputPath",
                    "-o:$output",
                )
        }
        if (!compileOnly && initialMode != null) command += "-im:$initialMode"
        Files.writeString(results.resolve("$name-command.txt"), command.joinToString("\n"))
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(results.resolve("$name.log").toFile())
                .start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        check(completed) { "$name exceeded 30 seconds" }
        return process.exitValue() to output
    }

    private fun caller(kind: String): Path {
        val caller = results.resolve("caller-$kind.xsl")
        Files.writeString(
            caller,
            """
            <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
              xmlns:hub="http://transpect.io/hub" xmlns:dbk="http://docbook.org/ns/docbook"
              exclude-result-prefixes="hub dbk">
              <xsl:import href="http://transpect.io/evolve-hub/xsl/evolve-hub.xsl"/>
              <xsl:import href="http://transpect.io/evolve-hub/lists-by-$kind/xsl/lists-main.xsl"/>
              <xsl:template match="dbk:tab" mode="hub:tabs-to-indent" priority="50">
                <xsl:message>composition-test: next-match reached</xsl:message>
                <xsl:next-match>
                  <xsl:with-param name="remove-nodes" select="if (../@xml:id = 'internal') then . else ()" tunnel="yes"/>
                </xsl:next-match>
              </xsl:template>
            </xsl:stylesheet>
            """.trimIndent(),
        )
        return caller
    }

    private fun checkContract(
        name: String,
        stylesheet: Path,
    ): Path {
        val (exit, output) = run(name, stylesheet)
        assertEquals(0, exit, Files.readString(results.resolve("$name.log")))
        assertTrue(Files.readString(results.resolve("$name.log")).contains("composition-test: next-match reached"))
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(output.toFile())
        val elements = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "*")

        fun element(id: String): Element =
            (0 until elements.length)
                .map { elements.item(it) as Element }
                .single { it.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id") == id }

        fun count(
            id: String,
            name: String,
        ) = element(id).getElementsByTagNameNS("http://docbook.org/ns/docbook", name).length
        // One point = 20 twips. These worked values reject duplicate tab processing.
        assertEquals("400", element("numbered").getAttribute("margin-left"))
        assertEquals("-400", element("numbered").getAttribute("text-indent"))
        assertEquals(1, count("numbered", "phrase"))
        assertEquals(
            "hub:identifier",
            (element("numbered").getElementsByTagNameNS("http://docbook.org/ns/docbook", "phrase").item(0) as Element).getAttribute("role"),
        )
        assertEquals(1, count("nested", "phrase"))
        assertEquals("360", element("styled").getAttribute("margin-left"))
        assertEquals("-180", element("styled").getAttribute("text-indent"))
        assertEquals(1, count("leading", "tab"))
        assertEquals(1, count("trailing", "tab"))
        // The caller tunnels removal of this specific tab through next-match.
        // A missing standalone tabs dependency would leave it in the output.
        assertEquals(0, count("internal", "tab"))
        assertEquals(1, count("equation", "tab"))
        assertEquals(0, count("equation", "phrase"))
        assertEquals("240", element("float").getAttribute("margin-left"))
        assertTrue(element("scope").textContent.contains("Note item"))
        return output
    }

    @Test
    fun combinedTabContract() {
        checkContract("combined", fixtures.resolve("caller-combined.xsl"))
        assertFalse(Files.readString(results.resolve("combined.log")).contains("SXWN9019"), "Combined graph duplicates a module")
    }

    @Test
    fun combinedMatchesFrozenBytes() {
        val (exit, output) = run("frozen", fixtures.resolve("caller-frozen.xsl"), input = "frozen-input.xml")
        assertEquals(0, exit, Files.readString(results.resolve("frozen.log")))
        assertArrayEquals(Files.readAllBytes(fixtures.resolve("expected-frozen.xml")), Files.readAllBytes(output))
    }

    @Test
    fun standaloneCallersKeepDependenciesAndNextMatch() {
        for (kind in listOf("indent", "role")) {
            val output = checkContract("standalone-$kind", caller(kind))
            assertArrayEquals(Files.readAllBytes(fixtures.resolve("expected-$kind.xml")), Files.readAllBytes(output))
        }
    }

    @Test
    fun rawModulesStillRequireCallerContext() {
        for (kind in listOf("indent", "role")) {
            val (exit, _) = run("raw-$kind", source.resolve("lists-by-$kind/xsl/lists-main.xsl"), true)
            assertFalse(exit == 0, "Raw $kind unexpectedly became an independent application")
            val log = Files.readString(results.resolve("raw-$kind.log"))
            assertTrue(log.contains("XPST0008") || log.contains("XPST0017"), log)
        }
    }

    private fun tableResult(
        name: String,
        fixture: String,
    ): org.w3c.dom.Document {
        val (exit, output) = run(name, fixtures.resolve("caller-table.xsl"), input = fixture, initialMode = null)
        assertEquals(0, exit, Files.readString(results.resolve("$name.log")))
        return DocumentBuilderFactory
            .newInstance()
            .also { it.isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(output.toFile())
    }

    @Test
    fun tableSpanWithoutRemoval() {
        val document = tableResult("table-span", "table-span-without-removal.xml")
        val span = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "spanspec")
        assertEquals(1, span.length)
        val element = span.item(0) as Element
        assertEquals("wide", element.getAttribute("spanname"))
        assertEquals("c1", element.getAttribute("namest"))
        assertEquals("c2", element.getAttribute("nameend"))
        val entries = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "entry")
        assertEquals(3, entries.length)
        assertEquals("Span ι", entries.item(0).textContent)
        assertEquals("c1", (entries.item(0) as Element).getAttribute("namest"))
        assertEquals("c2", (entries.item(0) as Element).getAttribute("nameend"))
    }

    @Test
    fun tinyPointColumn() {
        val document = tableResult("tiny-point", "tiny-pt-column.xml")
        val group = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "tgroup").item(0) as Element
        assertEquals("2", group.getAttribute("cols"))
        val columns = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "colspec")
        assertEquals(2, columns.length)
        assertEquals("10.5pt", (columns.item(0) as Element).getAttribute("colwidth"))
        assertEquals("c2", (columns.item(1) as Element).getAttribute("colname"))
        val entries = document.getElementsByTagNameNS("http://docbook.org/ns/docbook", "entry")
        assertEquals(2, entries.length)
        assertEquals("Left κ", entries.item(0).textContent)
        assertEquals("Right λ", entries.item(1).textContent)
        assertEquals("c1", (entries.item(0) as Element).getAttribute("colname"))
        assertEquals("c2", (entries.item(1) as Element).getAttribute("colname"))
    }

    private fun tableModes(namespaces: List<String>) {
        for ((namespaceIndex, namespace) in namespaces.withIndex()) {
            for (small in listOf(false, true)) {
                for (edge in listOf("colname", "namest", "nameend")) {
                    val mode = "rm-redundant-${if (small) "small-" else ""}cols-$edge"
                    val name = "table-modes-$namespaceIndex-$mode-${if (namespace.isEmpty()) "empty" else "qualified"}"
                    val start = if (edge == "namest") "c2" else "c1"
                    val end = if (edge == "nameend") "c2" else "c3"
                    val input = results.resolve("$name-input.xml")
                    Files.writeString(
                        input,
                        """
                        <hub xmlns="$namespace"><informaltable><tgroup cols="3">
                          <colspec colnum="1" colname="c1" colwidth="10pt"/>
                          <colspec colnum="2" colname="c2" colwidth="0.5pt"/>
                          <colspec colnum="3" colname="c3"/>
                          <spanspec spanname="wide" namest="$start" nameend="$end"/>
                          <tbody><row><entry colname="c1">Left</entry><entry colname="c3">Right</entry></row>
                          <row><entry namest="$start" nameend="$end">Span</entry></row></tbody>
                        </tgroup></informaltable></hub>
                        """.trimIndent(),
                    )
                    val caller = results.resolve("$name-caller.xsl")
                    Files.writeString(
                        caller,
                        Files.readString(fixtures.resolve("caller-table.xsl")).replace("rm-redundant-small-cols-colname", mode),
                    )
                    val (exit, output) = run(name, caller, initialMode = null, inputPath = input)
                    assertEquals(0, exit, Files.readString(results.resolve("$name.log")))
                    val document =
                        DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }.newDocumentBuilder().parse(
                            output.toFile(),
                        )
                    val group = document.getElementsByTagNameNS(namespace.ifEmpty { null }, "tgroup").item(0) as Element
                    assertEquals("2", group.getAttribute("cols"), name)
                    val columns = group.getElementsByTagNameNS(namespace.ifEmpty { null }, "colspec")
                    assertEquals(2, columns.length, name)
                    assertEquals(if (edge == "namest") "10pt" else "10.5pt", (columns.item(0) as Element).getAttribute("colwidth"), name)
                    // A missing authored width stays absent; it must not become NaN or an empty attribute.
                    assertFalse((columns.item(1) as Element).hasAttribute("colwidth"), name)
                    val entries = group.getElementsByTagNameNS(namespace.ifEmpty { null }, "entry")
                    assertEquals(listOf("Left", "Right", "Span"), (0 until entries.length).map { entries.item(it).textContent }, name)
                    assertEquals("c1", (entries.item(0) as Element).getAttribute("colname"), name)
                    assertEquals("c2", (entries.item(1) as Element).getAttribute("colname"), name)
                    val spans = group.getElementsByTagNameNS(namespace.ifEmpty { null }, "spanspec")
                    assertEquals(if (edge == "colname") 1 else 0, spans.length, name)
                    if (edge == "colname") {
                        assertEquals("c1", (spans.item(0) as Element).getAttribute("namest"), name)
                        assertEquals("c2", (spans.item(0) as Element).getAttribute("nameend"), name)
                    } else {
                        assertEquals(if (edge == "namest") "c2" else "c1", (entries.item(2) as Element).getAttribute("colname"), name)
                    }
                }
            }
        }
    }

    @Test
    fun noNamespaceTableModes() {
        tableModes(listOf(""))
    }

    @Test
    fun namespacedTableModes() {
        tableModes(listOf("http://docbook.org/ns/docbook", "urn:test:table"))
    }
}
