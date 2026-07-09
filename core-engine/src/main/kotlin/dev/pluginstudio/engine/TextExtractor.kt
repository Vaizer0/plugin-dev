package dev.pluginstudio.engine

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

object TextExtractor {
    fun get(node: Node?): String {
        val children = node?.childNodes() ?: return ""
        if (children.isEmpty()) return ""
        return children.joinToString("") { child ->
            when {
                child.nodeName() == "p" -> getPTraverse(child)
                child.nodeName() == "br" -> "\n"
                child.nodeName() == "hr" -> "\n\n"
                child is TextNode -> child.text().trim()
                else -> getNodeTextTraverse(child)
            }
        }
    }

    private fun getPTraverse(node: Node): String {
        fun innerTraverse(node: Node): String = node.childNodes().joinToString("") { child ->
            when {
                child.nodeName() == "br" -> "\n"
                child is TextNode -> child.text()
                else -> innerTraverse(child)
            }
        }
        val paragraph = innerTraverse(node).trim()
        return if (paragraph.isEmpty()) "" else paragraph + "\n\n"
    }

    private fun getNodeTextTraverse(node: Node): String {
        val children = node.childNodes()
        if (children.isEmpty()) return ""
        return children.joinToString("") { child ->
            when {
                child.nodeName() == "p" -> getPTraverse(child)
                child.nodeName() == "br" -> "\n"
                child.nodeName() == "hr" -> "\n\n"
                child is TextNode -> {
                    val text = child.text().trim()
                    if (text.isEmpty()) "" else text + "\n\n"
                }
                else -> getNodeTextTraverse(child)
            }
        }
    }
}
