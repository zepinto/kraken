package com.zepinto.kraken

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val WORDS = (1..25).map { "word$it" }

/** A game whose key is known: red treasures first, then blue, then neutrals, kraken last. */
private fun fixedGame(start: Team = Team.RED): GameState {
    val g = Engine.newGame(Lang.EN, WORDS, start, 7L, Random(1))
    val first = CardType.of(start)
    val second = CardType.of(start.other)
    val key = List(9) { first } + List(8) { second } + List(7) { CardType.NEUTRAL } + CardType.KRAKEN
    return g.copy(key = key)
}

class EngineTest {
    @Test
    fun newGameHasTheRightMixOfCards() {
        for (start in Team.values()) repeat(50) { seed ->
            val g = Engine.newGame(Lang.EN, WORDS, start, 1L, Random(seed))
            assertEquals(9, g.key.count { it == CardType.of(start) })
            assertEquals(8, g.key.count { it == CardType.of(start.other) })
            assertEquals(7, g.key.count { it == CardType.NEUTRAL })
            assertEquals(1, g.key.count { it == CardType.KRAKEN })
            assertEquals(start, g.turn)
            assertEquals(0, g.revealedCount)
            assertEquals(9, g.remaining(start))
            assertEquals(8, g.remaining(start.other))
        }
    }

    @Test
    fun ownTreasureKeepsTheTurnGoing() {
        val g = Engine.reveal(fixedGame(), 0)
        assertTrue(g.revealed[0])
        assertEquals(Team.RED, g.turn)
        assertEquals(8, g.remaining(Team.RED))
        assertEquals(LastReveal(0, CardType.RED, Team.RED), g.last)
    }

    @Test
    fun emptySeaEndsTheTurn() {
        val g = Engine.reveal(fixedGame(), 20)
        assertEquals(Team.BLUE, g.turn)
    }

    @Test
    fun theOtherTeamsTreasureEndsTheTurnAndCountsForThem() {
        val g = Engine.reveal(fixedGame(), 10) // a blue card, red guessing
        assertEquals(Team.BLUE, g.turn)
        assertEquals(7, g.remaining(Team.BLUE))
        assertEquals(9, g.remaining(Team.RED))
    }

    @Test
    fun theKrakenLosesTheGameForTheTeamThatPickedIt() {
        val g = Engine.reveal(fixedGame(), 24)
        assertEquals(Team.BLUE, g.winner)
        assertEquals(WinReason.KRAKEN, g.winReason)
        // and the same when blue picks it
        val blueTurn = Engine.endTurn(fixedGame())
        assertEquals(Team.RED, Engine.reveal(blueTurn, 24).winner)
    }

    @Test
    fun uncoveringAllOfYourTreasuresWins() {
        var g = fixedGame()
        for (i in 0 until 9) g = Engine.reveal(g, i)
        assertEquals(Team.RED, g.winner)
        assertEquals(WinReason.TREASURES, g.winReason)
    }

    @Test
    fun theOtherTeamWinsIfYouUncoverTheirLastTreasure() {
        var g = Engine.endTurn(fixedGame()) // blue to guess
        for (i in 9 until 16) g = Engine.reveal(g, i) // 7 blue treasures, blue keeps guessing
        assertEquals(Team.BLUE, g.turn)
        g = Engine.endTurn(g) // red to guess
        g = Engine.reveal(g, 16)
        assertEquals(Team.BLUE, g.winner)
    }

    @Test
    fun nothingHappensTwiceOrAfterTheEnd() {
        var g = Engine.reveal(fixedGame(), 0)
        assertEquals(g, Engine.reveal(g, 0))
        assertEquals(g, Engine.reveal(g, 99))
        assertEquals(g, Engine.reveal(g, -1))
        val over = Engine.reveal(fixedGame(), 24)
        assertEquals(over, Engine.reveal(over, 1))
        assertEquals(over, Engine.endTurn(over))
    }

