package com.zepinto.kraken

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun KrakenApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = state.lang.strings
    var showRules by rememberSaveable { mutableStateOf(false) }
    var rulesCastaways by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }
    var confirmCastawaysNew by remember { mutableStateOf(false) }

    val hostPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) vm.startNearbyHost() else vm.nearbyDenied()
    }
    val joinPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) vm.startNearbySearch() else vm.nearbyDeniedOnTable()
    }

    // the music stops while the app is in the background
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.onPause()
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.screen != Screen.HOME) {
        when (state.screen) {
            Screen.JOIN -> vm.leaveTable()
            Screen.CASTAWAYS_HOME -> vm.leaveCastawaysHome()
            else -> confirmLeave = true
        }
    }
    // declared last so it wins: Back closes the rules page first
    BackHandler(enabled = showRules) { showRules = false }

    CompositionLocalProvider(LocalStrings provides t) {
        if (showRules) {
            RulesScreen(initialCastaways = rulesCastaways, onBack = { showRules = false })
        } else {
            when (state.screen) {
                Screen.HOME -> HomeScreen(
                    state = state,
                    onCaptain = vm::createGame,
                    onTable = { vm.openJoin() },
                    onResume = vm::resumeGame,
                    onCastaways = vm::openCastawaysHome,
                    onResumeCastaways = vm::resumeCastaways,
                    onLanguage = vm::setLanguage,
                    onRules = { rulesCastaways = false; showRules = true },
                    onMusic = { vm.setMusic(!state.musicOn) },
                )
                Screen.HOST -> HostScreen(
                    state = state,
                    onEndTurn = vm::hostEndTurn,
                    onNewGame = { confirmNewGame = true },
                    onAllowNearby = { hostPermission.launch(NearbyPermissions.required()) },
                    onRules = { rulesCastaways = false; showRules = true },
                )
                Screen.JOIN -> JoinScreen(
                    state = state,
                    onBack = vm::leaveTable,
                    onAllowNearby = { joinPermission.launch(NearbyPermissions.required()) },
                    onNearby = vm::connectNearby,
                    onWifi = vm::connectWifi,
                )
                Screen.CASTAWAYS_HOME -> CastawaysHomeScreen(
                    state = state,
                    onBack = vm::leaveCastawaysHome,
                    onCreate = vm::createCastaways,
                    onJoin = { vm.openJoin(fromCastaways = true) },
                    onResume = vm::resumeCastaways,
                    onRules = { rulesCastaways = true; showRules = true },
                )
                Screen.CASTAWAYS -> CastawaysScreen(
                    state = state,
                    onGuess = vm::castawaysGuess,
                    onPass = vm::castawaysPass,
                    onNewGame = { confirmCastawaysNew = true },
                    onAllowNearby = { hostPermission.launch(NearbyPermissions.required()) },
                    onRules = { rulesCastaways = true; showRules = true },
                )
                Screen.TABLE -> TableScreen(
                    state = state,
                    onGuess = vm::guess,
                    onEndTurn = vm::tableEndTurn,
                    onRules = { rulesCastaways = false; showRules = true },
                )
            }
        }

        state.pairing?.let { p ->
            AlertDialog(
                onDismissRequest = { vm.rejectPairing() },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(t.pairTitle(p.peerName)) },
                text = { p.digits?.let { Text(t.pairText(it), fontSize = 18.sp) } },
                confirmButton = { TextButton(onClick = { vm.acceptPairing() }) { Text(t.accept) } },
                dismissButton = { TextButton(onClick = { vm.rejectPairing() }) { Text(t.reject) } },
            )
        }
        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text(t.leaveTitle) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = false
                        if (state.screen == Screen.HOST || (state.screen == Screen.CASTAWAYS && state.castawaysHost)) vm.leaveHost() else vm.leaveTable()
                    }) { Text(t.leave) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(t.stay) } },
            )
        }
        if (confirmCastawaysNew) {
            AlertDialog(
                onDismissRequest = { confirmCastawaysNew = false },
                title = { Text(t.newGameTitle) },
                text = { Text(t.newGameText) },
                confirmButton = { TextButton(onClick = { confirmCastawaysNew = false; vm.castawaysNewGame() }) { Text(t.newGame) } },
                dismissButton = { TextButton(onClick = { confirmCastawaysNew = false }) { Text(t.cancel) } },
            )
        }
        if (confirmNewGame) {
            AlertDialog(
                onDismissRequest = { confirmNewGame = false },
                title = { Text(t.newGameTitle) },
                text = { Text(t.newGameText) },
                confirmButton = { TextButton(onClick = { confirmNewGame = false; vm.newGame() }) { Text(t.newGame) } },
                dismissButton = { TextButton(onClick = { confirmNewGame = false }) { Text(t.cancel) } },
            )
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo_kraken), contentDescription = null, modifier = modifier)
}

