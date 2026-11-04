package com.zepinto.codenames

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Screen { HOME, HOST, JOIN, TABLE, DUET_HOME, DUET }
enum class ClientStatus { IDLE, SEARCHING, CONNECTING, CONNECTED, LOST }
enum class JoinMessage { FAILED, WRONG_PIN, LOCKED, BAD_ADDRESS, NEARBY }

/** A phone asking to connect over Nearby; the spymaster compares [digits] with the other phone and accepts or rejects. */
class PairingRequest(
    val peerId: String,
    val peerName: String,
    val digits: String?,
    val accept: () -> Unit,
    val reject: () -> Unit,
)

data class FoundHost(val id: String, val name: String)

data class UiState(
    val lang: Lang,
    val screen: Screen = Screen.HOME,
    val musicOn: Boolean = true,
    val hasSavedGame: Boolean = false,
    val hasSavedDuet: Boolean = false,

    // Duet: this phone's view of the game, and whether this phone created it (and so holds both keys)
    val duet: DuetView? = null,
    val duetHost: Boolean = false,
    /** The join screen was opened from the Duet menu, so Back returns there. */
    val joinFromDuet: Boolean = false,

    // spymaster phone
    val game: GameState? = null,
    val tableConnected: Boolean = false,
    val pairing: PairingRequest? = null,
    val pin: String = "",
    val wifiAddresses: List<String> = emptyList(),
    val nearbyActive: Boolean = false,
    val nearbyProblem: Boolean = false,

    // table phone
    val view: BoardView? = null,
    val status: ClientStatus = ClientStatus.IDLE,
    val found: List<FoundHost> = emptyList(),
    val pairingDigits: String? = null,
    val joinMessage: JoinMessage? = null,
)

