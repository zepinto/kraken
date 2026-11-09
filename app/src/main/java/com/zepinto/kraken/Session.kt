package com.zepinto.kraken

/**
 * The captain phone's side of the conversation. It owns the game, applies what the table phone asks for
 * and sends the new public state back. Free of Android so it can be unit tested.
 */
class HostSession(
    game: GameState,
    /** Send a message to every connected table phone. */
    private val broadcast: (String) -> Unit,
    /** Called with every new game state, to update the screen and save it. */
    private val onChange: (GameState) -> Unit,
) {
    var game: GameState = game
        private set

    /** A message arrived from a table phone; [reply] answers only that phone. */
    fun onMessage(text: String, reply: (String) -> Unit) {
        when (val m = Protocol.parse(text)) {
            is Message.Guess -> apply(Engine.reveal(game, m.index))
            Message.EndTurn -> apply(Engine.endTurn(game))
            Message.Sync -> reply(Protocol.state(game))
            else -> Unit
        }
    }

    /** The captain phone's own buttons act on the game like the table phone's do. */
    fun endTurn() = apply(Engine.endTurn(game))

    fun startGame(newGame: GameState) {
        game = newGame
        onChange(game)
        broadcast(Protocol.state(game))
    }

    private fun apply(next: GameState) {
        if (next == game) return
        game = next
        onChange(game)
        broadcast(Protocol.state(game))
    }
}

/** The table phone's side: keeps the latest public state and says which sound a new state calls for. */
class ClientSession {
    var view: BoardView? = null
        private set

    /** What a received state means for the screen. */
    data class Update(val view: BoardView, val cue: SoundCue?)

    /** Returns null when the message is not a state, or is older than the one already shown. */
    fun onMessage(text: String): Update? {
        val state = (Protocol.parse(text) as? Message.State)?.view ?: return null
        val old = view
        if (old != null && old.gameId == state.gameId && state.seq < old.seq) return null
        view = state
        return Update(state, cueFor(old?.snapshot(), state.snapshot()))
    }
}
