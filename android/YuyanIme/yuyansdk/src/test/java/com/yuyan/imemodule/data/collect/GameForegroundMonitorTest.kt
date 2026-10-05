package com.yuyan.imemodule.data.collect

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test

class GameForegroundMonitorTest {
    @Test fun screenOffPermitsSyncButKeepsGameInteractionProtected() {
        val sync = mutableListOf<Boolean>()
        val interaction = mutableListOf<Boolean>()
        var finish: ((GameForegroundResult) -> Unit)? = null
        val state = GameForegroundState({ _, _, _, done -> finish = done }, { sync += it },
            publishInteraction = { interaction += it })
        state.connect(true)
        state.candidate("game", 1)
        finish!!(GameForegroundResult(GameForegroundWindow.Application, true))
        state.screen(false)
        assertEquals(false, sync.last())
        assertEquals(listOf(true), interaction.distinct())
        state.screen(true)
        state.candidate("reader", 2)
        finish!!(GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(false, interaction.last())
    }

    @Test fun startingWhileScreenOffDoesNotAssumeSafeInteraction() {
        val interaction = mutableListOf<Boolean>()
        val state = GameForegroundState({ _, _, _, _ -> }, {}, publishInteraction = { interaction += it })
        state.connect(false)
        assertEquals(listOf(true), interaction)
    }
    private data class Query(val name: String, val cached: Boolean?, val finish: (GameForegroundResult) -> Unit)
    private class Harness(cacheLimit: Int = 64) {
        val publications = mutableListOf<Boolean>()
        val queries = mutableListOf<Query>()
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val initializationRetries = mutableListOf<Long>()
        val state = GameForegroundState({ name, _, cached, done -> queries += Query(name, cached, done) },
            { publications += it }, cacheLimit, { delay, action -> scheduled += (now + delay) to action }, { now },
            { initializationRetries += it })
        fun finish(gaming: Boolean?, index: Int = queries.lastIndex) =
            queries[index].finish(GameForegroundResult(GameForegroundWindow.Application, gaming))
        fun unknown() = queries.last().finish(GameForegroundResult(GameForegroundWindow.Unknown))
        fun advance(millis: Long) {
            now += millis
            while (scheduled.any { it.first <= now }) {
                val task = scheduled.first { it.first <= now }
                scheduled.remove(task)
                task.second()
            }
        }
    }

    @Test fun newForegroundPausesBeforeAsynchronousClassificationAndDuplicateEventsDoNotQueryAgain() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        assertEquals(true, h.publications.last())
        assertEquals(1, h.queries.size)
        h.finish(false)
        assertEquals(false, h.publications.last())
        h.state.candidate("reader", 1)
        assertEquals(1, h.queries.size)
        h.state.candidate("game", 2)
        assertEquals(true, h.publications.last())
        h.finish(true)
        assertEquals(true, h.publications.last())
    }

