package eth.serotine.domain.usecase

import eth.serotine.domain.model.NetworkUpgrade.Phase
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.model.UpgradeNotice.Kind
import eth.serotine.domain.repository.KnownUpgrade
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UpgradeNoticesTest {

    private val known = FakeKnownUpgradeStore()
    private val notifier = FakeNotifier()
    private val activation = glamsterdam.activationTime!!
    private var now = activation - 30 * DAY
    private val notices = UpgradeNotices(known, notifier) { now }

    private val shownKinds get() = notifier.shown.map { it.kind }

    @Test
    fun theFirstReportIsAnnouncedOnceAndTheWeekReminderIsAskedFor() = runTest {
        assertEquals(activation - 7 * DAY, notices.check(glamsterdam))
        assertEquals(listOf(UpgradeNotice(Kind.ANNOUNCED, glamsterdam)), notifier.shown)
        assertEquals(listOf<Long?>(activation - 7 * DAY), notifier.reminders)

        // The engine reports it again and again; the user hears of it once.
        notices.check(glamsterdam)
        notices.check(glamsterdam.copy(reportingNetworks = 9))
        assertEquals(listOf(Kind.ANNOUNCED), shownKinds)
        assertEquals(listOf<Long?>(activation - 7 * DAY), notifier.reminders, "asked for once")
        assertEquals(KnownUpgrade(glamsterdam.copy(reportingNetworks = 9), setOf(Kind.ANNOUNCED)), known.record.value)
    }

    @Test
    fun theRemindersFallDueFromTheRecordWithNoReport() = runTest {
        notices.check(glamsterdam)

        now = activation - 7 * DAY
        assertEquals(activation - DAY, notices.remind())
        now = activation - DAY
        assertEquals(activation, notices.remind())
        now = activation
        assertEquals(null, notices.remind())
        now = activation + 30 * DAY
        notices.remind()

        assertEquals(listOf(Kind.ANNOUNCED, Kind.WEEK_AHEAD, Kind.DAY_AHEAD, Kind.ACTIVATED), shownKinds)
        assertEquals(listOf(activation - 7 * DAY, activation - DAY, activation, null), notifier.reminders)
    }

    @Test
    fun allIsSaidOnceTheActivationIsShown() = runTest {
        assertEquals(false, notices.activationShown())
        notices.check(glamsterdam)
        assertEquals(false, notices.activationShown())

        now = activation
        notices.remind()
        assertEquals(true, notices.activationShown())
    }

    @Test
    fun aPhoneThatWasOffHearsOnlyTheMostUrgentNotice() = runTest {
        notices.check(glamsterdam)

        now = activation - DAY / 2
        notices.remind()

        assertEquals(listOf(Kind.ANNOUNCED, Kind.DAY_AHEAD), shownKinds)
        assertEquals(Kind.entries.toSet() - Kind.ACTIVATED, known.record.value?.shown)
    }

    @Test
    fun anUpgradeFirstHeardOfAfterItActivatedIsSaidOnceAndThatIsAll() = runTest {
        now = activation + DAY

        assertEquals(null, notices.check(glamsterdam.copy(phase = Phase.ACTIVE, activationTime = null)))
        notices.remind()

        assertEquals(listOf(Kind.ACTIVATED), shownKinds)
        assertEquals(listOf<Long?>(null), notifier.reminders)
    }

    @Test
    fun peersOnTheNewRulesActivateTheRecordedUpgradeEarly() = runTest {
        notices.check(glamsterdam)

        now = activation - 3 * DAY
        notices.check(glamsterdam.copy(phase = Phase.ACTIVE, activationTime = null, forkId = null))

        assertEquals(
            UpgradeNotice(Kind.ACTIVATED, glamsterdam.copy(phase = Phase.ACTIVE)),
            notifier.shown.last(),
            "with the time the announcement gave",
        )
        assertEquals(null, notifier.reminders.last())
    }

    @Test
    fun anUpgradeMovedToAnotherTimeIsAnnouncedAfresh() = runTest {
        notices.check(glamsterdam)
        now = activation - 7 * DAY
        notices.remind()

        val moved = glamsterdam.copy(activationTime = activation + 14 * DAY)
        assertEquals(activation + 7 * DAY, notices.check(moved))

        assertEquals(listOf(Kind.ANNOUNCED, Kind.WEEK_AHEAD, Kind.ANNOUNCED), shownKinds)
        assertEquals(moved, notifier.shown.last().upgrade)
    }

    @Test
    fun anUpgradeWithoutATimeIsAnnouncedWithNothingToRemindOf() = runTest {
        assertEquals(null, notices.check(glamsterdam.copy(activationTime = null)))

        assertEquals(listOf(Kind.ANNOUNCED), shownKinds)
        assertEquals(listOf<Long?>(null), notifier.reminders)
    }

    @Test
    fun aRecordThatCannotBeStoredStillHasItsNoticeShown() = runTest {
        known.failing = true

        notices.check(glamsterdam)

        assertEquals(listOf(Kind.ANNOUNCED), shownKinds)
        assertEquals(null, known.record.value)
    }

    @Test
    fun followingTheEngineShowsTheRemindersThatFallDueWhileItRuns() = runTest {
        val upgrades = FakeNetworkUpgrades()
        val start = activation - 7 * DAY - 60
        val clock = { start + currentTime / 1_000 }
        val following = UpgradeNotices(known, notifier, clock)
        backgroundScope.launch { FollowUpgradeReports(upgrades, following, clock)() }
        runCurrent()
        assertEquals(emptyList(), shownKinds)

        upgrades.report.value = glamsterdam
        runCurrent()
        assertEquals(listOf(Kind.ANNOUNCED), shownKinds)

        advanceTimeBy(61_000)
        assertEquals(listOf(Kind.ANNOUNCED, Kind.WEEK_AHEAD), shownKinds)
    }
}
