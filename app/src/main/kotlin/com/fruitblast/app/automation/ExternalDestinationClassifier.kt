package com.fruitblast.app.automation

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.fruitblast.automation.ExternalAppKind
import com.fruitblast.automation.FruitBlastConfig

/** Detects Play Store exactly and browsers through HTTPS/BROWSABLE intent handlers. */
internal class ExternalDestinationClassifier(private val packageManager: PackageManager) {
    private val browserPackages: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it == FruitBlastConfig.TARGET_PACKAGE || it == PLAY_STORE_PACKAGE }
            .toSet()
    }

    fun classify(packageName: String?): ExternalAppKind = when {
        packageName.isNullOrBlank() -> ExternalAppKind.OTHER
        packageName == PLAY_STORE_PACKAGE -> ExternalAppKind.PLAY_STORE
        packageName in browserPackages -> ExternalAppKind.BROWSER
        else -> ExternalAppKind.OTHER
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