    @Test fun lateNonGameResultCannotReleaseNewGame() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.state.candidate("game", 2)
        h.finish(true, 1)
        h.finish(false, 0)
        assertEquals(true, h.publications.last())
    }

    @Test fun overlayCannotReleaseGameAndUnknownClassificationStaysPaused() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("game", 1)
        h.finish(true)
        h.state.candidate("keyboard", 2)
        h.queries.last().finish(GameForegroundResult(GameForegroundWindow.Overlay))
        assertEquals(true, h.publications.last())
        h.state.candidate("unavailable", 3)
        h.finish(null)
        assertEquals(true, h.publications.last())
        h.state.candidate("unverified-window", 4)
        h.queries.last().finish(GameForegroundResult(GameForegroundWindow.Unknown, false))
        assertEquals(true, h.publications.last())
    }

    @Test fun overlayAboveNonGameRestoresPreviousClassification() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.finish(false)
        h.state.candidate("keyboard", 2)
        h.queries.last().finish(GameForegroundResult(GameForegroundWindow.Overlay))
        assertEquals(false, h.publications.last())
    }

    @Test fun screenOffResumesAndScreenOnInvalidatesOldResultUntilForegroundIsConfirmed() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.state.screen(false)
        assertEquals(false, h.publications.last())
        h.state.candidate("game", 2)
        assertEquals(1, h.queries.size)
        h.state.screen(true)
        assertEquals(true, h.publications.last())
        h.finish(false)
        assertEquals(true, h.publications.last())
        h.state.candidate("reader", 1)
        h.finish(false)
        assertEquals(false, h.publications.last())
    }

    @Test fun disconnectStaysConservativeAndRejectsLateCallbacksAndEvents() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.state.disconnect()
        h.finish(false)
        h.state.candidate("reader", 2)
        h.state.screen(false)
        assertEquals(true, h.publications.last())
        assertEquals(1, h.queries.size)
    }

    @Test fun initialRootResultCannotReplaceLaterEventOrReconnection() {
        val h = Harness()
        val first = h.state.connect(true)
        h.state.candidate("game", 1)
        h.finish(true)
        h.state.initialize(first, "reader", GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(true, h.publications.last())
        h.state.disconnect()
        val second = h.state.connect(true)
        h.state.initialize(first, "reader", GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(true, h.publications.last())
        h.state.initialize(second, "reader", GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(false, h.publications.last())
    }

    @Test fun cacheIsBoundedButEveryNewWindowStillRequiresWindowVerification() {
        val h = Harness(cacheLimit = 2)
        h.state.connect(true)
        for ((index, name) in listOf("one", "two", "three").withIndex()) {
            h.state.candidate(name, index)
            assertNull(h.queries.last().cached)
            h.finish(false)
        }
        h.state.candidate("two", 4)
        assertEquals(false, h.queries.last().cached)
        h.finish(false)
        h.state.candidate("one", 5)
        assertNull(h.queries.last().cached)
    }

    @Test fun screenOffConnectionAllowsBackgroundButDoesNotTrustInitialization() {
        val h = Harness()
        val token = h.state.connect(false)
        assertEquals(false, h.publications.last())
        h.state.initialize(token, "game", GameForegroundResult(GameForegroundWindow.Application, true))
        assertEquals(false, h.publications.last())
    }

    @Test fun cachedNonGameChangingWindowsDoesNotCancelBackgroundWorkAgain() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.finish(false)
        val before = h.publications.toList()
        h.state.candidate("reader", 2)
        assertEquals(before, h.publications)
        assertEquals(2, h.queries.size)
        h.finish(false)
        assertEquals(before, h.publications)
        h.state.candidate("game", 3)
        h.finish(true)
        h.state.candidate("reader", 2)
        assertEquals(true, h.publications.last())
        h.finish(false)
        assertEquals(false, h.publications.last())
    }

    @Test fun transientMissingWindowRetriesAndOnlySuccessfulResultDeduplicatesPermanently() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.unknown()
        h.advance(250)
        assertEquals(2, h.queries.size)
        h.finish(false)
        h.state.candidate("reader", 1)
        h.advance(2000)
        assertEquals(2, h.queries.size)
        assertEquals(false, h.publications.last())
    }

    @Test fun retriesAreBoundedAndEventFloodCannotCreateMoreQueries() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.unknown()
        repeat(100) { h.state.candidate("reader", 1) }
        assertEquals(1, h.queries.size)
        h.advance(250)
        assertEquals(2, h.queries.size)
        h.unknown()
        h.advance(750)
        assertEquals(3, h.queries.size)
        h.unknown()
        repeat(100) { h.state.candidate("reader", 1) }
        h.advance(999)
        assertEquals(3, h.queries.size)
        h.advance(1)
        h.state.candidate("reader", 1)
        assertEquals(4, h.queries.size)
    }

    @Test fun newForegroundAndLifecycleChangesInvalidateScheduledRetries() {
        for (change in listOf<(Harness) -> Unit>(
            { it.state.candidate("game", 2); it.finish(true) },
            { it.state.screen(false) },
            { it.state.disconnect() },
        )) {
            val h = Harness()
            h.state.connect(true)
            h.state.candidate("reader", 1)
            h.unknown()
            change(h)
            val queries = h.queries.size
            val published = h.publications.toList()
            h.advance(2000)
            assertEquals(queries, h.queries.size)
            assertEquals(published, h.publications)
        }
    }

    @Test fun emptyOrOverlayInitialRootGetsOnlyTwoGenerationBoundedRetries() {
        for (result in listOf(GameForegroundResult(GameForegroundWindow.Unknown),
            GameForegroundResult(GameForegroundWindow.Overlay))) {
            val h = Harness()
            val token = h.state.connect(true)
            h.state.initialize(token, null, result)
            h.advance(250)
            assertEquals(listOf(token), h.initializationRetries)
            h.state.initialize(token, "overlay", result)
            h.advance(750)
            assertEquals(listOf(token, token), h.initializationRetries)
            h.state.initialize(token, null, result)
            h.advance(5000)
            assertEquals(2, h.initializationRetries.size)
            assertEquals(true, h.publications.last())
        }
        val h = Harness()
        val token = h.state.connect(true)
        h.state.initialize(token, null, GameForegroundResult(GameForegroundWindow.Unknown))
        h.state.candidate("reader", 1)
        h.finish(false)
        h.advance(250)
        assertTrue(h.initializationRetries.isEmpty())
        assertEquals(false, h.publications.last())
    }

    @Suppress("DEPRECATION")
    @Test fun metadataAndExplicitFallbackGamesAreRecognizedWithoutGuessingPackageNames() {
        assertTrue(declaredGame("some.game", ApplicationInfo.CATEGORY_GAME, 0))
        assertTrue(declaredGame("legacy", ApplicationInfo.CATEGORY_UNDEFINED, ApplicationInfo.FLAG_IS_GAME))
        assertTrue(declaredGame("com.tencent.tmgp.sgame", ApplicationInfo.CATEGORY_UNDEFINED, 0))
        assertTrue(declaredGame("com.kidsgame.forest.debug", ApplicationInfo.CATEGORY_UNDEFINED, 0))
        assertFalse(declaredGame("com.random.game.store", ApplicationInfo.CATEGORY_UNDEFINED, 0))
        assertFalse(declaredGame("reader", ApplicationInfo.CATEGORY_PRODUCTIVITY, 0))
    }
}
