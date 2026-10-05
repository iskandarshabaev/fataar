package com.fataar.gradle
import org.junit.Assert.*
import org.junit.Test

class DependencyValidatorTest {
    private val graph = listOf(
        "root\tproject::sdk", "node\tproject::sdk\t1", "node\tproject::feature\t1",
        "node\tmodule:test:remote\t2", "node\tmodule:test:leaf\t3",
        "edge\tproject::sdk\tproject::feature\tproject::feature",
        "edge\tproject::feature\tmodule:test:remote\tmodule:test:remote",
        "edge\tmodule:test:remote\tmodule:test:leaf\tmodule:test:leaf",
    )
    @Test fun missingExternalIncludesFullPathAndIsNeverGuessed() {
        val result = DependencyValidator.analyze(graph, listOf("embed\tproject::feature"), listOf(":"))
        assertEquals(2, result.issues.size)
        assertTrue(result.report.contains("project::sdk -> project::feature -> module:test:remote"))
        assertTrue(result.issues.all { it.fix == null })
    }
    @Test fun externalRootCoversItsPublishedTransitiveClosure() {
        val result = DependencyValidator.analyze(graph + "edge\tproject::sdk\tmodule:test:remote\tmodule:test:remote",
            listOf("embed\tproject::feature", "api\tmodule:test:remote"), emptyList())
        assertTrue(result.report, result.issues.isEmpty())
    }
    @Test fun embedAndExternalCannotDescribeSameSelectedComponent() {
        val result = DependencyValidator.analyze(graph,
            listOf("embed\tproject::feature", "implementation\tproject::feature"), emptyList())
        assertTrue(result.issues.any { it.code == "FATAAR_CONFLICTING_DELIVERY" })
    }
    @Test fun externalCyclesTerminateAndRemainCovered() {
        val result = DependencyValidator.analyze(graph + listOf(
            "edge\tproject::sdk\tmodule:test:remote\tmodule:test:remote",
            "edge\tmodule:test:leaf\tmodule:test:remote\tmodule:test:remote"),
            listOf("embed\tproject::feature", "api\tmodule:test:remote"), emptyList())
        assertTrue(result.report, result.issues.isEmpty())
    }
    @Test fun rulesRejectUnknownRolesAndIncompleteCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { DependencyRules.parse("guess module test:remote:1") }
        assertThrows(IllegalArgumentException::class.java) { DependencyRules.parse("api module test:remote") }
        assertEquals(Triple("embed", "project", ":a"), DependencyRules.parse("# comment\nembed project :a\n").single())
    }
    @Test fun scopedRulesRoundTripAndFixesStayInChosenVariant() {
        assertEquals(Triple("redDebugEmbed", "project", ":leaf"),
            DependencyRules.parse("redDebugEmbed project :leaf").single())
        assertEquals(Triple("redEmbed", "module", "test:remote:1"),
            DependencyRules.parse("redEmbed module test:remote:1").single())
        val scopedGraph = listOf("root\tproject::sdk", "node\tproject::sdk\t1",
            "node\tproject::feature\t1", "node\tproject::leaf\t1",
            "edge\tproject::sdk\tproject::feature\tproject::feature",
            "edge\tproject::feature\tproject::leaf\tproject::leaf")
        val result = DependencyValidator.analyze(scopedGraph, listOf("embed\tproject::feature"),
            listOf(":"), "redDebugEmbed")
        assertEquals("redDebugEmbed project :leaf", result.issues.single().fix)
    }
    @Test fun bomDoesNotProvidePayloadAndMissingBomIsNeverAutoEmbedded() {
        val records = graph + listOf("node\tproject::bom\t1", "platform\tproject::bom\tplatform",
            "edge\tproject::feature\tproject::bom\tproject::bom")
        val missing = DependencyValidator.analyze(records, listOf("embed\tproject::feature"), listOf(":"))
        val issue = missing.issues.single { it.code == "FATAAR_MISSING_PLATFORM" }
        assertNull(issue.fix); assertTrue(issue.message.contains("api(platform(...))"))
        val declared = DependencyValidator.analyze(records + "edge\tproject::sdk\tproject::bom\tproject::bom",
            listOf("embed\tproject::feature", "api\tproject::bom"), emptyList())
        assertEquals(2, declared.issues.size)
        assertTrue(declared.issues.all { it.code == "FATAAR_MISSING_DEPENDENCY" })
    }
    @Test fun rejectsEmbeddingAPlatformInsteadOfTreatingItAsAnArchive() {
        val result = DependencyValidator.analyze(listOf("root\tproject::sdk", "node\tproject::sdk\t1", "node\tproject::bom\t1",
            "platform\tproject::bom\tplatform", "edge\tproject::sdk\tproject::bom\tproject::bom"),
            listOf("embed\tproject::bom"), emptyList())
        assertTrue(result.issues.any { it.code == "FATAAR_EMBED_PLATFORM" })
    }
    @Test fun rejectsEnforcedPlatformEvenWhenExternalRootCoversItsGraph() {
        val result = DependencyValidator.analyze(listOf("root\tproject::sdk", "node\tproject::sdk\t1", "node\tproject::bom\t1",
            "platform\tproject::bom\tenforced-platform", "edge\tproject::sdk\tproject::bom\tproject::bom"),
            listOf("api\tproject::bom"), emptyList())
        assertTrue(result.issues.any { it.code == "FATAAR_ENFORCED_PLATFORM" })
    }

}
