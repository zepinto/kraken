# Codenames (two phones)

Two ways to play, both with phones instead of cards: **Classic** (4 or more players: a spymaster phone and a table
phone) and **Duet** (2 players, cooperative: one phone each).

The word-guessing party game **Codenames**, played with **two phones** instead of cards:

- the **spymaster phone** creates the game and shows the secret key (which of the 25 words are red, blue,
  bystanders or the assassin) to the two spymasters;
- the **table phone** lies in the middle and shows the 25 words to everybody. The team taps a word,
  confirms, and the phone only then learns that card's colour.

The phones talk to each other live, so a guess made on the table phone shows up on the spymaster phone
at once, together with whose turn it is and how many agents are left. Clues are spoken at the table, as in the
real game: the app only shows which team is playing and lets either phone **pass the turn**.

Native Android app (Kotlin, Jetpack Compose). English, Portuguese and Spanish, each with its own
list of about 400 words. Colour-blind friendly: red cards have stripes, blue cards have dots.

## Duet (2 players, one team)

The cooperative Codenames Duet for two. **Each player uses their own phone, which is both the board and that player's
own key**: 9 agents, 3 assassins and 13 bystanders, laid out as in the real game (3 agents are green on both keys).
You take turns: the clue-giver says one word and a number out loud, the partner taps words, and the clue-giver's key
says what each word is. Find all 15 agents within 9 turns (10 or 11 for an easier game) without picking an assassin.
A bystander ends the turn and marks the word; when the turns run out it is **sudden death**: no clues, any wrong guess
loses. The phone that creates the game holds both keys and is the authority; the other phone only ever receives its own
key (the partner's is added once the game is over). Guesses carry the game and position they were made on, so a late
message cannot hit a new game. Join through the same Nearby or Wi-Fi link as in the classic game.

## How the phones connect

| Mode | Needs | Notes |
|---|---|---|
| **Nearby** (default) | Bluetooth and Wi-Fi Direct, Google Play services | No internet and no shared Wi-Fi. Both phones show the same 4-digit code and the spymaster phone accepts. |
| **Same Wi-Fi** | Both phones on one network (a phone hotspot works) | Type the address and the 6-digit PIN shown on the spymaster phone into the table phone. A plain TCP socket, one JSON message per line; five wrong PINs lock that address out for a minute. |

The **spymaster phone is the only place that knows the key.** The table phone is only sent the words and the
colours of cards that have already been uncovered; the whole key is sent once the game is over.
If the connection drops, the table phone reconnects by itself and gets the current state. The spymaster
phone saves the game, so closing the app does not lose it (use **Resume game**).

> **Status:** the game rules, the protocol, the Wi-Fi link and all screens are covered by unit tests and were
> tested end to end on two emulators. The **Nearby** link has also been confirmed on two real phones: the table
> phone found the spymaster phone without typing any address and the game played normally. It has not been
> tried on every Android version yet.

## Rules implemented

25 words; the team that starts has 9 agents, the other team 8, plus 7 bystanders and 1 assassin.
A clue is one word and a number, said out loud (the app does not take it and does not count guesses; the team keeps
to the number plus one). Own agent: keep guessing. Bystander or the other team's agent: the turn ends. Either phone
can pass the turn.
Assassin: the team that picked it loses. First team to uncover all its agents wins.
The rules of both versions are also inside the app (the **?** button; tabs switch between Classic and Duet).

## Sound and music

The sound effects and the music were generated with [ElevenLabs](https://elevenlabs.io) from the prompts in
`tools/audio.json`. `tools/audio.py` regenerates them into `app/src/main/res/raw/` (it needs an ElevenLabs API key in
`$ELEVENLABS_API_KEY` or `~/.config/elevenlabs/api_key`; paid generations are cached in `tools/audio-cache/`, which is
not in git, so only changed prompts are generated again). Effects are trimmed and levelled; music is made into a
seamless loop.

Music: a spy lounge tune in the menus and a quiet spy-jazz one during a game. During a game only one phone plays
music, so the two never play the same tune out of step: the table phone (the spymaster's stays quiet), or in Duet
the phone that created the game.

The 🎵 button on the first screen turns the music off. The sound effects stay on: they are part of the game.

## Build

Requirements: JDK 17 or newer and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/zepinto/codenames/Game.kt` | Cards, teams, the rules engine, word picking (plain Kotlin, unit tested) |
| `.../Protocol.kt`, `Session.kt` | Messages between the phones, host and table sessions, which sound to play |
| `.../Duet.kt`, `DuetScreens.kt` | Duet: layout of the two keys, rules engine, per-player view, host and guest sessions, screens |
| `.../Link.kt`, `LanLink.kt`, `NearbyLink.kt` | The two transports behind one interface |
| `.../AppViewModel.kt` | Wires game, sessions and links together; reconnection |
| `.../Screens.kt`, `Theme.kt`, `Strings.kt`, `Sfx.kt` | UI, look, texts (three languages, rules page), sound effects and music player |
| `app/src/main/assets/words_{en,pt,es}.txt` | One word per line |

## Testing two phones on a PC

Two emulators can play together over the Wi-Fi mode: run both with `-read-only`, forward a host port to the
spymaster emulator (`adb -s emulator-5554 forward tcp:18765 tcp:8765`) and connect the table emulator to
`10.0.2.2:18765` with the PIN shown on the spymaster screen.

## Known limits

- Nearby was confirmed on one pair of real phones only. On Android 12 and older the phone's location switch
  may have to be on for Nearby to find the other phone.
- There is no foreground service: keep the app open on both phones (the screen stays on by itself while it is).
- The Wi-Fi link is not encrypted; it is meant for a game among friends on a network you trust.

## Disclaimer

This is an unofficial, non-commercial fan implementation. Codenames is a game by Vlaada Chvátil published by
Czech Games Edition; this project is not affiliated with or endorsed by them. The word lists are original.
