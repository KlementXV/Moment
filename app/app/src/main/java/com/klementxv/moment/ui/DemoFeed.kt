package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr

internal data class DemoMoment(
    val name: String,
    val subtitle: String,
    val caption: String,
    val variant: Int,
)

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
