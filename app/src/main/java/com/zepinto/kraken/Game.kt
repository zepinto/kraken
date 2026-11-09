package com.zepinto.kraken

import kotlin.random.Random

enum class Team {
    RED, BLUE;

    val other get() = if (this == RED) BLUE else RED
}

/** What a card is on the secret key. */
enum class CardType {
    RED, BLUE, NEUTRAL, KRAKEN;

    val team: Team?
        get() = when (this) {
            RED -> Team.RED
            BLUE -> Team.BLUE
            else -> null
        }

    companion object {
        fun of(team: Team) = if (team == Team.RED) RED else BLUE
    }
}

enum class WinReason { TREASURES, KRAKEN }

/** The card revealed most recently, and the team that was guessing when it was revealed. */
data class LastReveal(val index: Int, val type: CardType, val by: Team)

/**
 * The complete game, held by the captain phone, which is the only place that knows the key.
 * Everything the table phone needs is derived from it by [BoardView.from].
 */
data class GameState(
    val gameId: Long,
    val lang: Lang,
    val words: List<String>,
    val key: List<CardType>,
    val revealed: List<Boolean>,
    val startTeam: Team,
    val turn: Team,
    val winner: Team? = null,
    val winReason: WinReason? = null,
    /** Goes up on every change, so a phone can ignore a stale message. */
    val seq: Int = 0,
    val last: LastReveal? = null,
) {
    val over get() = winner != null
    val revealedCount get() = revealed.count { it }
    fun remaining(team: Team) = key.indices.count { key[it].team == team && !revealed[it] }
}

object Engine {
    const val SIZE = 25
    const val TREASURES_FIRST = 9
    const val TREASURES_SECOND = 8
    const val NEUTRALS = 7

    /** A fresh game: the team that starts has 9 treasures, the other 8, plus 7 empty-sea cards and 1 kraken. */
    fun newGame(lang: Lang, words: List<String>, startTeam: Team, gameId: Long, rng: Random): GameState {
        require(words.size == SIZE) { "a game needs exactly $SIZE words" }
        val key = buildList {
            repeat(TREASURES_FIRST) { add(CardType.of(startTeam)) }
            repeat(TREASURES_SECOND) { add(CardType.of(startTeam.other)) }
            repeat(NEUTRALS) { add(CardType.NEUTRAL) }
            add(CardType.KRAKEN)
        }.shuffled(rng)
        return GameState(
            gameId = gameId,
            lang = lang,
            words = words,
            key = key,
            revealed = List(SIZE) { false },
            startTeam = startTeam,
            turn = startTeam,
        )
    }

    /**
     * The team in play reveals one card. Clues are spoken at the table, so the app does not count guesses.
     * - Kraken: the team in play loses at once.
     * - A team has no treasures left: that team wins, whoever uncovered the last one.
     * - Own treasure: the turn goes on until the team passes.
     * - Empty sea or the other team's treasure: the turn ends.
     */
    fun reveal(g: GameState, index: Int): GameState {
        if (g.over || index !in 0 until SIZE || g.revealed[index]) return g
        val type = g.key[index]
        val revealed = g.revealed.toMutableList().also { it[index] = true }
        val next = g.copy(revealed = revealed, seq = g.seq + 1, last = LastReveal(index, type, g.turn))

        if (type == CardType.KRAKEN) {
            return next.copy(winner = g.turn.other, winReason = WinReason.KRAKEN)
        }
        for (team in Team.values()) {
            if (next.remaining(team) == 0) return next.copy(winner = team, winReason = WinReason.TREASURES)
        }
        return if (type.team == g.turn) next else switchTurn(next)
    }

    /** The team in play passes: it is the other team's turn. */
    fun endTurn(g: GameState): GameState = if (g.over) g else switchTurn(g.copy(seq = g.seq + 1))

    private fun switchTurn(g: GameState) = g.copy(turn = g.turn.other)
}

/** Hands out words, never repeating one until the whole list has been used. */
class WordPicker(private val all: List<String>) {
    private val pool = ArrayDeque<String>()

    fun next(count: Int, rng: Random): List<String> {
        require(count <= all.size) { "word list too short" }
        val picked = mutableListOf<String>()
        while (picked.size < count) {
            if (pool.isEmpty()) pool.addAll(all.shuffled(rng))
            val word = pool.removeFirst()
            if (word !in picked) picked += word
        }
        return picked
    }
}
