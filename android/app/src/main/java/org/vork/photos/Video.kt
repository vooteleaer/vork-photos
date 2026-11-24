package org.vork.photos

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor

/**
 * A video's content, read from vØrk storage as the player asks for it: a
 * piece at a time, the pieces not on this device fetched from the library's
 * others as it plays. A few recent pieces are kept (players read the same
 * places again: headers, the index at the end).
 */
class StorageSource(private val ns: String, private val cap: String) : MediaDataSource() {
    private val total = Photo.size(cap)
    private val pieces = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }

    override fun getSize(): Long = total

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        var done = 0
        while (done < size && position + done < total) {
            val at = position + done
            val index = at / PIECE
            val piece = synchronized(pieces) { pieces[index] } ?: (Core.readRange(ns, cap, index * PIECE, PIECE) ?: break).also { synchronized(pieces) { pieces[index] = it } }
            val from = (at - index * PIECE).toInt()
            if (from >= piece.size) break
            val n = minOf(size - done, piece.size - from)
            System.arraycopy(piece, from, buffer, offset + done, n)
            done += n
        }
        // Nothing could be read: the player says it can't go on.
        return if (done == 0) -1 else done
    }

    override fun close() {
        synchronized(pieces) { pieces.clear() }
    }

    companion object {
        const val PIECE = 1L shl 20
    }
}

/** Pictures of videos: a frame, for those no device has made one for yet. */
object Frames {
    private val cache = LruCache<String, Bitmap>(64)

    /** A frame of `p` (a second in), about `side` pixels on its longest side, read from vØrk storage. */
    suspend fun of(p: Photo, side: Int): Bitmap? {
        val cap = p.original ?: return null
        val key = "$cap@$side"
        cache.get(key)?.let { return it }
        val frame = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(StorageSource(p.ns, cap))
                scaledFrame(r, side)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        } ?: return null
        cache.put(key, frame)
        return frame
    }

    /**
     * A video's thumbnails as the extractor makes a picture's (ADR-0026):
     * a frame as JPEG about 400 and about 1600 pixels on its longest side.
     */
    fun thumbnails(fd: FileDescriptor): Pair<ByteArray, ByteArray>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(fd)
            fun jpeg(side: Int): ByteArray? {
                val b = scaledFrame(r, side) ?: return null
                return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            }
            val thumb = jpeg(400) ?: return null
            val preview = jpeg(1600) ?: return null
            thumb to preview
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/** A frame a second in, about `side` pixels on its longest side. */
private fun scaledFrame(r: MediaMetadataRetriever, side: Int): Bitmap? {
    if (android.os.Build.VERSION.SDK_INT >= 27) return r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
    val b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
    val scale = side.toFloat() / maxOf(b.width, b.height)
    return if (scale >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
}

/** `83` → "1:23", `3725` → "1:02:05". */
fun durationLabel(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A video's mark on its thumbnail: a play sign and how long it is. */
@Composable
fun VideoBadge(p: Photo, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(14.dp))
        p.durationS?.let { Text(durationLabel(it), color = Color.White, style = MaterialTheme.typography.labelSmall) }
    }
}

/**
 * A video filling the screen, played from vØrk storage as it comes: it
 * starts when its page is the one shown and stops when it isn't. A tap
 * shows or hides the bars; the button plays or pauses; the bar seeks.
 */
@Composable
fun VideoPlayer(p: Photo, active: Boolean, chrome: Boolean, tap: () -> Unit) {
    val cap = p.original
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var length by remember { mutableFloatStateOf(1f) }
    // Width over height as it plays (turned as it says): the picture keeps it.
    var ratio by remember { mutableFloatStateOf(0f) }
    val poster by androidx.compose.runtime.produceState<Bitmap?>(null, p.ns, p.id) { value = Images.best(p, 1800) }

    // A player while the page is shown, with the surface it draws on.
    DisposableEffect(active, surface, cap) {
        val s = surface
        if (!active || s == null || cap == null) return@DisposableEffect onDispose { }
        val mp = MediaPlayer()
        player = mp
        failed = false
        prepared = false
        mp.setSurface(s)
        mp.setOnPreparedListener {
            prepared = true
            length = it.duration.coerceAtLeast(1).toFloat()
            it.start()
            playing = true
        }
        mp.setOnCompletionListener { playing = false }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
        mp.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        try {
            mp.setDataSource(StorageSource(p.ns, cap))
            mp.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
        onDispose {
            player = null
            prepared = false
            playing = false
            runCatching { mp.release() }
        }
    }
    // Where it is, for the bar.
    LaunchedEffect(player, prepared) {
        while (player != null && prepared) {
            position = runCatching { player?.currentPosition?.toFloat() }.getOrNull() ?: position
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().clickable(onClick = tap), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(t)
                        }

                        override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}

                        override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                    }
                }
            },
            // As big as fits, its own shape (not stretched to the screen's).
            modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
        )
        // Before it plays: its frame, and that it's coming.
        if (!prepared) {
            poster?.let { androidx.compose.foundation.Image(it.asImageBitmap(), p.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
            if (failed) {
                Text("This video can't be played now", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(12.dp))
            } else if (active) {
                CircularProgressIndicator()
            }
        }
        if (chrome && prepared) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 64.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val mp = player ?: return@IconButton
                        if (mp.isPlaying) mp.pause() else mp.start()
                        playing = mp.isPlaying
                    }) {
                        if (playing) PauseSign() else Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White)
                    }
                    Text(durationLabel((position / 1000).toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = position.coerceIn(0f, length),
                        onValueChange = { position = it },
                        onValueChangeFinished = { player?.seekTo(position.toInt()) },
                        valueRange = 0f..length,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(durationLabel((length / 1000).toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Two bars: pause (the core icon set has none). */
@Composable
private fun PauseSign() {
    Row(Modifier.size(24.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 6.dp, height = 16.dp).background(Color.White))
        Box(Modifier.size(width = 4.dp, height = 16.dp))
        Box(Modifier.size(width = 6.dp, height = 16.dp).background(Color.White))
    }
}