/** Music on or off. The sound effects stay on: they are part of the game. */
@Composable
internal fun MusicButton(on: Boolean, label: String, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = if (on) 0.2f else 0.06f),
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (on) "🎵" else "🔇", fontSize = 20.sp)
        }
    }
}

@Composable
internal fun HelpButton(onClick: () -> Unit) {
    val t = LocalStrings.current
    Surface(
        shape = CircleShape,
        color = Palette.Aqua.copy(alpha = 0.2f),
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = t.rulesButton }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("?", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.Aqua)
        }
    }
}

@Composable
private fun TeamTile(team: Team, remaining: Int, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    Surface(shape = Soft, color = Palette.team(team).copy(alpha = 0.3f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$remaining", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(t.teamName(team), fontWeight = FontWeight.Bold)
                Text(t.treasuresLabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** The whose-turn banner, or the winner once the game is over. */
@Composable
private fun TurnBanner(turn: Team, winner: Team?, reason: WinReason?) {
    val t = LocalStrings.current
    val team = winner ?: turn
    Surface(shape = Soft, color = Palette.team(team), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (winner == null) {
                Text(t.turnOf(turn), fontWeight = FontWeight.Black, fontSize = 20.sp)
            } else if (reason == WinReason.KRAKEN) {
                Text("🐙 " + t.krakenHit(winner.other), fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
                Text("🏆 " + t.teamWins(winner), fontWeight = FontWeight.Black, fontSize = 20.sp)
            } else {
                Text("🏆 " + t.teamWins(winner), fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
        }
    }
}

/** One card of the 5 x 5 grid. */
@Composable
internal fun WordCell(
    word: String,
    modifier: Modifier = Modifier,
    fill: Color,
    textColor: Color,
    pattern: CardType? = null,
    dim: Float = 1f,
    selected: Boolean = false,
    skull: Boolean = false,
    badge: String? = null,
    ring: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        modifier
            .aspectRatio(0.92f)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(dim)
            .background(fill)
            .typePattern(pattern)
            .then(if (ring != null) Modifier.border(4.dp, ring, shape) else if (selected) Modifier.border(3.dp, Palette.Aqua, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Fit the word to the width of the cell, and undo the user's font scale so a large setting cannot make it overflow.
        val fontScale = LocalDensity.current.fontScale
        val fitted = (minOf(13f, (maxWidth.value - 6f) / (word.length * 0.72f)) / fontScale).coerceAtLeast(5f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (skull) Text("🐙", fontSize = 12.sp, color = Color.White)
            Text(
                word.uppercase(),
                color = textColor,
                fontSize = fitted.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp),
            )
        }
    }
}

@Composable
internal fun Grid(cell: @Composable (Int, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 5) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 5) cell(row * 5 + col, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(
    state: UiState,
    onCaptain: () -> Unit,
    onTable: () -> Unit,
    onResume: () -> Unit,
    onCastaways: () -> Unit,
    onResumeCastaways: () -> Unit,
    onLanguage: (Lang) -> Unit,
    onRules: () -> Unit,
    onMusic: () -> Unit,
) {
    val t = LocalStrings.current
    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(Modifier.size(76.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("KRAKEN", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(t.tagline, color = Palette.Lilac, fontSize = 14.sp)
            }
            MusicButton(state.musicOn, t.musicButton, Palette.Aqua, onMusic)
            Spacer(Modifier.width(8.dp))
            HelpButton(onRules)
        }
        Spacer(Modifier.height(16.dp))
        LanguagePicker(state.lang, onLanguage)
        Spacer(Modifier.height(20.dp))
        Text(t.classicLabel, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Palette.Lilac)
        Spacer(Modifier.height(8.dp))
        RoleCard("🏴‍☠️", t.captainTitle, t.captainDesc, Palette.Red, onCaptain)
        Spacer(Modifier.height(14.dp))
        RoleCard("🧭", t.tableTitle, t.tableDesc, Palette.Blue, onTable)
        if (state.hasSavedGame) {
            Spacer(Modifier.height(14.dp))
            PillButton(t.resumeGame, onResume, Modifier.fillMaxWidth(), color = Palette.Aqua)
        }
        Spacer(Modifier.height(22.dp))
        Text(t.castawaysLabel, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Palette.Lilac)
        Spacer(Modifier.height(8.dp))
        RoleCard("🏝️", t.castawaysTitle, t.castawaysDesc, Palette.Treasure, onCastaways)
        if (state.hasSavedCastaways) {
            Spacer(Modifier.height(14.dp))
            PillButton(t.resumeGame, onResumeCastaways, Modifier.fillMaxWidth(), color = Palette.Aqua)
        }
        Spacer(Modifier.height(24.dp))
        Text(t.twoPhonesHint, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun RoleCard(emoji: String, title: String, desc: String, color: Color, onClick: () -> Unit) {
    Surface(
        shape = Soft,
        color = color.copy(alpha = 0.25f),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, color, Soft)
            .clip(Soft)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 40.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(2.dp))
                Text(desc, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
internal fun LanguagePicker(selected: Lang, onSelect: (Lang) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Lang.entries.forEach { lang ->
            val on = lang == selected
            Surface(
                shape = Pill,
                color = if (on) Palette.Aqua.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                modifier = Modifier
                    .weight(1f)
                    .border(2.dp, if (on) Palette.Aqua else Color.Transparent, Pill)
                    .clip(Pill)
                    .clickable { onSelect(lang) },
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.flag, fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(lang.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- captain phone

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostScreen(
    state: UiState,
    onEndTurn: () -> Unit,
    onNewGame: () -> Unit,
    onAllowNearby: () -> Unit,
    onRules: () -> Unit,
) {
    val t = LocalStrings.current
    val g = state.game ?: return
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    // Captains look at the key only when they choose to: it hides again when the turn changes or the app is left.
    LaunchedEffect(g.turn, g.gameId) { keyVisible = false }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) keyVisible = false }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TurnBanner(g.turn, g.winner, g.winReason) }
            HelpButton(onRules)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamTile(Team.RED, g.remaining(Team.RED), Modifier.weight(1f))
            TeamTile(Team.BLUE, g.remaining(Team.BLUE), Modifier.weight(1f))
        }
        Chip(
            if (state.tableConnected) "🟢 ${t.tableConnected}" else "🟠 ${t.tableWaiting}",
            if (state.tableConnected) Palette.Mint else Palette.Sun,
        )

        if (keyVisible || g.over) {
            KeyGrid(g)
            if (!g.over) PillButton(t.hideKey, { keyVisible = false }, Modifier.fillMaxWidth(), color = Palette.Lilac)
        } else {
            KeyCover(onShow = { keyVisible = true })
        }

        if (!g.over) {
            PillButton(t.endTurn, onEndTurn, Modifier.fillMaxWidth(), color = Color.White.copy(alpha = 0.16f), content = Color.White)
        }

        if (!state.tableConnected) ConnectPanel(state, onAllowNearby)

        PillButton(t.newGame, onNewGame, Modifier.fillMaxWidth(), color = if (g.over) Palette.Sun else Color.Transparent, content = if (g.over) Palette.Ink else Palette.Aqua)
    }
}

@Composable
private fun KeyGrid(g: GameState) {
    Grid { i, mod ->
        val type = g.key[i]
        WordCell(
            word = g.words[i],
            modifier = mod,
            fill = Palette.card(type),
            textColor = if (type == CardType.NEUTRAL) Palette.PaperText else Color.White,
            pattern = type,
            dim = if (g.revealed[i]) 0.33f else 1f,
            skull = type == CardType.KRAKEN,
        )
    }
}

@Composable
private fun KeyCover(onShow: () -> Unit) {
    val t = LocalStrings.current
    Surface(
        shape = Soft,
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(Soft)
            .clickable(onClick = onShow),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🗺️", fontSize = 48.sp)
            Text(t.keyHiddenTitle, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(t.keyHiddenHint, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            PillButton(t.showKey, onShow)
        }
    }
}

/** How to connect the table phone: shown until it is connected. */
@Composable
internal fun ConnectPanel(state: UiState, onAllowNearby: () -> Unit, castaways: Boolean = false) {
    val t = LocalStrings.current
    Surface(shape = Soft, color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (castaways) t.castawaysConnectTitle else t.connectTitle, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text(if (castaways) t.castawaysConnectHelp else t.connectHelpNearby, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            if (!state.nearbyActive) {
                if (state.nearbyProblem) Text(t.nearbyUnavailable, fontSize = 13.sp, color = Palette.Sun)
                Text(t.allowNearby, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                PillButton(t.allow, onAllowNearby, color = Palette.Aqua)
            }
            if (state.wifiAddresses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(if (castaways) t.castawaysConnectHelpWifi else t.connectHelpWifi, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Text("${t.wifiAddress}: ${state.wifiAddresses.joinToString("  ")}", fontWeight = FontWeight.Bold, color = Palette.Aqua)
                Text("${t.pinLabel}: ${state.pin}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Palette.Sun)
            }
        }
    }
}

// ---------------------------------------------------------------- table phone: joining

@Composable
private fun JoinScreen(
    state: UiState,
    onBack: () -> Unit,
    onAllowNearby: () -> Unit,
    onNearby: (String, String) -> Unit,
    onWifi: (String, String) -> Unit,
) {
    val t = LocalStrings.current
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    val nearbyAllowed = NearbyPermissions.granted(context)

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← ${t.back}", color = Palette.Aqua, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Text("🧭 ${t.joinTitle}", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(t.joinHint, color = Palette.Lilac)

        if (!nearbyAllowed) {
            Surface(shape = Soft, color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t.permissionText)
                    PillButton(t.allow, onAllowNearby, color = Palette.Aqua)
                }
            }
        } else {
            when (state.status) {
                ClientStatus.CONNECTING -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Aqua, strokeWidth = 3.dp)
                        Text(t.connecting, fontWeight = FontWeight.Bold)
                    }
                    state.pairingDigits?.let { Text(t.pairingCode(it), fontSize = 20.sp, fontWeight = FontWeight.Black, color = Palette.Sun) }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.status == ClientStatus.SEARCHING) CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Aqua, strokeWidth = 3.dp)
                        Text(if (state.status == ClientStatus.SEARCHING) t.searching else t.noneFound, color = Color.White.copy(alpha = 0.85f))
                    }
                    state.found.forEach { host ->
                        Surface(
                            shape = Soft,
                            color = Palette.Blue.copy(alpha = 0.3f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, Palette.Blue, Soft)
                                .clip(Soft)
                                .clickable { onNearby(host.id, host.name) },
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("🏴‍☠️", fontSize = 28.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(host.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        state.joinMessage?.let {
            Text(
                when (it) {
                    JoinMessage.FAILED -> t.connectionFailed
                    JoinMessage.WRONG_PIN -> "${t.connectionFailed} (${t.pinLabel})"
                    JoinMessage.LOCKED -> t.pinLocked
                    JoinMessage.BAD_ADDRESS -> t.addressHint
                    JoinMessage.NEARBY -> t.nearbyUnavailable
                },
                color = Palette.Sun,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(t.wifiTitle, fontWeight = FontWeight.Black, fontSize = 17.sp)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            placeholder = { Text(t.addressHint) },
            singleLine = true,
            shape = Pill,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            placeholder = { Text(t.pinHint) },
            singleLine = true,
            shape = Pill,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton(t.connect, { onWifi(address, pin) }, Modifier.fillMaxWidth(), enabled = address.isNotBlank() && pin.length == 6, color = Palette.Aqua)
    }
}

// ---------------------------------------------------------------- table phone: the board

@Composable
private fun TableScreen(
    state: UiState,
    onGuess: (Int) -> Unit,
    onEndTurn: () -> Unit,
    onRules: () -> Unit,
) {
    val t = LocalStrings.current
    val v = state.view
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    // a new state from the captain phone makes any half-made choice stale
    LaunchedEffect(v?.seq, v?.gameId) { selected = -1 }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (v == null) {
            Spacer(Modifier.height(80.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Palette.Aqua, strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(t.connecting)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TurnBanner(v.turn, v.winner, v.winReason) }
            HelpButton(onRules)
        }
        if (state.status != ClientStatus.CONNECTED) {
            Surface(shape = Soft, color = Palette.Sun.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Sun, strokeWidth = 2.5.dp)
                    Text(t.connectionLost, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamTile(Team.RED, v.remainingRed, Modifier.weight(1f))
            TeamTile(Team.BLUE, v.remainingBlue, Modifier.weight(1f))
        }

        Grid { i, mod ->
            val type = v.shown[i]
            if (type != null) {
                WordCell(
                    word = v.words[i],
                    modifier = mod,
                    fill = Palette.card(type),
                    textColor = if (type == CardType.NEUTRAL) Palette.PaperText else Color.White,
                    pattern = type,
                    // a card face up during play is solid; cards shown only because the game ended are paler
                    dim = if (v.revealed[i]) 1f else 0.45f,
                    skull = type == CardType.KRAKEN,
                )
            } else {
                WordCell(
                    word = v.words[i],
                    modifier = mod,
                    fill = Palette.Paper,
                    textColor = Palette.PaperText,
                    selected = selected == i,
                    onClick = if (v.over) null else ({ selected = if (selected == i) -1 else i }),
                )
            }
        }

        if (!v.over) {
            val pick = selected.takeIf { it in 0 until Engine.SIZE && v.shown[it] == null }
            PillButton(
                if (pick != null) t.revealWord(v.words[pick].uppercase()) else t.tapAWord,
                { pick?.let { onGuess(it) }; selected = -1 },
                Modifier.fillMaxWidth(),
                enabled = pick != null && state.status == ClientStatus.CONNECTED,
                color = Palette.team(v.turn),
                content = Color.White,
            )
            PillButton(t.endTurn, onEndTurn, Modifier.fillMaxWidth(), enabled = state.status == ClientStatus.CONNECTED, color = Color.White.copy(alpha = 0.16f), content = Color.White)
        } else {
            Text(t.waitingNewGame, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- rules

@Composable
private fun RulesScreen(initialCastaways: Boolean, onBack: () -> Unit) {
    val t = LocalStrings.current
    var castawaysTab by rememberSaveable(initialCastaways) { mutableStateOf(initialCastaways) }
    val sections = if (castawaysTab) t.castawaysRulesSections else t.rulesSections
    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← ${t.back}", color = Palette.Aqua, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text("📖 ${t.rulesTitle}", fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        // both versions of the game, one tap apart
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RulesTab("🏴‍☠️ ${t.classicTitle}", !castawaysTab, Modifier.weight(1f)) { castawaysTab = false }
            RulesTab("🏝️ ${t.castawaysTitle}", castawaysTab, Modifier.weight(1f)) { castawaysTab = true }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (castawaysTab) t.castawaysLabel else t.classicLabel, color = Palette.Lilac, fontWeight = FontWeight.Bold)
            sections.forEach { section ->
                Surface(shape = Soft, color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${section.emoji}  ${section.title}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Sun)
                        section.lines.forEach { line ->
                            Row {
                                Text("•", color = Palette.Lilac, modifier = Modifier.width(18.dp))
                                Text(line, color = Color.White.copy(alpha = 0.9f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RulesTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = Pill,
        color = if (selected) Palette.Aqua.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
        modifier = modifier
            .border(2.dp, if (selected) Palette.Aqua else Color.Transparent, Pill)
            .clip(Pill)
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
        )
    }
}
