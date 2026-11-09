package com.zepinto.kraken

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Castaways menu: how many turns, then create a game on this phone or join the one on the partner's phone. */
@Composable
internal fun CastawaysHomeScreen(
    state: UiState,
    onBack: () -> Unit,
    onCreate: (Int) -> Unit,
    onJoin: () -> Unit,
    onResume: () -> Unit,
    onRules: () -> Unit,
) {
    val t = LocalStrings.current
    var turns by rememberSaveable { mutableIntStateOf(CastawaysEngine.STANDARD_TURNS) }
    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← ${t.back}", color = Palette.Aqua, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            HelpButton(onRules)
        }
        Text("🏝️ ${t.castawaysTitle}", fontSize = 32.sp, fontWeight = FontWeight.Black)
        Text(t.castawaysDesc, color = Palette.Lilac)

        Surface(shape = Soft, color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⏳ ${t.castawaysTurnsTitle}", fontWeight = FontWeight.Black, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(9, 10, 11).forEach { n -> TurnChip(n, turns == n) { turns = n } }
                }
                Text(t.castawaysTurnsHint, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
        RoleCard("🆕", t.castawaysCreateTitle, t.castawaysCreateDesc, Palette.Treasure) { onCreate(turns) }
        RoleCard("🔗", t.castawaysJoinTitle, t.castawaysJoinDesc, Palette.Blue, onJoin)
        if (state.hasSavedCastaways) PillButton(t.resumeGame, onResume, Modifier.fillMaxWidth(), color = Palette.Aqua)
    }
}

@Composable
private fun TurnChip(n: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) Palette.Sun else Color.White.copy(alpha = 0.12f),
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("$n", fontWeight = FontWeight.Black, fontSize = 20.sp, color = if (selected) Palette.Ink else Color.White)
        }
    }
}

/**
 * One Castaways player's screen. Every phone is both the board and that player's key: the words, this player's own
 * key colours, what has been found, and the words already ruled out. The buttons to confirm a guess or pass the
 * turn stay at the bottom of the screen; everything else scrolls above them.
 */