    @Test
    fun aGuessNeedsNoClueBecauseCluesAreSpokenAtTheTable() {
        val g = Engine.reveal(fixedGame(), 0)
        assertTrue(g.revealed[0])
        assertEquals(Team.RED, g.turn)
    }

    @Test
    fun theTurnGoesOnForAsLongAsTheTeamFindsItsOwnTreasures() {
        var g = fixedGame()
        for (i in 0 until 8) {
            g = Engine.reveal(g, i)
            assertEquals("after treasure ${i + 1}", Team.RED, g.turn)
        }
        assertEquals(1, g.remaining(Team.RED))
        // the team stops when it wants to
        assertEquals(Team.BLUE, Engine.endTurn(g).turn)
    }

    @Test
    fun passingTheTurnTwiceGivesTheTurnBack() {
        val g = fixedGame()
        assertEquals(Team.BLUE, Engine.endTurn(g).turn)
        assertEquals(Team.RED, Engine.endTurn(Engine.endTurn(g)).turn)
    }

    @Test
    fun everyChangeBumpsTheVersion() {
        var g = fixedGame()
        val seen = mutableListOf(g.seq)
        g = Engine.reveal(g, 0); seen += g.seq
        g = Engine.reveal(g, 20); seen += g.seq // an empty sea: the turn passes
        g = Engine.endTurn(g); seen += g.seq
        assertEquals(seen.sorted().distinct(), seen)
    }
}

class ProtocolTest {
    @Test
    fun theTableViewNeverContainsTheKeyOfAHiddenCard() {
        val g = Engine.reveal(fixedGame(), 0)
        val json = Protocol.state(g)
        assertFalse("no key array on the wire", json.contains("\"key\""))
        val view = (Protocol.parse(json) as Message.State).view
        assertEquals(CardType.RED, view.shown[0])
        for (i in 1 until 25) assertNull("card $i is still hidden", view.shown[i])
    }

    @Test
    fun theWholeKeyIsSentOnceTheGameIsOver() {
        val g = Engine.reveal(fixedGame(), 24)
        val view = (Protocol.parse(Protocol.state(g)) as Message.State).view
        assertEquals(g.key, view.shown)
        assertEquals(Team.BLUE, view.winner)
    }

    @Test
    fun theViewSurvivesAJsonRoundTrip() {
        var g = Engine.reveal(fixedGame(Team.BLUE), 2) // own treasure: the turn goes on
        var back = (Protocol.parse(Protocol.state(g)) as Message.State).view
        assertEquals(BoardView.from(g), back)
        assertEquals(Team.BLUE, back.turn)

        g = Engine.reveal(g, 12) // blue picks a red treasure: the turn passes
        back = (Protocol.parse(Protocol.state(g)) as Message.State).view
        assertEquals(BoardView.from(g), back)
        assertEquals(Team.RED, back.turn)
        // key of fixedGame(BLUE): 9 blue treasures first, then 8 red; one of each was uncovered
        assertEquals(7, back.remainingRed)
        assertEquals(8, back.remainingBlue)
    }

    @Test
    fun aSavedGameComesBackExactly() {
        var g = Engine.reveal(fixedGame(), 3)
        assertEquals(g, Protocol.loadGame(Protocol.saveGame(g)))
        val over = Engine.reveal(g, 24)
        assertEquals(over, Protocol.loadGame(Protocol.saveGame(over)))
    }

    @Test
    fun garbageIsIgnoredNotCrashedOn() {
        for (bad in listOf("", "{", "[]", "{\"t\":\"nope\"}", "{\"t\":\"guess\"}", "{\"t\":\"state\",\"s\":{}}", "hello")) {
            assertNull(bad, Protocol.parse(bad))
        }
        assertNull(Protocol.loadGame("{}"))
    }

