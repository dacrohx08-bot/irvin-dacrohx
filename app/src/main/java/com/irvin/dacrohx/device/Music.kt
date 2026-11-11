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

    @Volatile private var cache: List<Track>? = null
    @Volatile private var cacheAt = 0L
    private val normCache = HashMap<Long, Triple<String, String, String>>()

    private fun allTracks(): List<Track> {
        cache?.let { if (System.currentTimeMillis() - cacheAt < 5 * 60_000) return it }
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
        cache = out; cacheAt = System.currentTimeMillis()
        synchronized(normCache) { normCache.clear() }
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
            val (title, artist, album) = synchronized(normCache) { normCache.getOrPut(t.id) { Triple(norm(t.title), norm(t.artist), norm(t.album)) } }
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

    data class App(val pkg: String, val label: String)

    /** Apps de música instaladas que pueden reproducir por búsqueda (Poweramp, Spotify, YouTube Music...). */
    @Volatile private var appsCache: List<App>? = null
    @Volatile private var appsAt = 0L

    fun musicApps(): List<App> {
        appsCache?.let { if (System.currentTimeMillis() - appsAt < 10 * 60_000) return it }
        return loadMusicApps().also { appsCache = it; appsAt = System.currentTimeMillis() }
    }

    private fun loadMusicApps(): List<App> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, App>()
        try {
            pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0).forEach {
                val pkg = it.activityInfo.packageName
                if (pkg == ctx.packageName || pkg.startsWith("com.google.android.googlequicksearchbox")) return@forEach
                out[pkg] = App(pkg, it.loadLabel(pm).toString())
            }
        } catch (_: Exception) {}
        out.remove("com.google.android.youtube")
        for ((pkg, name) in listOf(
            "com.maxmpz.audioplayer" to "Poweramp", "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music", "com.google.android.youtube" to "YouTube",
        )) {
            if (pkg !in out) try { pm.getPackageInfo(pkg, 0); out[pkg] = App(pkg, name) } catch (_: Exception) {}
        }
        // Si dos apps se llaman igual, agrega una pista para distinguirlas.
        val list = out.values.toList()
        val dup = list.groupBy { it.label }.filter { it.value.size > 1 }.keys
        return list.map { a ->
            if (a.label in dup) a.copy(label = a.label + " · " + a.pkg.split('.').filter { it !in setOf("com", "android", "apps", "app", "google") }.take(2).joinToString(".")) else a
        }
    }

    fun findApp(name: String): App? {
        val n = norm(name)
        if (n.isEmpty()) return null
        val apps = musicApps()
        return apps.firstOrNull { norm(it.label) == n }
            ?: apps.sortedByDescending { it.label.length }.firstOrNull { n.contains(norm(it.label)) }
            ?: apps.firstOrNull { norm(it.label).contains(n) }
    }

    /** Reproduce en otra app. Primero por búsqueda; si no, abre la canción encontrada en el teléfono. */
    fun playInApp(app: App, query: String): Boolean {
        val pm = ctx.packageManager
        // YouTube (normal / Premium): abre la búsqueda del video musical.
        if (app.pkg == "com.google.android.youtube") {
            val q = if (query.isBlank() || norm(query) in setOf("lo que sea", "la que sea", "musica")) "música" else query
            val i = Intent(Intent.ACTION_SEARCH).setPackage(app.pkg).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { stop(); ctx.startActivity(i); return true } catch (_: Exception) {}
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q)))
                    .setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Exception) {}
        }
        val hits = if (query.isNotBlank()) search(query) else emptyList()
        val isArtist = hits.isNotEmpty() && norm(hits.first().artist).contains(norm(query))
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.pkg)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            query.isBlank() -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            isArtist -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, hits.first().artist)
            hits.isNotEmpty() -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, hits.first().title)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, hits.first().artist)
            else -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (i.resolveActivity(pm) != null) {
            try { stop(); ctx.startActivity(i); return true } catch (_: Exception) {}
        }
        // Respaldo: abrir directamente el archivo de la canción en esa app.
        hits.firstOrNull()?.let { t ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id)
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").setPackage(app.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { stop(); ctx.startActivity(v); return true } catch (_: Exception) {}
        }
        // Último recurso: abrir la app.
        pm.getLaunchIntentForPackage(app.pkg)?.let {
            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        }
        return false
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