@Composable
internal fun CastawaysScreen(
    state: UiState,
    onGuess: (Int) -> Unit,
    onPass: () -> Unit,
    onNewGame: () -> Unit,
    onAllowNearby: () -> Unit,
    onRules: () -> Unit,
) {
    val t = LocalStrings.current
    val v = state.castaways ?: return
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var keyVisible by rememberSaveable { mutableStateOf(true) }
    // an update from the other phone makes a half-made choice stale
    LaunchedEffect(v.seq, v.gameId) { selected = -1 }
    val connected = state.castawaysHost || state.status == ClientStatus.CONNECTED
    val sudden = v.phase == CastawaysPhase.SUDDEN_DEATH
    val guessing = !v.over && (sudden || v.iGuess)

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CastawaysBanner(v) }
                HelpButton(onRules)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile("⏳", t.castawaysTurnsLeft(v.tokens), Modifier.weight(1f))
                InfoTile("💰", t.castawaysTreasures(v.treasuresFound, v.treasuresTotal), Modifier.weight(1f))
            }
            if (state.castawaysHost) {
                Chip(
                    if (state.tableConnected) "🟢 ${t.castawaysPartnerConnected}" else "🟠 ${t.castawaysPartnerWaiting}",
                    if (state.tableConnected) Palette.Mint else Palette.Sun,
                )
            } else if (state.status != ClientStatus.CONNECTED) {
                Surface(shape = Soft, color = Palette.Sun.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Sun, strokeWidth = 2.5.dp)
                        Text(t.connectionLost, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // what to do now
            when {
                v.over -> Unit
                sudden -> {
                    Text(t.castawaysSuddenHint, color = Palette.Sun, fontWeight = FontWeight.Bold)
                    Text(t.castawaysToFind(v.treasuresTotal - v.treasuresFound), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                v.iGive -> Text(t.castawaysGiveHint, color = Color.White.copy(alpha = 0.85f))
                else -> Text(t.castawaysGuessHint, color = Color.White.copy(alpha = 0.85f))
            }

            Grid { i, mod ->
                val mine = v.myKey[i]
                val found = v.found[i]
                // Ruled out: an empty sea already found for the key that will decide the next guess. It cannot be tapped.
                val ruledOut = if (sudden) v.tan[1 - v.me][i] else v.tan[v.giver][i]
                // Marked on the other key only: just information, still a fair guess.
                val markedOther = !found && !ruledOut && (v.tan[0][i] || v.tan[1][i])
                val canGuess = guessing && !found && !ruledOut
                val fatal = v.phase == CastawaysPhase.LOST && v.last?.index == i
                val fill = when {
                    found -> Palette.Treasure
                    keyVisible -> when (mine) {
                        CastawaysCard.TREASURE -> Palette.TreasureDeep
                        CastawaysCard.EMPTY_SEA -> Palette.Sand
                        CastawaysCard.KRAKEN -> Palette.Kraken
                    }
                    else -> Palette.Paper
                }
                val lightText = found || (keyVisible && mine != CastawaysCard.EMPTY_SEA)
                WordCell(
                    word = v.words[i],
                    modifier = mod,
                    fill = fill,
                    textColor = if (lightText) Color.White else Palette.PaperText,
                    // stripes mark your own treasures, so they can be told from empty-sea cards without colour
                    pattern = if (!found && keyVisible && mine == CastawaysCard.TREASURE) CardType.RED else null,
                    dim = if (found) 0.9f else if (ruledOut) 0.6f else 1f,
                    selected = selected == i,
                    skull = !found && keyVisible && mine == CastawaysCard.KRAKEN,
                    badge = if (found) "✓" else if (ruledOut) "✕" else if (markedOther) "•" else null,
                    ring = if (fatal) Palette.Red else null,
                    onClick = if (canGuess) ({ selected = if (selected == i) -1 else i }) else null,
                )
            }

            PillButton(
                if (keyVisible) t.hideKey else t.showKey,
                { keyVisible = !keyVisible },
                Modifier.fillMaxWidth(),
                color = Palette.Lilac,
            )

            if (state.castawaysHost) {
                if (!state.tableConnected) ConnectPanel(state, onAllowNearby, castaways = true)
                PillButton(
                    t.newGame,
                    onNewGame,
                    Modifier.fillMaxWidth(),
                    color = if (v.over) Palette.Sun else Color.Transparent,
                    content = if (v.over) Palette.Ink else Palette.Aqua,
                )
            } else if (v.over) {
                Text(t.waitingNewGame, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        // always in view: choose a word, confirm, pass
        if (guessing) {
            val pick = selected.takeIf { it in 0 until CastawaysEngine.SIZE }
            PillButton(
                if (pick != null) t.castawaysGuess(v.words[pick].uppercase()) else t.tapAWord,
                { pick?.let { onGuess(it) }; selected = -1 },
                Modifier.fillMaxWidth(),
                enabled = pick != null && connected,
                color = if (sudden) Palette.Red else Palette.Treasure,
                content = Color.White,
            )
            if (!sudden) {
                PillButton(
                    t.endTurn,
                    onPass,
                    Modifier.fillMaxWidth(),
                    enabled = v.guessesThisTurn >= 1 && connected,
                    color = Color.White.copy(alpha = 0.16f),
                    content = Color.White,
                )
                if (v.guessesThisTurn < 1) {
                    Text(t.castawaysNeedGuess, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun InfoTile(emoji: String, text: String, modifier: Modifier = Modifier) {
    Surface(shape = Soft, color = Color.White.copy(alpha = 0.1f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Whose job it is now, or how the game ended. */
@Composable
private fun CastawaysBanner(v: CastawaysView) {
    val t = LocalStrings.current
    val (color, text) = when {
        v.phase == CastawaysPhase.WON -> Palette.Treasure to "🏆 ${t.castawaysWon}"
        v.phase == CastawaysPhase.LOST ->
            Palette.Red to "🐙 " + if (v.lossReason == LossReason.KRAKEN) t.castawaysLostKraken else t.castawaysLostSudden
        v.phase == CastawaysPhase.SUDDEN_DEATH -> Palette.Red to "⚠️ ${t.castawaysSuddenDeath}"
        v.iGive -> Palette.Sun to "🗣️ ${t.castawaysYouGive}"
        else -> Palette.Blue to "👆 ${t.castawaysYouGuess}"
    }
    Surface(shape = Soft, color = color, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = if (v.phase == CastawaysPhase.PLAYING && v.iGive) Palette.Ink else Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
