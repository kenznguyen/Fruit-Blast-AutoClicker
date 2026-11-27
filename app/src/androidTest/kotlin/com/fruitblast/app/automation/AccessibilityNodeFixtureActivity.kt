package com.fruitblast.app.automation

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import com.fruitblast.automation.FruitBlastConfig

/**
 * Test-APK-only window containing a deterministic Android View accessibility tree.
 * This Activity is declared only in src/androidTest/AndroidManifest.xml.
 */
internal class AccessibilityNodeFixtureActivity : Activity() {
    lateinit var fixtureRoot: LinearLayout
        private set

    var closeClickCount: Int = 0
        private set

    fun addDuplicateCloseControl() {
        fixtureRoot.addView(FixtureCloseButton(this) { closeClickCount++ }, fixtureLayoutParams())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fixtureRoot = FixtureLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fixtureRoot.addView(
            FixtureNodeView(
                context = this,
                nodeClassName = "android.webkit.WebView",
                resourceName = "playable",
            ),
            fixtureLayoutParams(),
        )
        fixtureRoot.addView(
            FixtureNodeView(
                context = this,
                nodeClassName = "android.view.View",
                resourceName = "stage",
            ),
            fixtureLayoutParams(),
        )
        fixtureRoot.addView(
            FixtureNodeView(
                context = this,
                nodeClassName = "android.view.View",
                resourceName = "overlay",
            ),
            fixtureLayoutParams(),
        )
        fixtureRoot.addView(
            FixtureCloseButton(this) { closeClickCount++ },
            fixtureLayoutParams(),
        )

        setContentView(
            fixtureRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun fixtureLayoutParams() = LinearLayout.LayoutParams(240, 96)

    private class FixtureLinearLayout(context: Activity) : LinearLayout(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.packageName = FruitBlastConfig.TARGET_PACKAGE
            info.className = "android.widget.LinearLayout"
            info.viewIdResourceName = "${FruitBlastConfig.TARGET_PACKAGE}:id/fixture_root"
        }
    }

    private class FixtureNodeView(
        context: Activity,
        private val nodeClassName: String,
        private val resourceName: String,
    ) : View(context) {
        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.packageName = FruitBlastConfig.TARGET_PACKAGE
            info.className = nodeClassName
            info.viewIdResourceName = "${FruitBlastConfig.TARGET_PACKAGE}:id/$resourceName"
        }
    }

    private class FixtureCloseButton(context: Activity, onClicked: () -> Unit) : Button(context) {
        init {
            text = "Close"
            isEnabled = true
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { onClicked() }
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.packageName = FruitBlastConfig.TARGET_PACKAGE
            info.className = "android.widget.Button"
            info.viewIdResourceName = "${FruitBlastConfig.TARGET_PACKAGE}:id/close"
        }
    }
}
