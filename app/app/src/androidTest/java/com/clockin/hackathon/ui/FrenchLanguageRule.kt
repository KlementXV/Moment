package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.AppLanguage
import org.junit.rules.ExternalResource

/** Existing UI assertions and reference screenshots are intentionally in French. */
class FrenchLanguageRule : ExternalResource() {
    private var previous: String? = null
    override fun before() {
        previous = AppLanguage.selection
        AppLanguage.select("fr")
    }
    override fun after() { AppLanguage.select(previous) }
}
