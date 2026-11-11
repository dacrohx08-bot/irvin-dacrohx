package com.irvin.dacrohx.device

import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.Locale

/**
 * Música sin internet: busca canciones guardadas en el teléfono y las reproduce.
 * También controla cualquier app de música (Poweramp, Spotify, YouTube Music...).
 */
class MusicPlayer(private val ctx: Context) {

    data class Track(val id: Long, val title: String, val artist: String, val album: String) {
        val label get() = "$title — $artist"
    }

    data class NowPlaying(val track: Track?, val playing: Boolean, val queueSize: Int)

    private val am = ctx.getSystemService(AudioManager::class.java)
    private var mp: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var index = 0
    private var focus: AudioFocusRequest? = null

    private val _now = MutableStateFlow(NowPlaying(null, false, 0))
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun allTracks(): List<Track> {
        val out = ArrayList<Track>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                ),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Track(
                        c.getLong(0), c.getString(1) ?: "Sin título",
                        (c.getString(2) ?: "").replace("<unknown>", "Desconocido"), c.getString(3) ?: "",
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    fun count(): Int = allTracks().size

    /** Busca por canción, artista o álbum. "" o "lo que sea" = todo en aleatorio. */
    fun search(query: String): List<Track> {
        val all = allTracks()
        val q = norm(query)
        val any = q.isEmpty() || q in setOf("musica", "lo que sea", "la que sea", "cualquiera", "algo", "aleatorio", "random", "mi musica")
        if (any) return all.shuffled()
        val words = q.split(" ").filter { it.length > 1 }
        return all.mapNotNull { t ->
            val title = norm(t.title); val artist = norm(t.artist); val album = norm(t.album)
            val hay = "$title $artist $album"
            var score = 0
            if (title == q) score += 10
            if (artist == q) score += 8
            if (title.contains(q)) score += 6
            if (artist.contains(q)) score += 5
            if (album.contains(q)) score += 3
            score += words.count { hay.contains(it) }
            if (score >= maxOf(2, words.size)) t to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun playList(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue = tracks
        index = 0
        startCurrent()
    }

    private fun startCurrent() {
        val t = queue.getOrNull(index) ?: return stop()
        releasePlayer()
        requestFocus()
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id)
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            setDataSource(ctx, uri)
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ -> next(); true }
            setOnPreparedListener { it.start(); publish() }
            prepareAsync()
        }
        publish()
    }

    private fun publish() {
        _now.value = NowPlaying(queue.getOrNull(index), mp?.isPlaying == true, queue.size)
    }

    fun ownActive(): Boolean = mp != null

    fun pause() { try { mp?.pause() } catch (_: Exception) {}; publish() }
    fun resume() { try { requestFocus(); mp?.start() } catch (_: Exception) {}; publish() }
    fun next() { if (queue.isEmpty()) return; index = (index + 1) % queue.size; startCurrent() }
    fun previous() { if (queue.isEmpty()) return; index = if (index - 1 < 0) queue.size - 1 else index - 1; startCurrent() }

    fun stop() {
        releasePlayer()
        queue = emptyList()
        focus?.let { am.abandonAudioFocusRequest(it) }
        _now.value = NowPlaying(null, false, 0)
    }

    private fun releasePlayer() {
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
        mp = null
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) pause()
            }.build()
        focus = req
        am.requestAudioFocus(req)
    }

    /** Teclas multimedia: controlan la app de música que esté sonando (Poweramp, Spotify...). */
    fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Pide a una app de música instalada que busque y reproduzca (Spotify / YouTube Music con internet). */
    fun playFromSearchInApp(query: String): String? {
        val pm = ctx.packageManager
        val apps = listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.maxmpz.audioplayer" to "Poweramp",
            "deezer.android.app" to "Deezer",
            "com.amazon.mp3" to "Amazon Music",
        )
        for ((pkg, name) in apps) {
            try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { continue }
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) == null) continue
            return try { ctx.startActivity(i); name } catch (_: Exception) { null }
        }
        return null
    }

    fun setVolume(percent: Int): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent.coerceIn(0, 100) * max + 50) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        return v * 100 / max
    }

    fun volume(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }
}