    @Test
    fun simpleMessagesRoundTrip() {
        assertEquals(Message.Guess(12), Protocol.parse(Protocol.guess(12)))
        assertEquals(Message.EndTurn, Protocol.parse(Protocol.endTurn()))
        assertEquals(Message.Sync, Protocol.parse(Protocol.sync()))
        assertEquals(Message.Ping, Protocol.parse(Protocol.ping()))
        assertEquals(Message.Welcome, Protocol.parse(Protocol.welcome()))
        assertEquals(Message.Denied("pin"), Protocol.parse(Protocol.denied("pin")))
        assertEquals(Message.Hello("1234", "Pixel", Protocol.VERSION), Protocol.parse(Protocol.hello("1234", "Pixel")))
        assertEquals(Message.Hello(null, "Pixel", Protocol.VERSION), Protocol.parse(Protocol.hello(null, "Pixel")))
    }
}

class SoundCueTest {
    private fun snap(count: Int, last: LastReveal?, winner: Team? = null, id: Long = 1) = Snapshot(id, count, winner, last)

    @Test
    fun theCueDependsOnWhatWasUncovered() {
        val before = snap(0, null)
        assertEquals(SoundCue.GOOD, cueFor(before, snap(1, LastReveal(0, CardType.RED, Team.RED))))
        assertEquals(SoundCue.BAD, cueFor(before, snap(1, LastReveal(0, CardType.BLUE, Team.RED))))
        assertEquals(SoundCue.NEUTRAL, cueFor(before, snap(1, LastReveal(0, CardType.NEUTRAL, Team.RED))))
        assertEquals(SoundCue.KRAKEN, cueFor(before, snap(1, LastReveal(0, CardType.KRAKEN, Team.RED), Team.BLUE)))
        assertEquals(SoundCue.WIN, cueFor(before, snap(1, LastReveal(0, CardType.RED, Team.RED), Team.RED)))
    }

    @Test
    fun noSoundWithoutAChange() {
        val a = snap(3, LastReveal(1, CardType.RED, Team.RED))
        assertNull(cueFor(null, a))
        assertNull(cueFor(a, a))
        assertNull(cueFor(snap(0, null), snap(1, LastReveal(0, CardType.RED, Team.RED), id = 2)))
    }
}

class SessionTest {
    /** Two phones wired together in memory. */
    private class Pair(game: GameState) {
        val toClient = mutableListOf<String>()
        val changes = mutableListOf<GameState>()
        val host = HostSession(game, { toClient += it }, { changes += it })
        val client = ClientSession()

        fun clientSends(text: String) = host.onMessage(text) { toClient += it }
        fun deliver(): List<ClientSession.Update> = toClient.toList().also { toClient.clear() }.mapNotNull { client.onMessage(it) }
    }

    @Test
    fun aGuessFromTheTableReachesTheHostAndComesBack() {
        val p = Pair(fixedGame())
        p.clientSends(Protocol.sync())
        assertEquals(0, p.deliver().last().view.revealedCount)

        p.clientSends(Protocol.guess(0))
        val updates = p.deliver()
        assertEquals(SoundCue.GOOD, updates.last().cue)
        assertTrue(p.host.game.revealed[0])
        assertEquals(p.host.game.seq, p.client.view!!.seq)
        assertEquals(1, p.changes.size)
    }

    @Test
    fun anIllegalGuessChangesNothingAndSendsNothing() {
        val p = Pair(Engine.reveal(fixedGame(), 0))
        p.clientSends(Protocol.guess(0))
        p.clientSends(Protocol.guess(77))
        p.clientSends("garbage")
        assertTrue(p.toClient.isEmpty())
        assertTrue(p.changes.isEmpty())
    }

    @Test
    fun theTableCanEndTheTurnButCannotPlantStateOrChangeTheKey() {
        val p = Pair(fixedGame())
        p.clientSends(Protocol.endTurn())
        assertEquals(Team.BLUE, p.host.game.turn)
        // a "state" or "hello" message from the table is not an order
        val before = p.host.game
        p.clientSends(Protocol.state(fixedGame(Team.BLUE)))
        p.clientSends(Protocol.hello(null, "x"))
        assertEquals(before, p.host.game)
    }

