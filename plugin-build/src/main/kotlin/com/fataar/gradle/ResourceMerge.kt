package com.fataar.gradle

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.GradleException
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Remove duplicate definitions before Android's consumer sees one resource set. */
internal object ResourceMerge {
    data class Input(val owner: String, val entries: Map<String, ByteArray>)
    private data class Definition(val owner: String, val key: String, val bytes: ByteArray?, val element: Element?, val remove: () -> Unit)
    fun merge(inputs: List<Input>, winners: Map<String, String>): List<Map<String, ByteArray>> {
        val outputs = inputs.map { it.entries.toMutableMap() }
        val definitions = linkedMapOf<String, MutableList<Definition>>()
        val documents = mutableListOf<Triple<Int, String, Element>>()
        val changed = mutableSetOf<Element>()
        fun add(d: Definition) { definitions.getOrPut(d.key) { mutableListOf() }.add(d) }
        inputs.forEachIndexed { index, input ->
            input.entries.toSortedMap().forEach { (path, bytes) ->
                if (!path.startsWith("res/")) return@forEach
                val folder = path.removePrefix("res/").substringBefore('/')
                val qualifier = folder.substringAfter('-', "")
                if (folder.substringBefore('-') != "values") {
                    val key = "$qualifier/${folder.substringBefore('-')}/${path.substringAfterLast('/').substringBefore('.')}"
                    add(Definition(input.owner, key, bytes, null) { outputs[index].remove(path) })
                } else if (path.endsWith(".xml")) {
                    val factory = DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = true
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    }
                    val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
                    if (root.tagName != "resources") throw GradleException("FATAAR_INVALID_RESOURCE: ${input.owner}: $path")
                    documents += Triple(index, path, root)
                    children(root).forEach { element ->
                        if (element.tagName in setOf("public", "eat-comment", "skip")) return@forEach
                        val type = when (element.tagName) { "item" -> element.getAttribute("type"); "string-array", "integer-array" -> "array"; else -> element.tagName }
                        val name = element.getAttribute("name")
                        if (name.isBlank()) throw GradleException("FATAAR_INVALID_RESOURCE: ${input.owner}: unnamed $type in $path")
                        add(Definition(input.owner, "$qualifier/$type/$name", null, element) {
                            element.parentNode?.removeChild(element); changed += root
                        })
                        if (type == "declare-styleable") children(element).filter { it.tagName == "attr" }.forEach { attr ->
                            if (':' !in attr.getAttribute("name") && (attr.hasAttribute("format") || children(attr).isNotEmpty()))
                                add(Definition(input.owner, "$qualifier/attr/${attr.getAttribute("name")}", null, attr) {
                                    // Retain the reference inside the styleable; only its global definition is duplicated.
                                    attr.removeAttribute("format"); children(attr).forEach(attr::removeChild); changed += root
                                })
                        }
                    }
                }
            }
        }
        // Global attr definitions must survive even if their containing styleable loses.
        // Normalize only overlapping declarations; unique XML stays byte-for-byte intact.
        definitions.forEach { (key, values) ->
            for (i in values.indices) {
                val definition = values[i]
                val attr = definition.element ?: continue
                val parent = attr.parentNode as? Element ?: continue
                if (attr.tagName != "attr" || parent.tagName != "declare-styleable") continue
                val styleableKey = key.substringBefore('/') + "/declare-styleable/" + parent.getAttribute("name")
                if (values.size < 2 && definitions[styleableKey].orEmpty().size < 2) continue
                val root = attr.ownerDocument.documentElement
                val global = attr.cloneNode(true) as Element
                root.appendChild(global)
                attr.removeAttribute("format"); children(attr).forEach(attr::removeChild)
                changed += root
                values[i] = definition.copy(element = global, remove = { global.parentNode?.removeChild(global); changed += root })
            }
        }
        val used = mutableSetOf<String>()
        definitions.forEach { (key, recorded) ->
            // A duplicate styleable removed earlier must not own the surviving attr definition.
            val values = recorded.filter { definition ->
                val element = definition.element
                if (element == null) true else {
                    var node: Node? = element
                    while (node?.parentNode != null) node = node.parentNode
                    node?.nodeType == Node.DOCUMENT_NODE
                }
            }
            if (values.size < 2) return@forEach
            val selector = if (key in winners) key else key.substringAfter('/')
            val chosen = winners[selector]?.let { owner ->
                used += selector
                values.firstOrNull { it.owner == owner } ?: throw GradleException("FATAAR_INVALID_RESOURCE_WINNER: $key has no definition from $owner; owners=${values.map { it.owner }}")
            } ?: values.first()
            val equal = values.all { same(chosen, it) }
            if (!equal && winners[selector] == null) throw GradleException("FATAAR_RESOURCE_CONFLICT: $key: ${values.map { it.owner }.distinct().joinToString(" and ")}; choose resourceWinners[\"$key\"] explicitly")
            // A changed styleable can invalidate integer indices already inlined in child bytecode.
            if (!equal && key.substringAfter('/').startsWith("declare-styleable/"))
                throw GradleException("FATAAR_STYLEABLE_CONFLICT: $key: different attribute lists cannot be overlaid safely; owners=${values.map { it.owner }}")
            values.filter { it !== chosen }.forEach { it.remove() }
        }
        (winners.keys - used).takeIf { it.isNotEmpty() }?.let {
            throw GradleException("FATAAR_UNUSED_RESOURCE_WINNER: ${it.sorted()}; selectors must match an actual resource collision")
        }
        documents.forEach { (index, path, root) ->
            if (root in changed) {
                val buffer = java.io.ByteArrayOutputStream()
                TransformerFactory.newInstance().apply {
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
                }.newTransformer().transform(DOMSource(root.ownerDocument), StreamResult(buffer))
                outputs[index][path] = buffer.toByteArray()
            }
        }
        return outputs
    }
    private fun children(node: Node): List<Element> = (0 until node.childNodes.length).mapNotNull { node.childNodes.item(it) as? Element }
    private fun same(a: Definition, b: Definition): Boolean = when {
        a.bytes != null && b.bytes != null -> a.bytes.contentEquals(b.bytes)
        a.element != null && b.element != null -> canonical(a.element) == canonical(b.element)
        else -> false
    }
    private fun canonical(node: Node): String = when (node) {
        is Element -> buildString {
            append("<${node.namespaceURI.orEmpty()}:${node.localName ?: node.tagName}")
            (0 until node.attributes.length).map { node.attributes.item(it) }
                .filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
                .sortedBy { it.namespaceURI.orEmpty() + ":" + (it.localName ?: it.nodeName) }
                .forEach { append("|${it.namespaceURI.orEmpty()}:${it.localName ?: it.nodeName}=${it.nodeValue.length}:${it.nodeValue}") }
            append('>')
            val structured = children(node).isNotEmpty() || node.tagName in setOf("attr", "declare-styleable")
            for (i in 0 until node.childNodes.length) {
                val child = node.childNodes.item(i)
                if (child.nodeType == Node.COMMENT_NODE || structured && child.nodeType == Node.TEXT_NODE && child.nodeValue.isBlank()) continue
                append(canonical(child))
            }
            append("</>")
        }
        else -> "${node.nodeType}:${node.nodeValue.orEmpty().length}:${node.nodeValue.orEmpty()}"
    }
}
