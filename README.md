# Kraken

A pirate word game for teams, played with **two phones** instead of cards. Two ways to play: **Classic** (4 or more
players: a captains' phone and a table phone) and **Castaways** (2 players, cooperative: one phone each).

Two pirate crews, red and blue, search the same sea for their treasure. Each crew has a captain who knows, from the
secret sea chart, which of the 25 words hide their crew's treasure, which are empty sea and which one wakes the Kraken.

- the **captains' phone** creates the game and shows the secret sea chart to the two captains;
- the **table phone** lies in the middle and shows the 25 words to everybody. The crew taps a word, confirms, and the
  phone only then learns what that card is.

The phones talk to each other live, so a guess made on the table phone shows up on the captains' phone at once,
together with whose turn it is and how much treasure is left. Clues are spoken at the table: the app only shows which
crew is playing and lets either phone **pass the turn**.

Native Android app (Kotlin, Jetpack Compose). English, Portuguese and Spanish, each with its own list of about 400
words. Colour-blind friendly: red cards have stripes, blue cards have dots.

## Castaways (2 players, one team)

Two castaways work together. **Each player uses their own phone, which is both the board and that player's own sea
chart**: 9 treasures, 3 Krakens and 13 empty sea (3 treasures are green on both charts, 15 words are treasure on at
least one). You take turns: the clue-giver says one word and a number out loud, the partner taps words, and the
clue-giver's chart says what each word is. Find all 15 treasures within 9 days (10 or 11 for an easier game) without
waking a Kraken. Empty sea ends the turn and marks the word; when the days run out the castaways **walk the plank**: no
clues, any wrong guess loses. The phone that creates the game holds both charts and is the authority; the other phone
only ever receives its own chart (the partner's is added once the game is over). Guesses carry the game and position
they were made on, so a late message cannot hit a new game. Join through the same Nearby or Wi-Fi link as in the
classic game.

## How the phones connect

| Mode | Needs | Notes |
|---|---|---|
| **Nearby** (default) | Bluetooth and Wi-Fi Direct, Google Play services | No internet and no shared Wi-Fi. Both phones show the same 4-digit code and the captains' phone accepts. |
| **Same Wi-Fi** | Both phones on one network (a phone hotspot works) | Type the address and the 6-digit PIN shown on the captains' phone into the table phone. A plain TCP socket, one JSON message per line; five wrong PINs lock that address out for a minute. |

The **captains' phone is the only place that knows the sea chart.** The table phone is only sent the words and what the
cards already uncovered are; the whole chart is sent once the game is over. If the connection drops, the table phone
reconnects by itself and gets the current state. The captains' phone saves the game, so closing the app does not lose
it (use **Resume game**).

> **Status:** the game rules, the protocol, the Wi-Fi link and all screens are covered by unit tests and were
> tested end to end on two emulators. The **Nearby** link has also been confirmed on two real phones: the table
> phone found the captains' phone without typing any address and the game played normally. It has not been
> tried on every Android version yet.

## Rules implemented

25 words; the crew that starts has 9 treasures, the other crew 8, plus 7 empty sea and 1 Kraken.
A clue is one word and a number, said out loud (the app does not take it and does not count guesses; the crew keeps
to the number plus one). Own treasure: keep guessing. Empty sea or the other crew's treasure: the turn ends. Either
phone can pass the turn. The Kraken: the crew that woke it loses. First crew to uncover all its treasure wins.
The rules of both versions are also inside the app (the **?** button; tabs switch between Classic and Castaways).

## Sound and music

The sound effects and the music are generated with [ElevenLabs](https://elevenlabs.io) from the prompts in
`tools/audio.json`. `tools/audio.py` regenerates them into `app/src/main/res/raw/` (it needs an ElevenLabs API key in
`$ELEVENLABS_API_KEY` or `~/.config/elevenlabs/api_key`; paid generations are cached in `tools/audio-cache/`, which is
not in git, so only changed prompts are generated again). Effects are trimmed and levelled; music is made into a
seamless loop.

The prompts describe the pirate theme: a sea shanty in the menus, a calm nautical tune during a game, a treasure
chest, a splash of empty sea and the Kraken rising. Run `tools/audio.py` once to replace the sounds of the previous
version, which are still the files in `app/src/main/res/raw/`. During a game only one phone plays music, so the two
never play the same tune out of step: the table phone (the captains' phone stays quiet), or in Castaways the phone
that created the game.

The 🎵 button on the first screen turns the music off. The sound effects stay on: they are part of the game.

## Build

Requirements: JDK 17 or newer and the Android SDK (platform 36).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Google Play

Google Play takes an App Bundle signed with the upload key:

```sh
./gradlew bundleRelease -Pplay    # → app/build/outputs/bundle/release/app-release.aab
```

`-Pplay` reads the key from these entries in `~/.gradle/gradle.properties`, which is outside the repo:

```properties
kraken.upload.storeFile=/home/you/.android/kraken-upload.jks
kraken.upload.storePassword=...
kraken.upload.keyAlias=upload
kraken.upload.keyPassword=...
```

Google re-signs the app for the store (Play App Signing). If the upload key is lost, it can be reset in the Play
Console. The Play version and a sideloaded APK have different signatures, so switching from one to the other means
uninstalling first. Raise `versionCode` in `app/build.gradle.kts` for every upload.

The privacy policy for the store listing is [PRIVACY.md](PRIVACY.md).

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/zepinto/kraken/Game.kt` | Cards, crews, the rules engine, word picking (plain Kotlin, unit tested) |
| `.../Protocol.kt`, `Session.kt` | Messages between the phones, host and table sessions, which sound to play |
| `.../Castaways.kt`, `CastawaysScreens.kt` | Castaways: layout of the two charts, rules engine, per-player view, host and guest sessions, screens |
| `.../Link.kt`, `LanLink.kt`, `NearbyLink.kt` | The two transports behind one interface |
| `.../AppViewModel.kt` | Wires game, sessions and links together; reconnection |
| `.../Screens.kt`, `Theme.kt`, `Strings.kt`, `Sfx.kt` | UI, look, texts (three languages, rules page), sound effects and music player |
| `app/src/main/assets/words_{en,pt,es}.txt` | One word per line |

## Testing two phones on a PC

Two emulators can play together over the Wi-Fi mode: run both with `-read-only`, forward a host port to the
captains' emulator (`adb -s emulator-5554 forward tcp:18765 tcp:8765`) and connect the table emulator to
`10.0.2.2:18765` with the PIN shown on the captains' screen.

## Known limits

- Nearby was confirmed on one pair of real phones only. On Android 12 and older the phone's location switch
  may have to be on for Nearby to find the other phone.
- There is no foreground service: keep the app open on both phones (the screen stays on by itself while it is).
- The Wi-Fi link is not encrypted; it is meant for a game among friends on a network you trust.
