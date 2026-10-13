package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class GameVerifiedForegroundTest {
    private class Harness {
        val verified = mutableListOf<Boolean>()
        val gaming = mutableListOf<Boolean>()
        val replies = mutableListOf<(GameForegroundResult) -> Unit>()
        val state = GameForegroundState(
            query = { _, _, _, done -> replies += done },
            publish = { gaming += it },
            publishVerifiedForeground = { verified += it },
        )
        fun finish(window: GameForegroundWindow, game: Boolean? = null) = replies.last()(GameForegroundResult(window, game))
    }

    @Test fun bothKnownGameAndNonGameAreVerifiedWithoutChangingGameProtection() {
        for (game in listOf(true, false)) {
            val h = Harness()
            h.state.connect(true)
            assertEquals(false, h.verified.last())
            h.state.candidate("app", 1)
            h.finish(GameForegroundWindow.Application, game)
            assertEquals(true, h.verified.last())
            assertEquals(game, h.gaming.last())
        }
    }

    @Test fun everyNewWindowWaitsForVerificationEvenWithCachedNonGame() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("reader", 1)
        h.finish(GameForegroundWindow.Application, false)
        val gamingBefore = h.gaming.toList()
        h.state.candidate("reader", 2)
        assertEquals(false, h.verified.last())
        assertEquals(gamingBefore, h.gaming)
        h.finish(GameForegroundWindow.Application, false)
        assertEquals(true, h.verified.last())
    }

    @Test fun overlayRetainsOnlyPreviouslyVerifiedUnderlyingApplication() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("game", 1)
        h.finish(GameForegroundWindow.Application, true)
        h.state.candidate("keyboard", 2)
        assertEquals(false, h.verified.last())
        h.finish(GameForegroundWindow.Overlay)
        assertEquals(true, h.verified.last())
        h.state.candidate("unknown", 3)
        h.finish(GameForegroundWindow.Unknown)
        assertEquals(false, h.verified.last())
        h.state.candidate("overlay", 4)
        h.finish(GameForegroundWindow.Overlay)
        assertEquals(false, h.verified.last())
    }

    @Test fun missingClassificationCannotVerifyAnApplication() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("app", 1)
        h.finish(GameForegroundWindow.Application, null)
        assertEquals(false, h.verified.last())
    }

    @Test fun screenChangesDisconnectAndReconnectInvalidateOldVerification() {
        val h = Harness()
        val token = h.state.connect(true)
        h.state.initialize(token, "game", GameForegroundResult(GameForegroundWindow.Application, true))
        assertEquals(true, h.verified.last())
        h.state.screen(false)
        assertEquals(false, h.verified.last())
        val screenOn = h.state.screen(true)
        assertEquals(false, h.verified.last())
        h.state.initialize(screenOn, "reader", GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(true, h.verified.last())
        h.state.disconnect()
        assertEquals(false, h.verified.last())
        h.state.connect(false)
        assertEquals(false, h.verified.last())
    }

    @Test fun staleResultCannotVerifyNewCandidateOrDisconnectedSession() {
        val h = Harness()
        h.state.connect(true)
        h.state.candidate("old", 1)
        val old = h.replies.last()
        h.state.candidate("new", 2)
        old(GameForegroundResult(GameForegroundWindow.Application, false))
        assertEquals(false, h.verified.last())
        val current = h.replies.last()
        h.state.disconnect()
        current(GameForegroundResult(GameForegroundWindow.Application, true))
        assertEquals(false, h.verified.last())
    }
}
