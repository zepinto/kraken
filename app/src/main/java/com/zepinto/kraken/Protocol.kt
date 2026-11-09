package com.zepinto.kraken

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the table phone knows: the words, which cards are face up and the state of play.
 * It never contains the key of a hidden card; the whole key is added only once the game is over.
 */
data class BoardView(
    val gameId: Long,
    val lang: Lang,
    val words: List<String>,
    val revealed: List<Boolean>,
    /** The type of each card, or null while it is still hidden. */
    val shown: List<CardType?>,
    val startTeam: Team,
    val turn: Team,
    val remainingRed: Int,
    val remainingBlue: Int,
    val winner: Team?,
    val winReason: WinReason?,
    val seq: Int,
    val last: LastReveal?,
) {
    val over get() = winner != null
    val revealedCount get() = revealed.count { it }
    fun remaining(team: Team) = if (team == Team.RED) remainingRed else remainingBlue

    companion object {
        fun from(g: GameState) = BoardView(
            gameId = g.gameId,
            lang = g.lang,
            words = g.words,
            revealed = g.revealed,
            shown = g.key.indices.map { if (g.revealed[it] || g.over) g.key[it] else null },
            startTeam = g.startTeam,
            turn = g.turn,
            remainingRed = g.remaining(Team.RED),
            remainingBlue = g.remaining(Team.BLUE),
            winner = g.winner,
            winReason = g.winReason,
            seq = g.seq,
            last = g.last,
        )
    }
}

/** A snapshot of the parts of a game that decide which sound to play. */
data class Snapshot(val gameId: Long, val revealedCount: Int, val winner: Team?, val last: LastReveal?)

fun GameState.snapshot() = Snapshot(gameId, revealedCount, winner, last)
fun BoardView.snapshot() = Snapshot(gameId, revealedCount, winner, last)

enum class SoundCue { GOOD, NEUTRAL, BAD, KRAKEN, WIN }

/** The one sound to play when the game moves from [prev] to [next], or null for none (first state, new game, no change). */
fun cueFor(prev: Snapshot?, next: Snapshot): SoundCue? {
    if (prev == null || prev.gameId != next.gameId) return null
    val newWinner = next.winner != null && prev.winner == null
    val lastReveal = next.last.takeIf { next.revealedCount > prev.revealedCount }
    return when {
        lastReveal?.type == CardType.KRAKEN -> SoundCue.KRAKEN
        newWinner -> SoundCue.WIN
        lastReveal == null -> null
        lastReveal.type == CardType.NEUTRAL -> SoundCue.NEUTRAL
        lastReveal.type.team == lastReveal.by -> SoundCue.GOOD
        else -> SoundCue.BAD
    }
}

/** Messages between the two phones, one JSON object each. */
sealed interface Message {
    /** Table phone to captain phone, first message of a Wi-Fi connection. */
    data class Hello(val pin: String?, val name: String, val version: Int) : Message
    data object Welcome : Message
    data class Denied(val reason: String) : Message
    data class Guess(val index: Int) : Message
    data object EndTurn : Message
    data object Sync : Message
    data object Ping : Message
    data class State(val view: BoardView) : Message

    /** Castaways: the second player's guess, their pass, and the host's update for them. */
    data class CastawaysGuess(val index: Int, val gameId: Long, val seq: Int) : Message
    data class CastawaysPass(val gameId: Long, val seq: Int) : Message
    data class CastawaysUpdate(val view: CastawaysView) : Message
}

object Protocol {
    const val VERSION = 1

    fun hello(pin: String?, name: String) = JSONObject().put("t", "hello").put("v", VERSION).put("name", name).apply { if (pin != null) put("pin", pin) }.toString()
    fun welcome() = JSONObject().put("t", "welcome").toString()
    fun denied(reason: String) = JSONObject().put("t", "denied").put("why", reason).toString()
    fun guess(index: Int) = JSONObject().put("t", "guess").put("i", index).toString()
    fun endTurn() = JSONObject().put("t", "end").toString()
    fun sync() = JSONObject().put("t", "sync").toString()
    fun ping() = JSONObject().put("t", "ping").toString()
    /** [gameId] and [seq] say which position of which game the player was looking at when they tapped. */
    fun castawaysGuess(index: Int, gameId: Long, seq: Int) = JSONObject().put("t", "dguess").put("i", index).put("g", gameId).put("q", seq).toString()
    fun castawaysPass(gameId: Long, seq: Int) = JSONObject().put("t", "dpass").put("g", gameId).put("q", seq).toString()

    /** The public state, as sent to the table phone. */
    fun state(g: GameState): String = JSONObject().put("t", "state").put("s", viewJson(BoardView.from(g))).toString()

