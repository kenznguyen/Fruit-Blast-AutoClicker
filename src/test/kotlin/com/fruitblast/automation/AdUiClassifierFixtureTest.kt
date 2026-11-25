package com.fruitblast.automation

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test-only parser for sanitized XML fixtures. It never touches Android APIs.
 */
internal object AccessibilityFixtureParser {
    fun parse(input: InputStream): List<AccessibilityNodeSnapshot> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(input)
        val root = document.documentElement
        require(root.tagName == "nodes") { "Fixture root must be <nodes>." }

        return (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .onEach { require(it.tagName == "node") { "Only <node> entries are supported." } }
            .map { element ->
                fun optional(name: String): String? =
                    element.getAttribute(name).trim().takeIf(String::isNotEmpty)

                fun requiredBoolean(name: String): Boolean =
                    element.getAttribute(name).toBooleanStrictOrNull()
                        ?: error("Fixture attribute '$name' must be true or false.")

                fun requiredInt(name: String): Int =
                    element.getAttribute(name).toIntOrNull()
                        ?: error("Fixture attribute '$name' must be an integer.")

                AccessibilityNodeSnapshot(
                    packageName = optional("package"),
                    className = optional("class"),
                    resourceId = optional("id"),
                    text = optional("text"),
                    contentDescription = optional("description"),
                    visibleToUser = requiredBoolean("visible"),
                    enabled = requiredBoolean("enabled"),
                    clickable = requiredBoolean("clickable"),
                    boundsWidth = requiredInt("width"),
                    boundsHeight = requiredInt("height"),
                )
            }
    }
}

internal fun runAdUiFixtureTests() {
    val classifier = AdUiClassifier()
    val loader = checkNotNull(AdUiClassifier::class.java.classLoader)

    fun fixture(name: String): List<AccessibilityNodeSnapshot> =
        checkNotNull(loader.getResourceAsStream("ad-ui/$name")) {
            "Missing test fixture: ad-ui/$name"
        }.use(AccessibilityFixtureParser::parse)

    val home = fixture("game-home.xml")
    check(classifier.classify(home).state == AdUiState.GAME_HOME_CANDIDATE) {
        "Game Home XML fixture must classify as a home candidate."
    }

    val playable = fixture("game-ad.xml")
    check(classifier.classify(playable).state == AdUiState.PLAYABLE_AD_CANDIDATE) {
        "Playable Ad XML fixture must classify as an ad candidate."
    }

    val result = classifier.classify(fixture("game-ad-end.xml"))
    check(result.state == AdUiState.PLAYABLE_AD_RESULT_CANDIDATE) {
        "Ad result XML fixture must classify as an ad-result candidate."
    }
    check(!result.continueVisible) { "PLAY NOW must never be a Continue signal." }

    check(classifier.classify(fixture("conflict.xml")).state == AdUiState.UNKNOWN) {
        "Conflicting Home and Ad evidence must fail closed."
    }

    val empty = "<nodes/>".byteInputStream().use(AccessibilityFixtureParser::parse)
    check(empty.isEmpty() && classifier.classify(empty).state == AdUiState.UNKNOWN) {
        "An empty fixture must remain UNKNOWN."
    }

    val invalidXmlRejected = runCatching {
        fixture("invalid.xml")
    }.isFailure
    check(invalidXmlRejected) { "Malformed XML fixtures must be rejected." }

    val ad = playable.first()
    fun withNode(node: AccessibilityNodeSnapshot) = classifier.classify(playable + node)

    val close = ad.copy(className = "android.widget.Button", resourceId = "id/close",
        contentDescription = "Close", clickable = true)
    check(withNode(close).closeVisible) { "Visible actionable Close must be detected." }

    val next = ad.copy(className = "android.widget.Button", resourceId = "id/next",
        text = "Next", clickable = true)
    check(withNode(next).nextVisible) { "Visible actionable Next must be detected." }

    val continueNode = ad.copy(className = "android.widget.Button", resourceId = "id/continue",
        text = "Continue", clickable = true)
    check(withNode(continueNode).continueVisible) {
        "Visible actionable Continue must be detected."
    }

    check(!withNode(close.copy(visibleToUser = false)).closeVisible) {
        "Hidden controls must not produce signals."
    }
    check(!withNode(close.copy(enabled = false)).closeVisible) {
        "Disabled controls must not produce signals."
    }
    check(!withNode(close.copy(clickable = false)).closeVisible) {
        "Non-clickable controls must not produce signals."
    }
    check(!withNode(close.copy(boundsWidth = 0)).closeVisible) {
        "Controls without positive bounds must not produce signals."
    }
    check(classifier.classify(playable + ad.copy(packageName = "com.android.vending")).state ==
        AdUiState.UNKNOWN) {
        "Mixed-package snapshots must fail closed."
    }

    val doctypeRejected = runCatching {
        """<!DOCTYPE nodes [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><nodes/>"""
            .byteInputStream().use(AccessibilityFixtureParser::parse)
    }.isFailure
    check(doctypeRejected) { "Fixture parser must reject DOCTYPE declarations." }

    println("PASS: 15 offline AdUiClassifier fixture/parser assertions")
}
