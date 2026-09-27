package com.klementxv.moment.ui

import com.klementxv.moment.i18n.AppLanguage
import org.junit.rules.ExternalResource

class FrenchLanguageRule : ExternalResource() {
    private var previous: String? = null
    override fun before() {
        previous = AppLanguage.selection
        AppLanguage.select("fr")
    }
    override fun after() { AppLanguage.select(previous) }
}