    /** Returns null for anything that is not a well-formed message, so garbage on the wire is ignored. */
    fun parse(text: String): Message? = try {
        val o = JSONObject(text)
        when (o.getString("t")) {
            "hello" -> Message.Hello(o.optString("pin").ifEmpty { null }, o.optString("name"), o.optInt("v", 0))
            "welcome" -> Message.Welcome
            "denied" -> Message.Denied(o.optString("why"))
            "guess" -> Message.Guess(o.getInt("i"))
            "end" -> Message.EndTurn
            "sync" -> Message.Sync
            "ping" -> Message.Ping
            "state" -> Message.State(parseView(o.getJSONObject("s")))
            "dstate" -> Message.CastawaysUpdate(CastawaysCodec.parseView(o.getJSONObject("s")))
            "dguess" -> Message.CastawaysGuess(o.getInt("i"), o.getLong("g"), o.getInt("q"))
            "dpass" -> Message.CastawaysPass(o.getLong("g"), o.getInt("q"))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun viewJson(v: BoardView) = JSONObject()
        .put("game", v.gameId)
        .put("lang", v.lang.code)
        .put("words", JSONArray(v.words))
        .put("rev", JSONArray(v.revealed))
        .put("shown", JSONArray(v.shown.map { it?.ordinal ?: -1 }))
        .put("start", v.startTeam.ordinal)
        .put("turn", v.turn.ordinal)
        .put("left", JSONArray(listOf(v.remainingRed, v.remainingBlue)))
        .put("winner", v.winner?.ordinal ?: -1)
        .put("why", v.winReason?.ordinal ?: -1)
        .put("seq", v.seq)
        .put("last", v.last?.let { JSONObject().put("i", it.index).put("c", it.type.ordinal).put("by", it.by.ordinal) } ?: JSONObject.NULL)

    private fun parseView(o: JSONObject): BoardView {
        val words = o.getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } }
        val rev = o.getJSONArray("rev").let { a -> List(a.length()) { a.getBoolean(it) } }
        val shown = o.getJSONArray("shown").let { a -> List(a.length()) { a.getInt(it).let { n -> if (n < 0) null else CardType.values()[n] } } }
        require(words.size == Engine.SIZE && rev.size == Engine.SIZE && shown.size == Engine.SIZE) { "bad board size" }
        val left = o.getJSONArray("left")
        val winner = o.getInt("winner").takeIf { it >= 0 }?.let { Team.values()[it] }
        val why = o.getInt("why").takeIf { it >= 0 }?.let { WinReason.values()[it] }
        val last = o.optJSONObject("last")?.let { LastReveal(it.getInt("i"), CardType.values()[it.getInt("c")], Team.values()[it.getInt("by")]) }
        return BoardView(
            gameId = o.getLong("game"),
            lang = Lang.fromCode(o.getString("lang")) ?: Lang.EN,
            words = words,
            revealed = rev,
            shown = shown,
            startTeam = Team.values()[o.getInt("start")],
            turn = Team.values()[o.getInt("turn")],
            remainingRed = left.getInt(0),
            remainingBlue = left.getInt(1),
            winner = winner,
            winReason = why,
            seq = o.getInt("seq"),
            last = last,
        )
    }

    // ---- saving the captain's game, key included, so it survives the app being closed ----

    fun saveGame(g: GameState): String = JSONObject()
        .put("game", g.gameId)
        .put("lang", g.lang.code)
        .put("words", JSONArray(g.words))
        .put("key", JSONArray(g.key.map { it.ordinal }))
        .put("rev", JSONArray(g.revealed))
        .put("start", g.startTeam.ordinal)
        .put("turn", g.turn.ordinal)
        .put("winner", g.winner?.ordinal ?: -1)
        .put("why", g.winReason?.ordinal ?: -1)
        .put("seq", g.seq)
        .put("last", g.last?.let { JSONObject().put("i", it.index).put("c", it.type.ordinal).put("by", it.by.ordinal) } ?: JSONObject.NULL)
        .toString()

    fun loadGame(text: String): GameState? = try {
        val o = JSONObject(text)
        val words = o.getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } }
        val key = o.getJSONArray("key").let { a -> List(a.length()) { CardType.values()[a.getInt(it)] } }
        val rev = o.getJSONArray("rev").let { a -> List(a.length()) { a.getBoolean(it) } }
        require(words.size == Engine.SIZE && key.size == Engine.SIZE && rev.size == Engine.SIZE)
        GameState(
            gameId = o.getLong("game"),
            lang = Lang.fromCode(o.getString("lang")) ?: Lang.EN,
            words = words,
            key = key,
            revealed = rev,
            startTeam = Team.values()[o.getInt("start")],
            turn = Team.values()[o.getInt("turn")],
            winner = o.getInt("winner").takeIf { it >= 0 }?.let { Team.values()[it] },
            winReason = o.getInt("why").takeIf { it >= 0 }?.let { WinReason.values()[it] },
            seq = o.getInt("seq"),
            last = o.optJSONObject("last")?.let { LastReveal(it.getInt("i"), CardType.values()[it.getInt("c")], Team.values()[it.getInt("by")]) },
        )
    } catch (_: Exception) {
        null
    }
}
