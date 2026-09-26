package com.clockin.hackathon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Unité du SKR affiché et saisi : 9 décimales pour le mint de test devnet, 6
 * pour le vrai SKR. Le mint lu sur la chaîne fait foi ; la valeur de la build
 * (`clockin.skrDecimals`) ne sert qu'avant cette lecture, pour l'affichage.
 * Aucune mise ne part avant la lecture (`ChainState.canStake`).
 */
object SkrUnit {
    var decimals by mutableIntStateOf(BuildConfig.SKR_DECIMALS)

    /** Unités de base dans un SKR. */
    val unit: Long get() = pow10(decimals)

    fun pow10(decimals: Int): Long {
        require(decimals in 0..18) { "décimales hors bornes" }
        var value = 1L
        repeat(decimals) { value *= 10 }
        return value
    }
}
