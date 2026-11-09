package com.zepinto.kraken

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

private fun SoundCue.res(): Int = when (this) {
    SoundCue.GOOD -> R.raw.sfx_good
    SoundCue.NEUTRAL -> R.raw.sfx_neutral
    SoundCue.BAD -> R.raw.sfx_bad
    SoundCue.KRAKEN -> R.raw.sfx_kraken
    SoundCue.WIN -> R.raw.sfx_win
}

private val gameAudio: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_GAME)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/** The game's sound effects (made with ElevenLabs, see tools/audio.json), loaded once and played from memory. */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(gameAudio).build()
    private val ids = SoundCue.entries.associateWith { pool.load(context, it.res(), 1) }

    fun play(cue: SoundCue) {
        // a sound that fails to load or play must never break the game
        ids[cue]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() = pool.release()
}

/** The background music: one looping track at a time, faded in and out when it changes. */
class Music(private val context: Context) {
    enum class Track(val res: Int) { LOBBY(R.raw.music_lobby), GAME(R.raw.music_game) }

    private var player: MediaPlayer? = null
    private var current: Track? = null
    private var wanted: Track? = null
    private var enabled = true
    private var paused = false

    /** The track for this moment of the game, or null for silence. */
    fun play(track: Track?) {
        wanted = track
        apply()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        apply()
    }

    /** The app went to the background. */
    fun pause() {
        paused = true
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun resume() {
        paused = false
        val p = player
        if (p != null) p.start() else apply()
    }

    fun release() {
        player?.release()
        player = null
        current = null
    }

    private fun apply() {
        val target = wanted.takeIf { enabled }
        if (target == current && (player != null || target == null)) return
        player?.let { fadeOut(it) }
        player = null
        current = target
        if (target == null || paused) return
        player = try {
            MediaPlayer.create(context, target.res, gameAudio, 0)?.apply {
                isLooping = true
                setVolume(0f, 0f)
                start()
            }
        } catch (_: Exception) {
            null
        }
        player?.let { fadeIn(it) }
    }

    private fun fade(p: MediaPlayer, from: Float, to: Float, ms: Long, end: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener { v -> runCatching { (v.animatedValue as Float).let { p.setVolume(it, it) } } }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    private fun fadeIn(p: MediaPlayer) = fade(p, 0f, VOLUME, 900)

    private fun fadeOut(p: MediaPlayer) = fade(p, VOLUME, 0f, 500) { runCatching { p.release() } }

    private companion object {
        const val VOLUME = 0.55f
    }
}
