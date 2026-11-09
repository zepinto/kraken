package com.zepinto.kraken

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val A = CastawaysCard.TREASURE
private val B = CastawaysCard.EMPTY_SEA
private val X = CastawaysCard.KRAKEN

/**
 * A Castaways game with a known layout, written as (key of player 0 / key of player 1):
 * 0-2 A/A, 3-7 A/B, 8 A/X, 9-13 B/A, 14 X/A, 15-21 B/B, 22 B/X, 23 X/B, 24 X/X.
 */
private fun castaways(giver: Int = 0, tokens: Int = 9): CastawaysState {
    val g = CastawaysEngine.newGame(Lang.EN, (1..25).map { "word$it" }, tokens, 5L, Random(1))
    val key0 = listOf(A, A, A) + List(5) { A } + A + List(5) { B } + X + List(7) { B } + B + X + X
    val key1 = listOf(A, A, A) + List(5) { B } + X + List(5) { A } + A + List(7) { B } + X + B + X
    return g.copy(keys = listOf(key0, key1), giver = giver)
}

class CastawaysEngineTest {
    @Test
    fun aGameNeedsAtLeastOneTurn() {
        val words = (1..25).map { "w$it" }
        var refused = false
        try {
            CastawaysEngine.newGame(Lang.EN, words, 0, 1L, Random(1))
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun theLayoutIsTheOfficialOne() {
        repeat(60) { seed ->
            val g = CastawaysEngine.newGame(Lang.EN, (1..25).map { "w$it" }, 9, 1L, Random(seed))
            for (p in 0..1) {
                assertEquals(9, g.keys[p].count { it == A })
                assertEquals(3, g.keys[p].count { it == X })
                assertEquals(13, g.keys[p].count { it == B })
            }
            assertEquals("treasures on both keys", 3, (0 until 25).count { g.keys[0][it] == A && g.keys[1][it] == A })
            assertEquals("kraken on both keys", 1, (0 until 25).count { g.keys[0][it] == X && g.keys[1][it] == X })
            assertEquals("treasures on at least one key", 15, g.treasuresTotal)
            assertEquals(9, g.tokens)
            assertEquals(0, g.treasuresFound)
            assertTrue(g.giver in 0..1)
        }
    }

    @Test
    fun bothPlayersGetToGiveTheFirstClueSometimes() {
        val givers = (0 until 40).map { CastawaysEngine.newGame(Lang.EN, (1..25).map { "w$it" }, 9, 1L, Random(it)).giver }.toSet()
        assertEquals(setOf(0, 1), givers)
    }

    @Test
    fun anTreasureOnTheGiversKeyIsFoundAndTheTurnGoesOn() {
        val g = CastawaysEngine.guess(castaways(giver = 0), player = 1, index = 0)
        assertTrue(g.found[0])
        assertEquals(0, g.giver)
        assertEquals(9, g.tokens)
        assertEquals(1, g.guessesThisTurn)
        assertEquals(CastawaysLast(0, CastawaysResult.TREASURE, 1), g.last)
    }

    @Test
    fun theGiversKeyDecidesNotTheGuessers() {
        // word 9 is an empty sea on key 0 but a treasure on key 1. Player 1 guessing during player 0's turn gets "empty sea".
        val g = CastawaysEngine.guess(castaways(giver = 0), 1, 9)
        assertFalse(g.found[9])
        assertTrue(g.tan[0][9])
        assertFalse(g.tan[1][9])
    }

    @Test
    fun anEmptySeaEndsTheTurnUsesATokenAndSwapsTheRoles() {
        val g = CastawaysEngine.guess(castaways(giver = 0), 1, 9)
        assertEquals(1, g.giver)
        assertEquals(8, g.tokens)
        assertEquals(0, g.guessesThisTurn)
        assertEquals(CastawaysPhase.PLAYING, g.phase)
        // the word stays on the board: the other player can still find it, from their own key
        val next = CastawaysEngine.guess(g, 0, 9) // player 1 gives, player 0 guesses; word 9 is a treasure on key 1
        assertTrue(next.found[9])
    }

    @Test
    fun anKrakenOnTheGiversKeyLosesAtOnce() {
        val g = CastawaysEngine.guess(castaways(giver = 0), 1, 14)
        assertEquals(CastawaysPhase.LOST, g.phase)
        assertEquals(LossReason.KRAKEN, g.lossReason)
        assertEquals(g, CastawaysEngine.guess(g, 1, 0)) // nothing happens after the end
        assertEquals(g, CastawaysEngine.pass(g, 1))
    }

    @Test
    fun onlyTheGuesserMayGuessAndNoWordTwice() {
        val g = castaways(giver = 0)
        assertEquals(g, CastawaysEngine.guess(g, 0, 0)) // the giver may not
        val one = CastawaysEngine.guess(g, 1, 0)
        assertEquals(one, CastawaysEngine.guess(one, 1, 0)) // already found
        val tan = CastawaysEngine.guess(one, 1, 9) // empty sea: turn ends, roles swap
        val back = tan.copy(giver = 0) // pretend it is player 0's clue again
        assertEquals(back, CastawaysEngine.guess(back, 1, 9)) // known empty sea on that key: ignored, no token lost
        assertEquals(g, CastawaysEngine.guess(g, 1, -1))
        assertEquals(g, CastawaysEngine.guess(g, 1, 25))
    }

    @Test
    fun passingNeedsAtLeastOneGuessAndUsesAToken() {
        val g = castaways(giver = 0)
        assertEquals(g, CastawaysEngine.pass(g, 1)) // no guess yet
        assertEquals(g, CastawaysEngine.pass(g, 0)) // the giver cannot pass
        val after = CastawaysEngine.pass(CastawaysEngine.guess(g, 1, 0), 1)
        assertEquals(1, after.giver)
        assertEquals(8, after.tokens)
        assertEquals(0, after.guessesThisTurn)
    }

    @Test
    fun whenTheTokensRunOutSuddenDeathBegins() {
        var g = castaways(giver = 0, tokens = 1)
        g = CastawaysEngine.guess(g, 1, 9) // empty sea on key 0: last day used
        assertEquals(0, g.tokens)
        assertEquals(CastawaysPhase.SUDDEN_DEATH, g.phase)
        assertEquals(g, CastawaysEngine.pass(g, 0)) // no passing in sudden death
    }

    @Test
    fun inSuddenDeathEitherPlayerGuessesAndTheOtherKeyJudges() {
        val sudden = castaways(tokens = 1).copy(tokens = 0, phase = CastawaysPhase.SUDDEN_DEATH)
        // player 0 guesses word 9: treasure on key 1, so correct
        val ok = CastawaysEngine.guess(sudden, 0, 9)
        assertTrue(ok.found[9])
        assertEquals(CastawaysPhase.SUDDEN_DEATH, ok.phase)
        // player 1 can guess at once, no turns: word 3 is a treasure on key 0
        assertTrue(CastawaysEngine.guess(ok, 1, 3).found[3])
        // an empty sea on the partner's key loses
        val lost = CastawaysEngine.guess(sudden, 0, 3) // word 3 is an empty sea on key 1
        assertEquals(CastawaysPhase.LOST, lost.phase)
        assertEquals(LossReason.SUDDEN_DEATH, lost.lossReason)
        assertEquals(CastawaysResult.WRONG, lost.last!!.result)
        // a kraken on the partner's key loses too
        val dead = CastawaysEngine.guess(sudden, 0, 8) // word 8 is a kraken on key 1
        assertEquals(LossReason.KRAKEN, dead.lossReason)
    }

    @Test
    fun findingAllFifteenTreasuresWins() {
        var g = castaways(giver = 0)
        for (i in 0..8) g = CastawaysEngine.guess(g, 1, i) // the 9 treasures on key 0
        assertEquals(9, g.treasuresFound)
        assertEquals(CastawaysPhase.PLAYING, g.phase)
        g = CastawaysEngine.pass(g, 1) // player 1 now gives the clues
        for (i in 9..13) g = CastawaysEngine.guess(g, 0, i)
        assertEquals(CastawaysPhase.PLAYING, g.phase)
        g = CastawaysEngine.guess(g, 0, 14) // the 15th
        assertEquals(15, g.treasuresFound)
        assertEquals(CastawaysPhase.WON, g.phase)
        assertTrue(g.over)
    }

    @Test
    fun theLastTreasureCanBeFoundInSuddenDeathToo() {
        var g = castaways(giver = 0)
        for (i in 0..8) g = CastawaysEngine.guess(g, 1, i)
        g = CastawaysEngine.pass(g, 1)
        for (i in 9..13) g = CastawaysEngine.guess(g, 0, i)
        val sudden = g.copy(tokens = 0, phase = CastawaysPhase.SUDDEN_DEATH)
        assertEquals(CastawaysPhase.WON, CastawaysEngine.guess(sudden, 0, 14).phase)
    }

    @Test
    fun everyChangeBumpsTheVersion() {
        var g = castaways()
        val seen = mutableListOf(g.seq)
        g = CastawaysEngine.guess(g, 1, 0); seen += g.seq
        g = CastawaysEngine.pass(g, 1); seen += g.seq
        g = CastawaysEngine.guess(g, 0, 9); seen += g.seq
        assertEquals(seen.sorted().distinct(), seen)
    }
}

class CastawaysViewTest {
    @Test
    fun aPlayerOnlySeesTheirOwnKeyUntilTheEnd() {
        val g = castaways()
        val v0 = CastawaysView.of(g, 0)
        val v1 = CastawaysView.of(g, 1)
        assertEquals(g.keys[0], v0.myKey)
        assertEquals(g.keys[1], v1.myKey)
        assertNull(v0.partnerKey)
        assertNull(v1.partnerKey)
        val over = CastawaysEngine.guess(g, 1, 14)
        assertEquals(over.keys[1], CastawaysView.of(over, 0).partnerKey)
        assertEquals(over.keys[0], CastawaysView.of(over, 1).partnerKey)
    }

    @Test
    fun theViewSaysWhoGivesAndWhoGuesses() {
        val g = CastawaysEngine.guess(castaways(giver = 0), 1, 0)
        assertTrue(CastawaysView.of(g, 1).iGuess)
        assertTrue(CastawaysView.of(g, 0).iGive)
        assertEquals(1, CastawaysView.of(g, 1).treasuresFound)
        assertEquals(15, CastawaysView.of(g, 1).treasuresTotal)
    }

    @Test
    fun noCountOfTheHostsHiddenTreasuresIsEverSent() {
        // a count like that would tell the other player whether a word the host found is also a treasure on the host's key
        assertFalse(CastawaysCodec.stateJson(castaways(), 1).contains("toGuess"))
    }

    @Test
    fun theWireMessageForThePartnerHasNoTraceOfTheHostsKey() {
        val g = castaways()
        val json = CastawaysCodec.stateJson(g, 1)
        assertFalse(json.contains("key0"))
        assertFalse(json.contains("\"partner\":["))
        val view = (Protocol.parse(json) as Message.CastawaysUpdate).view
        assertEquals(g.keys[1], view.myKey)
        assertNull(view.partnerKey)
    }

    @Test
    fun theViewSurvivesAJsonRoundTripInEveryPhase() {
        var g = castaways(giver = 1)
        g = CastawaysEngine.guess(g, 0, 3)       // key 1 says empty sea: marked, turn ends
        g = CastawaysEngine.guess(g, 1, 9)       // now player 0 gives: word 9 is an empty sea on key 0 ... marked
        val sudden = g.copy(tokens = 0, phase = CastawaysPhase.SUDDEN_DEATH)
        val over = CastawaysEngine.guess(sudden, 0, 3)
        for (state in listOf(castaways(), g, sudden, over)) for (p in 0..1) {
            val back = (Protocol.parse(CastawaysCodec.stateJson(state, p)) as Message.CastawaysUpdate).view
            assertEquals(CastawaysView.of(state, p), back)
        }
    }

    @Test
    fun aSavedGameComesBackExactly() {
        var g = CastawaysEngine.guess(castaways(), 1, 0)
        g = CastawaysEngine.guess(g, 1, 9)
        assertEquals(g, CastawaysCodec.load(CastawaysCodec.save(g)))
        val over = CastawaysEngine.guess(g, 0, 22)
        assertEquals(over, CastawaysCodec.load(CastawaysCodec.save(over)))
        assertNull(CastawaysCodec.load("{}"))
        assertNull(CastawaysCodec.load("nope"))
    }

    @Test
    fun malformedCastawaysMessagesAreIgnored() {
        for (bad in listOf(
            "{\"t\":\"dstate\"}", "{\"t\":\"dstate\",\"s\":{}}", "{\"t\":\"dguess\"}",
            "{\"t\":\"dguess\",\"i\":3}", // no game or version: refused
            "{\"t\":\"dpass\"}", "{\"t\":\"dstate\",\"s\":{\"me\":5}}",
        )) {
            assertNull(bad, Protocol.parse(bad))
        }
        assertEquals(Message.CastawaysGuess(7, 5L, 3), Protocol.parse(Protocol.castawaysGuess(7, 5L, 3)))
        assertEquals(Message.CastawaysPass(5L, 3), Protocol.parse(Protocol.castawaysPass(5L, 3)))
    }
}

class CastawaysSoundTest {
    private fun snap(marks: Int, phase: CastawaysPhase = CastawaysPhase.PLAYING, last: CastawaysLast? = null, id: Long = 1) = CastawaysSnapshot(id, marks, phase, last)

    @Test
    fun theCueDependsOnWhatHappened() {
        val before = snap(0)
        assertEquals(SoundCue.GOOD, castawaysCueFor(before, snap(1, last = CastawaysLast(0, CastawaysResult.TREASURE, 1))))
        assertEquals(SoundCue.NEUTRAL, castawaysCueFor(before, snap(1, last = CastawaysLast(9, CastawaysResult.EMPTY_SEA, 1))))
        assertEquals(SoundCue.KRAKEN, castawaysCueFor(before, snap(0, CastawaysPhase.LOST, CastawaysLast(14, CastawaysResult.KRAKEN, 1))))
        assertEquals(SoundCue.WIN, castawaysCueFor(before, snap(15, CastawaysPhase.WON, CastawaysLast(5, CastawaysResult.TREASURE, 1))))
        assertNull(castawaysCueFor(null, snap(3)))
        assertNull(castawaysCueFor(before, before))
        assertNull(castawaysCueFor(snap(0), snap(1, last = CastawaysLast(0, CastawaysResult.TREASURE, 0), id = 2)))
    }
}

class CastawaysSessionTest {
    private class Rig(game: CastawaysState) {
        val toGuest = mutableListOf<String>()
        val changes = mutableListOf<CastawaysState>()
        val host = CastawaysHostSession(game, { toGuest += it }, { changes += it })
        val guest = CastawaysClientSession()
        fun guestSends(text: String) = host.onMessage(text) { toGuest += it }

        /** The messages a guest phone would send while looking at the host's current position. */
        fun guessMsg(i: Int) = Protocol.castawaysGuess(i, host.game.gameId, host.game.seq)
        fun passMsg() = Protocol.castawaysPass(host.game.gameId, host.game.seq)
        fun deliver() = toGuest.toList().also { toGuest.clear() }.mapNotNull { guest.onMessage(it) }
    }

    @Test
    fun theGuestGuessesAndTheHostAnswers() {
        val r = Rig(castaways(giver = 0))
        r.guestSends(Protocol.sync())
        assertEquals(0, r.deliver().last().view.treasuresFound)

        r.guestSends(r.guessMsg(0))
        val update = r.deliver().last()
        assertEquals(SoundCue.GOOD, update.cue)
        assertTrue(update.view.found[0])
        assertEquals(1, update.view.me)
        assertEquals(1, r.changes.size)
    }

    @Test
    fun theGuestCannotGuessOutOfTurnOrPassEarly() {
        val r = Rig(castaways(giver = 1)) // the guest gives, so the guest may not guess
        r.guestSends(r.guessMsg(0))
        r.guestSends(r.passMsg())
        r.guestSends("garbage")
        assertTrue(r.toGuest.isEmpty())
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun aGuessMadeOnAnOlderPositionOrGameIsDropped() {
        val r = Rig(castaways(giver = 0))
        val old = r.guessMsg(1)                    // made while looking at seq 0
        r.guestSends(r.guessMsg(0))                // a valid guess first: the game is now at seq 1
        assertEquals(1, r.changes.size)
        r.toGuest.clear()
        r.guestSends(old)                          // the old one arrives late
        assertTrue(r.toGuest.isEmpty())
        assertEquals(1, r.changes.size)
        assertFalse(r.host.game.found[1])
        // another game with the same position number
        r.guestSends(Protocol.castawaysGuess(1, r.host.game.gameId + 1, r.host.game.seq))
        assertTrue(r.toGuest.isEmpty() && r.changes.size == 1)
        // a pass for an older position
        r.guestSends(Protocol.castawaysPass(r.host.game.gameId, r.host.game.seq - 1))
        assertTrue(r.toGuest.isEmpty() && r.changes.size == 1)
        // the current position still works
        r.guestSends(r.guessMsg(1))
        assertTrue(r.host.game.found[1])
    }

    @Test
    fun theHostsOwnGuessesReachTheGuest() {
        val r = Rig(castaways(giver = 1)) // host guesses
        r.host.guess(9) // word 9: treasure on key 1, the giver's key
        val update = r.deliver().last()
        assertTrue(update.view.found[9])
        r.host.pass()
        assertEquals(0, r.deliver().last().view.giver)
    }

    @Test
    fun aNewGameReplacesTheOldOneOnTheGuest() {
        val r = Rig(castaways())
        r.host.guess(0); r.host.pass()
        r.deliver()
        val fresh = CastawaysEngine.newGame(Lang.PT, (1..25).map { "p$it" }, 10, 77L, Random(3))
        r.host.startGame(fresh)
        val update = r.deliver().last()
        assertEquals(77L, update.view.gameId)
        assertEquals(10, update.view.maxTokens)
        assertNull(update.cue)
    }

    @Test
    fun aStaleUpdateIsIgnored() {
        val r = Rig(castaways(giver = 0))
        r.guestSends(r.guessMsg(0))
        r.guestSends(r.guessMsg(1))
        val sent = r.toGuest.toList()
        r.toGuest.clear()
        assertEquals(2, sent.size)
        assertTrue(r.guest.onMessage(sent[1]) != null)
        assertNull(r.guest.onMessage(sent[0]))
    }
}
