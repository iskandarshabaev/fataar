package com.fataar.gradle

import org.gradle.api.GradleException
import org.junit.Assert.*
import org.junit.Test

class ResourceMergeTest {
    private fun input(owner: String, value: String, path: String = "res/values/values.xml") = ResourceMerge.Input(owner, mapOf(path to value.toByteArray()))
    @Test fun removesIdenticalValuesAndFilesWithoutDroppingUnrelatedDefinitions() {
        val a = input("sdk", "<resources><string name=\"same\">ok</string></resources>")
        val b = input("child", "<resources>\n<string name=\"same\">ok</string><string name=\"unique\">other</string></resources>")
        val merged = ResourceMerge.merge(listOf(a, b), emptyMap())
        assertArrayEquals(a.entries.values.single(), merged[0].values.single())
        assertFalse(merged[1].values.single().decodeToString().contains("name=\"same\""))
        assertTrue(merged[1].values.single().decodeToString().contains("unique"))
        val file = input("sdk", "<shape/>", "res/drawable/icon.xml")
        assertTrue(ResourceMerge.merge(listOf(file, file.copy(owner = "child")), emptyMap())[1].isEmpty())
    }
    @Test fun overlaysOnlyTheRequestedQualifierAndRejectsUnknownSelectorsOrOwners() {
        val a = input("sdk", "<resources><string name=\"same\">sdk</string></resources>")
        val b = input("child", "<resources><string name=\"same\">child</string></resources>")
        val merged = ResourceMerge.merge(listOf(a, b), mapOf("string/same" to "child"))
        assertFalse(merged[0].values.single().decodeToString().contains("name=\"same\""))
        assertArrayEquals(b.entries.values.single(), merged[1].values.single())
        assertThrows(GradleException::class.java) { ResourceMerge.merge(listOf(a, b), mapOf("string/same" to "missing")) }
        assertThrows(GradleException::class.java) { ResourceMerge.merge(listOf(a), mapOf("string/typo" to "sdk")) }
        val night = b.copy(entries = mapOf("res/values-night/values.xml" to b.entries.values.single()))
        assertEquals(2, ResourceMerge.merge(listOf(a, night), emptyMap()).sumOf { it.size })
    }
    @Test fun doesNotTreatTextWhitespaceAsEquivalentOrOverlayIncompatibleStyleables() {
        val a = input("sdk", "<resources><string name=\"x\">a b</string></resources>")
        val b = input("child", "<resources><string name=\"x\">ab</string></resources>")
        assertTrue(assertThrows(GradleException::class.java) { ResourceMerge.merge(listOf(a, b), emptyMap()) }.message!!.contains("RESOURCE_CONFLICT"))
        val left = input("sdk", "<resources><declare-styleable name=\"View\"><attr name=\"first\"/></declare-styleable></resources>")
        val right = input("child", "<resources><declare-styleable name=\"View\"><attr name=\"second\"/></declare-styleable></resources>")
        assertTrue(assertThrows(GradleException::class.java) { ResourceMerge.merge(listOf(left, right), mapOf("declare-styleable/View" to "sdk")) }.message!!.contains("STYLEABLE_CONFLICT"))
    }
    @Test fun mergesInlineAndGlobalAttrDefinitionsWithoutLosingTheSurvivingStyleable() {
        val left = input("sdk", "<resources><attr name=\"shared\" format=\"string\"/><declare-styleable name=\"View\"><attr name=\"shared\"/></declare-styleable></resources>")
        val right = input("child", "<resources><declare-styleable name=\"View\"><attr name=\"shared\" format=\"string\"/></declare-styleable></resources>")
        val merged = ResourceMerge.merge(listOf(left, right), emptyMap())
        val xml = merged.flatMap { it.values }.joinToString { it.decodeToString() }
        assertEquals(1, Regex("format=\"string\"").findAll(xml).count())
        assertEquals(1, Regex("<declare-styleable").findAll(xml).count())
    }
}