    @Test
    fun aStaleStateIsIgnored() {
        val p = Pair(fixedGame())
        p.host.endTurn()
        p.clientSends(Protocol.guess(0))
        val sent = p.toClient.toList()
        p.toClient.clear()
        // deliver the newest first, then the older one
        assertEquals(2, sent.size)
        assertTrue(p.client.onMessage(sent[1]) != null)
        assertNull(p.client.onMessage(sent[0]))
        assertEquals(p.host.game.seq, p.client.view!!.seq)
    }

    @Test
    fun aNewGameIsPickedUpEvenThoughItsVersionStartsOver() {
        val p = Pair(fixedGame())
        repeat(3) { p.host.endTurn() }
        p.deliver()
        assertTrue(p.client.view!!.seq >= 3)
        val fresh = Engine.newGame(Lang.EN, WORDS, Team.BLUE, 99L, Random(5))
        p.host.startGame(fresh)
        val update = p.deliver().last()
        assertEquals(99L, update.view.gameId)
        assertNull(update.cue)
        assertEquals(Team.BLUE, update.view.turn)
    }

    @Test
    fun reconnectingGetsTheCurrentStateWithoutASound() {
        val p = Pair(fixedGame())
        p.clientSends(Protocol.guess(0))
        p.clientSends(Protocol.guess(1))
        val late = ClientSession() // a table phone that joins after play started
        p.toClient.clear()
        p.host.onMessage(Protocol.sync()) { p.toClient += it }
        val update = late.onMessage(p.toClient.single())!!
        assertEquals(2, update.view.revealedCount)
        assertNull(update.cue)
    }
}

class WordListTest {
    private fun words(lang: Lang): List<String> {
        val f = listOf("src/main/assets/${lang.wordFile}", "app/src/main/assets/${lang.wordFile}").map(::File).first { it.exists() }
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun everyLanguageHasAGoodWordList() {
        for (lang in Lang.entries) {
            val w = words(lang)
            assertTrue("${lang.code} has ${w.size} words", w.size >= 400)
            assertEquals("${lang.code} duplicates", w.size, w.map { it.lowercase() }.toSet().size)
            w.forEach {
                assertTrue("${lang.code}: '$it' has a space", !it.contains(' '))
                assertTrue("${lang.code}: '$it' is too long for a card", it.length <= 11)
                assertEquals("${lang.code}: '$it' should be lowercase", it.lowercase(), it)
            }
        }
    }

    @Test
    fun theBoardNeverRepeatsAWordUntilTheListRunsOut() {
        val all = words(Lang.EN)
        val picker = WordPicker(all)
        val seen = mutableSetOf<String>()
        repeat(all.size / 25) {
            val board = picker.next(25, Random(it))
            assertEquals(25, board.toSet().size)
            assertTrue("a word came up again too soon", seen.addAll(board))
        }
        // after that it keeps working, with 25 distinct words every time
        repeat(5) { assertEquals(25, picker.next(25, Random(100 + it)).toSet().size) }
    }
}

class StringsTest {
    @Test
    fun everyLanguageIsComplete() {
        for (lang in Lang.entries) {
            val t = lang.strings
            assertEquals(7, t.rulesSections.size)
            t.rulesSections.forEach { assertTrue(it.title.isNotBlank() && it.lines.isNotEmpty()) }
            assertNotEquals(t.teamName(Team.RED), t.teamName(Team.BLUE))
            assertNotEquals(t.teamWins(Team.RED), t.teamWins(Team.BLUE))
            assertNotEquals(t.turnOf(Team.RED), t.turnOf(Team.BLUE))
            assertTrue(t.pairTitle("Ana").contains("Ana"))
            assertTrue(t.pairText("4821").contains("4821"))
            assertTrue(t.revealWord("sea").contains("sea"))
            assertTrue(t.treasuresLabel.isNotBlank())
            assertTrue(t.pairingCode("4821").contains("4821"))
        }
        assertEquals(Lang.PT, Lang.fromCode("pt"))
        assertNull(Lang.fromCode("fr"))
    }
}
