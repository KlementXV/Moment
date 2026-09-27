package com.klementxv.moment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object SkrUnit {
    var decimals by mutableIntStateOf(BuildConfig.SKR_DECIMALS)

    val unit: Long get() = pow10(decimals)

    fun pow10(decimals: Int): Long {
        require(decimals in 0..18) { "décimales hors bornes" }
        var value = 1L
        repeat(decimals) { value *= 10 }
        return value
    }
}