/**
 * Wires the game to the two links. The spymaster phone owns the game and answers the table phone;
 * the table phone only shows what it is told and sends guesses. Everything here runs on the main thread:
 * link callbacks are posted to it first.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val prefs = app.getSharedPreferences("codenames", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val sfx = Sfx(app)
    private val music = Music(app)
    private val pickers = HashMap<Lang, WordPicker>()

    /** What this phone is called to the other one. The random suffix keeps two phones of the same model apart. */
    private val deviceName: String = run {
        val suffix = prefs.getString(KEY_SUFFIX, null)
            ?: Random.nextInt(0x1000, 0xFFFF).toString(16).uppercase().also { prefs.edit().putString(KEY_SUFFIX, it).apply() }
        "${(Build.MODEL ?: "Phone").take(24)}-$suffix"
    }

    private fun ownLanguage() = Lang.fromCode(prefs.getString(KEY_LANG, null)) ?: Lang.deviceDefault()

    private val _state = MutableStateFlow(
        UiState(lang = ownLanguage(), hasSavedGame = loadSaved() != null, hasSavedDuet = loadSavedDuet() != null, musicOn = prefs.getBoolean(KEY_MUSIC, true))
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        music.setEnabled(_state.value.musicOn)
        viewModelScope.launch {
            _state.map { trackFor(it) }.distinctUntilChanged().collect { music.play(it) }
        }
    }

    /**
     * The lobby tune in the menus. During a game only one phone plays music, so the two phones never play the same
     * tune out of step: the table phone (the spymaster's stays quiet), or in Duet the phone that created the game.
     */
    private fun trackFor(st: UiState): Music.Track? = when (st.screen) {
        Screen.HOME, Screen.JOIN, Screen.DUET_HOME -> Music.Track.LOBBY
        Screen.TABLE -> Music.Track.GAME
        Screen.DUET -> if (st.duetHost) Music.Track.GAME else null
        Screen.HOST -> null
    }

    fun setMusic(on: Boolean) {
        _state.update { it.copy(musicOn = on) }
        music.setEnabled(on)
        prefs.edit().putBoolean(KEY_MUSIC, on).apply()
    }

    fun onPause() = music.pause()

    fun onResume() = music.resume()

    fun setLanguage(lang: Lang) {
        _state.update { it.copy(lang = lang) }
        prefs.edit().putString(KEY_LANG, lang.code).apply()
    }

    // ================= spymaster phone =================

    private var session: HostSession? = null
    private var lan: LanHostLink? = null
    private var nearbyHost: NearbyHostLink? = null
    private val peers = HashSet<String>()

    /** Phones already accepted in this session, so one that drops out and comes back is not asked about again. */
    private val trusted = HashSet<String>()
    private var hostSnapshot: Snapshot? = null
    private var duetSession: DuetHostSession? = null
    private var duetSnapshot: DuetSnapshot? = null

    private fun hostSend(text: String) {
        lan?.send(text)
        nearbyHost?.send(text)
    }

    private val hostListener = object : HostListener {
        override fun onPairingRequest(peerId: String, peerName: String, digits: String?, accept: () -> Unit, reject: () -> Unit) = post {
            if (peerName in trusted) {
                accept()
                return@post
            }
            _state.update { it.copy(pairing = PairingRequest(peerId, peerName, digits, accept = { accept() }, reject = { reject() })) }
        }

        override fun onConnected(peerId: String, peerName: String) = post {
            peers += peerId
            _state.update { it.copy(tableConnected = true, pairing = null) }
            session?.let { s -> hostSend(Protocol.state(s.game)) }
            duetSession?.let { d -> hostSend(d.guestState()) }
        }

        override fun onMessage(peerId: String, text: String) = post {
            session?.onMessage(text) { reply -> hostSend(reply) }
            duetSession?.onMessage(text) { reply -> hostSend(reply) }
        }

        override fun onDisconnected(peerId: String) = post {
            peers -= peerId
            _state.update {
                it.copy(
                    tableConnected = peers.isNotEmpty(),
                    pairing = it.pairing?.takeUnless { p -> p.peerId == peerId },
                )
            }
        }

        override fun onError(message: String) = post {
            if (message.startsWith("Nearby")) {
                // advertising did not start: say so, and let the spymaster try again
                nearbyHost?.stop()
                nearbyHost = null
                _state.update { it.copy(nearbyActive = false, nearbyProblem = true) }
            }
        }
    }

    fun createGame() {
        trusted.clear()
        startHosting(newGameState(), randomPin())
    }

    fun resumeGame() {
        val game = loadSaved() ?: return
        _state.update { it.copy(lang = game.lang) }
        // the same PIN as before, so a table phone that lost the connection can come back by itself
        startHosting(game, prefs.getString(KEY_PIN, null) ?: randomPin())
    }

    private fun randomPin() = (100_000 + Random.nextInt(900_000)).toString()

    private fun startHosting(game: GameState, pin: String) {
        stopHostLinks()
        duetSession = null
        session = HostSession(
            game,
            broadcast = { text -> lan?.send(text); nearbyHost?.send(text) },
            onChange = ::onHostGameChanged,
        )
        hostSnapshot = game.snapshot()
        save(game)
        prefs.edit().putString(KEY_PIN, pin).apply()
        val link = LanHostLink(pin)
        lan = link
        link.start(hostListener)
        _state.update {
            it.copy(
                screen = Screen.HOST,
                game = game,
                tableConnected = false,
                pairing = null,
                pin = pin,
                wifiAddresses = if (link.port == 0) emptyList() else localAddresses().map { a -> "$a:${link.port}" },
                nearbyActive = false,
                nearbyProblem = false,
            )
        }
        if (NearbyPermissions.granted(appContext)) startNearbyHost()
    }

    /** Called once the permissions are granted. */
    fun startNearbyHost() {
        if (nearbyHost != null || (session == null && duetSession == null)) return
        val link = NearbyHostLink(appContext, deviceName)
        nearbyHost = link
        link.start(hostListener)
        _state.update { it.copy(nearbyActive = true, nearbyProblem = false) }
    }

    fun nearbyDenied() = _state.update { it.copy(nearbyProblem = true) }

    private fun onHostGameChanged(g: GameState) {
        save(g)
        cueFor(hostSnapshot, g.snapshot())?.let(sfx::play)
        hostSnapshot = g.snapshot()
        _state.update { it.copy(game = g) }
    }

    fun hostEndTurn() {
        session?.endTurn()
    }

    fun newGame() {
        session?.startGame(newGameState())
    }

    fun acceptPairing() {
        val request = _state.value.pairing ?: return
        trusted += request.peerName
        request.accept()
        _state.update { it.copy(pairing = null) }
    }

    fun rejectPairing() {
        _state.value.pairing?.reject?.invoke()
        _state.update { it.copy(pairing = null) }
    }

    fun leaveHost() {
        stopHostLinks()
        session = null
        duetSession = null
        _state.update {
            it.copy(
                screen = Screen.HOME, game = null, duet = null, duetHost = false, tableConnected = false, pairing = null,
                hasSavedGame = loadSaved() != null, hasSavedDuet = loadSavedDuet() != null,
            )
        }
    }

    // ================= Duet: the phone that creates the game =================

    fun openDuetHome() {
        _state.update { it.copy(screen = Screen.DUET_HOME, hasSavedDuet = loadSavedDuet() != null) }
    }

    fun leaveDuetHome() {
        _state.update { it.copy(screen = Screen.HOME) }
    }

    fun createDuet(turns: Int) {
        trusted.clear()
        startDuetHosting(newDuetState(turns), randomPin())
    }

    fun resumeDuet() {
        val game = loadSavedDuet() ?: return
        _state.update { it.copy(lang = game.lang) }
        startDuetHosting(game, prefs.getString(KEY_PIN, null) ?: randomPin())
    }

    private fun startDuetHosting(game: DuetState, pin: String) {
        stopHostLinks()
        session = null
        duetSession = DuetHostSession(game, sendToGuest = ::hostSend, onChange = ::onDuetChanged)
        duetSnapshot = game.snapshot()
        saveDuet(game)
        prefs.edit().putString(KEY_PIN, pin).apply()
        val link = LanHostLink(pin)
        lan = link
        link.start(hostListener)
        _state.update {
            it.copy(
                screen = Screen.DUET,
                game = null,
                duet = DuetView.of(game, 0),
                duetHost = true,
                tableConnected = false,
                pairing = null,
                pin = pin,
                wifiAddresses = if (link.port == 0) emptyList() else localAddresses().map { a -> "$a:${link.port}" },
                nearbyActive = false,
                nearbyProblem = false,
            )
        }
        if (NearbyPermissions.granted(appContext)) startNearbyHost()
    }

    private fun onDuetChanged(g: DuetState) {
        saveDuet(g)
        duetCueFor(duetSnapshot, g.snapshot())?.let(sfx::play)
        duetSnapshot = g.snapshot()
        _state.update { it.copy(duet = DuetView.of(g, 0)) }
    }

    /** A new Duet game with the same number of turns as the one just played. */
    fun duetNewGame() {
        val turns = duetSession?.game?.maxTokens ?: DuetEngine.STANDARD_TURNS
        duetSession?.startGame(newDuetState(turns))
    }

    /** The player taps a word and confirms: this phone applies it itself, the other phone asks the host. */
    fun duetGuess(index: Int) {
        val st = _state.value
        val view = st.duet ?: return
        if (st.duetHost) duetSession?.guess(index) else client?.send(Protocol.duetGuess(index, view.gameId, view.seq))
    }

    fun duetPass() {
        val st = _state.value
        val view = st.duet ?: return
        if (st.duetHost) duetSession?.pass() else client?.send(Protocol.duetPass(view.gameId, view.seq))
    }

    private fun newDuetState(turns: Int): DuetState {
        val lang = _state.value.lang
        val picker = pickers.getOrPut(lang) { WordPicker(readWords(lang)) }
        return DuetEngine.newGame(lang, picker.next(DuetEngine.SIZE, Random.Default), turns, System.currentTimeMillis(), Random.Default)
    }

    /** A finished game is not worth resuming. */
    private fun saveDuet(g: DuetState) {
        if (g.over) prefs.edit().remove(KEY_DUET).apply() else prefs.edit().putString(KEY_DUET, DuetCodec.save(g)).apply()
    }

    private fun loadSavedDuet(): DuetState? = prefs.getString(KEY_DUET, null)?.let { DuetCodec.load(it) }

    private fun stopHostLinks() {
        lan?.stop()
        nearbyHost?.stop()
        lan = null
        nearbyHost = null
        peers.clear()
    }

    private fun newGameState(): GameState {
        val lang = _state.value.lang
        val picker = pickers.getOrPut(lang) { WordPicker(readWords(lang)) }
        return Engine.newGame(lang, picker.next(Engine.SIZE, Random.Default), Team.values().random(), System.currentTimeMillis(), Random.Default)
    }

    private fun readWords(lang: Lang): List<String> =
        appContext.assets.open(lang.wordFile).bufferedReader().use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() } }

    /** A finished game is not worth resuming. */
    private fun save(g: GameState) {
        if (g.over) prefs.edit().remove(KEY_GAME).apply() else prefs.edit().putString(KEY_GAME, Protocol.saveGame(g)).apply()
    }

    private fun loadSaved(): GameState? = prefs.getString(KEY_GAME, null)?.let { Protocol.loadGame(it) }

    // ================= table phone =================

    private var client: ClientLink? = null
    private var clientSession = ClientSession()
    private var duetClient = DuetClientSession()

    /** True while this phone is the guest in a game (classic table phone or the second Duet player). */
    private fun guestPlaying(st: UiState) = st.screen == Screen.TABLE || (st.screen == Screen.DUET && !st.duetHost)
    private var wifiTarget: Pair<String, String?>? = null
    private var reconnectName: String? = null
    private var nearbyStartedAt = 0L

    private val retry = Runnable { reconnect() }

    /**
     * Listens to one particular link. Events from a link that has been replaced or stopped are dropped,
     * so a late callback can never change the state of the link that took its place.
     */
    private inner class Bound(private val link: ClientLink) : ClientListener {
        override fun onFound(id: String, name: String) = post {
            if (client !== link) return@post
            _state.update { s -> s.copy(found = s.found.filter { it.id != id } + FoundHost(id, name)) }
            // after a lost connection, go back to the same spymaster phone by itself
            val st = _state.value
            if (guestPlaying(st) && st.status == ClientStatus.LOST && name == reconnectName) connectNearby(id, name)
        }

        override fun onLost(id: String) = post {
            if (client !== link) return@post
            _state.update { s -> s.copy(found = s.found.filter { it.id != id }) }
        }

        override fun onPairing(digits: String?) = post {
            if (client !== link) return@post
            _state.update { it.copy(pairingDigits = digits) }
        }

        override fun onConnected() = post {
            if (client !== link) return@post
            main.removeCallbacks(retry)
            clientSession = ClientSession()
            duetClient = DuetClientSession()
            // The game that arrives decides what is shown: the classic table phone or the Duet screen.
            _state.update {
                it.copy(
                    status = ClientStatus.CONNECTED, pairingDigits = null, joinMessage = null,
                    screen = if (it.screen == Screen.DUET) Screen.DUET else Screen.TABLE,
                )
            }
            link.send(Protocol.sync())
        }

        override fun onMessage(text: String) = post {
            if (client !== link) return@post
            duetClient.onMessage(text)?.let { duet ->
                duet.cue?.let(sfx::play)
                _state.update { it.copy(duet = duet.view, duetHost = false, view = null, screen = Screen.DUET, lang = duet.view.lang) }
                return@post
            }
            val update = clientSession.onMessage(text) ?: return@post
            update.cue?.let(sfx::play)
            // The table plays in the language of the game it joined, without changing its own setting.
            _state.update { it.copy(view = update.view, lang = update.view.lang) }
        }

        override fun onDisconnected(reason: String) = post {
            if (client !== link) return@post
            _state.update { it.copy(status = ClientStatus.LOST, pairingDigits = null) }
            scheduleReconnect()
        }

        override fun onError(kind: LinkError, detail: String) = post {
            if (client !== link) return@post
            if (guestPlaying(_state.value)) {
                if (kind == LinkError.DENIED && (detail == "pin" || detail == "locked")) {
                    // a different game is hosted now: do not keep retrying with a PIN that will never work
                    stopClient()
                    _state.update {
                        it.copy(
                            screen = Screen.JOIN, view = null, duet = null, status = ClientStatus.IDLE, found = emptyList(),
                            lang = ownLanguage(), joinMessage = if (detail == "locked") JoinMessage.LOCKED else JoinMessage.WRONG_PIN,
                        )
                    }
                    return@post
                }
                _state.update { it.copy(status = ClientStatus.LOST) }
                scheduleReconnect()
                return@post
            }
            val message = when {
                detail.startsWith("Nearby") -> JoinMessage.NEARBY
                kind == LinkError.DENIED && detail == "pin" -> JoinMessage.WRONG_PIN
                kind == LinkError.DENIED && detail == "locked" -> JoinMessage.LOCKED
                detail == "address" -> JoinMessage.BAD_ADDRESS
                else -> JoinMessage.FAILED
            }
            _state.update {
                it.copy(
                    status = if (it.found.isEmpty() || message == JoinMessage.NEARBY) ClientStatus.IDLE else ClientStatus.SEARCHING,
                    joinMessage = message,
                    pairingDigits = null,
                )
            }
        }
    }

    fun openJoin(fromDuet: Boolean = false) {
        stopClient()
        _state.update { it.copy(joinFromDuet = fromDuet, screen = Screen.JOIN, status = ClientStatus.IDLE, found = emptyList(), pairingDigits = null, joinMessage = null, view = null, duet = null) }
        if (NearbyPermissions.granted(appContext)) startNearbySearch()
    }

    fun startNearbySearch() {
        stopClient()
        val link = NearbyClientLink(appContext, deviceName)
        client = link
        nearbyStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = ClientStatus.SEARCHING, found = emptyList(), joinMessage = null) }
        link.start(Bound(link))
    }

    fun nearbyDeniedOnTable() = _state.update { it.copy(joinMessage = JoinMessage.NEARBY) }

    fun connectNearby(id: String, name: String) {
        reconnectName = name
        wifiTarget = null
        _state.update { it.copy(status = ClientStatus.CONNECTING, joinMessage = null) }
        client?.connect(id, null)
    }

    fun connectWifi(address: String, pin: String) {
        stopClient()
        if (parseAddress(address) == null) {
            _state.update { it.copy(joinMessage = JoinMessage.BAD_ADDRESS, status = ClientStatus.IDLE, found = emptyList()) }
            return
        }
        wifiTarget = address to pin.trim().ifEmpty { null }
        reconnectName = null
        val link = LanClientLink(deviceName)
        client = link
        link.start(Bound(link))
        // the Nearby search is over, so the hosts it had found cannot be connected to any more
        _state.update { it.copy(status = ClientStatus.CONNECTING, joinMessage = null, found = emptyList()) }
        link.connect(address, wifiTarget?.second)
    }

    /** The connection dropped during a game: keep trying until it is back. */
    private fun scheduleReconnect() {
        main.removeCallbacks(retry)
        main.postDelayed(retry, RECONNECT_MS)
    }

    private fun reconnect() {
        val st = _state.value
        if (!guestPlaying(st) || st.status != ClientStatus.LOST) return
        val wifi = wifiTarget
        if (wifi != null) {
            client?.stop()
            val fresh = LanClientLink(deviceName)
            client = fresh
            fresh.start(Bound(fresh))
            fresh.connect(wifi.first, wifi.second)
        } else if (SystemClock.elapsedRealtime() - nearbyStartedAt >= NEARBY_RESTART_MS) {
            // Look for the same spymaster phone again; onFound reconnects when it shows up.
            // Bluetooth discovery can take several seconds, so a search is left running and only restarted now and then.
            val link = NearbyClientLink(appContext, deviceName)
            client?.stop()
            client = link
            nearbyStartedAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(found = emptyList()) }
            link.start(Bound(link))
        }
        scheduleReconnect()
    }

    fun guess(index: Int) {
        client?.send(Protocol.guess(index))
    }

    fun tableEndTurn() {
        client?.send(Protocol.endTurn())
    }

    fun leaveTable() {
        stopClient()
        _state.update {
            it.copy(
                screen = if (it.screen == Screen.JOIN && it.joinFromDuet) Screen.DUET_HOME else Screen.HOME,
                view = null, duet = null, status = ClientStatus.IDLE, found = emptyList(),
                pairingDigits = null, joinMessage = null, lang = ownLanguage(),
            )
        }
    }

    private fun stopClient() {
        main.removeCallbacks(retry)
        val old = client
        client = null
        old?.stop()
        clientSession = ClientSession()
        duetClient = DuetClientSession()
        wifiTarget = null
        reconnectName = null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    override fun onCleared() {
        stopHostLinks()
        stopClient()
        sfx.release()
        music.release()
    }

    private companion object {
        const val KEY_LANG = "lang"
        const val KEY_MUSIC = "music"
        const val KEY_GAME = "game"
        const val KEY_DUET = "duet_game"
        const val KEY_PIN = "pin"
        const val KEY_SUFFIX = "device_suffix"
        const val RECONNECT_MS = 3_000L
        const val NEARBY_RESTART_MS = 20_000L
    }
}
