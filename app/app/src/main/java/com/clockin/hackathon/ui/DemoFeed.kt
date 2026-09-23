package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

/**
 * Le mode démo : de faux Moments pour regarder à quoi ressemble un cercle
 * habité, en attendant que le keyserver distribue les clés des vrais.
 *
 * Tout est déduit du jour UTC, sans hasard : le feed doit rester identique
 * d'une recomposition à l'autre, et se renouveler à minuit comme le vrai.
 * Rien ici ne touche la chaîne — ces Moments portent la pastille « Démo ».
 */
internal data class DemoMoment(
    val name: String,
    val subtitle: String,
    val caption: String,
    /** Indexe la palette de l'illustration, pour que deux cartes voisines diffèrent. */
    val variant: Int,
)

/** Cinq cartes : assez pour un cercle vivant, assez peu pour rester lisible. */
internal const val DEMO_MOMENTS = 5

private val PEOPLE = listOf(
    "Léa", "Youssef", "Mara", "Théo", "Noor", "Sacha",
    "Inès", "Gabriel", "Amara", "Jonas", "Chloé", "Ravi",
)

private val CAPTIONS get() = listOf(
    tr(Message.UpBeforeTheAlarmForOnce),
    tr(Message.SameBenchSameCoffeeWorksForMe),
    tr(Message.TwentyMinutesOfWalkingBeforeTheCity),
    tr(Message.AlmostSkippedTodayDidnT),
    tr(Message.ALunchtimeBreakAwayFromTheOffice),
    tr(Message.ItWasRainingIWentAnyway),
    tr(Message.DayIMNotReallyCountingAnymore),
    tr(Message.NothingSpectacularThatSThePoint),
    tr(Message.LastLightOnTheRooftops),
    tr(Message.MySisterJoinedMeThisMorning),
    tr(Message.TheDogChoosesTheRoute),
    tr(Message.TwoQuietMinutesOutOfTheDay),
)

/**
 * Le pas 5 est premier avec 12 : la distribution tourne sur douze jours au
 * lieu d'alterner entre deux groupes, et cinq voisins d'un cycle de douze
 * sont toujours cinq personnes distinctes.
 */
internal fun demoMoments(utcDay: Long): List<DemoMoment> = List(DEMO_MOMENTS) { index ->
    val hours = Math.floorMod(utcDay * 3 + index * 7, 9) + 1
    val streak = Math.floorMod(utcDay * 11 + index * 13, 120) + 2
    DemoMoment(
        name = PEOPLE[Math.floorMod(utcDay * 5 + index, PEOPLE.size)],
        subtitle = tr(Message.HAgoDayStreak, hours, streak),
        caption = CAPTIONS[Math.floorMod(utcDay * 7 + index * 5, CAPTIONS.size)],
        variant = Math.floorMod(utcDay + index, DEMO_MOMENTS),
    )
}
