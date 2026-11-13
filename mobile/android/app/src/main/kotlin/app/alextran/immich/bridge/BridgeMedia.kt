package app.alextran.immich.bridge

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import app.alextran.immich.core.HttpClientManager
import app.alextran.immich.widget.model.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Serves media bytes to picker apps.
 *
 * Remote rows: fetched from the server with the app's own session
 * (`?sessionKey=`, the dialect the home-screen widget uses). The shared OkHttp
 * client is reused when the app has initialised it, so custom certs/headers
 * apply; otherwise a minimal client is used.
 *
 * Local rows: read straight from MediaStore — the app already holds the media
 * permissions, and the consumer never needs a path into this sandbox.
 */
object BridgeMedia {
  private const val TAG = "ImmichBridgeMedia"

  private val fallbackClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
  }

  private val sizeCache = LruCache<String, Long>(256)

  private fun client(): OkHttpClient = try {
    HttpClientManager.getClient()
  } catch (_: Throwable) {
    fallbackClient
  }

  @Suppress("DEPRECATION")
  fun mediaStoreUri(localId: Long): Uri =
    ContentUris.withAppendedId(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
      } else {
        MediaStore.Files.getContentUri("external")
      },
      localId,
    )

  fun openLocalOriginal(ctx: Context, localId: Long): ParcelFileDescriptor =
    ctx.contentResolver.openFileDescriptor(mediaStoreUri(localId), "r")
      ?: throw IOException("cannot open local asset $localId")

  fun openLocalThumb(ctx: Context, localId: Long): ParcelFileDescriptor {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throw IOException("local thumbnails require API 29+")
    }
    val bitmap = ctx.contentResolver.loadThumbnail(mediaStoreUri(localId), Size(512, 512), null)
    return pipe { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
      bitmap.recycle()
    }
  }

  fun openRemoteThumb(ctx: Context, cfg: ServerConfig, assetId: String): ParcelFileDescriptor {
    val url = buildUrl(cfg, "/assets/$assetId/thumbnail", mapOf("size" to "thumbnail"))
    return pipe { out -> stream(ctx, cfg, url, out) }
  }

  fun openRemoteOriginal(ctx: Context, cfg: ServerConfig, assetId: String): ParcelFileDescriptor {
    val url = buildUrl(cfg, "/assets/$assetId/original")
    return pipe { out -> stream(ctx, cfg, url, out) }
  }

  /** Content-Length of the remote original, cached per asset. */
  fun remoteSize(cfg: ServerConfig, assetId: String): Long {
    sizeCache.get(assetId)?.let { return it }
    val url = buildUrl(cfg, "/assets/$assetId/original")
    val req = Request.Builder().url(url).head()
      .apply { cfg.customHeaders.forEach { (k, v) -> header(k, v) } }
      .build()
    client().newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for HEAD $url")
      val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
      if (len >= 0) sizeCache.put(assetId, len)
      return len
    }
  }

  fun localSize(ctx: Context, localId: Long): Long {
    ctx.contentResolver
      .query(mediaStoreUri(localId), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
      ?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
      }
    return -1L
  }

  private fun buildUrl(cfg: ServerConfig, path: String, params: Map<String, String> = emptyMap()): String {
    val sb = StringBuilder("${cfg.serverEndpoint}$path?sessionKey=${URLEncoder.encode(cfg.sessionKey, "UTF-8")}")
    params.forEach { (k, v) -> sb.append("&${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}") }
    return sb.toString()
  }

  private fun stream(ctx: Context, cfg: ServerConfig, url: String, out: OutputStream) {
    val req = Request.Builder().url(url)
      .apply { cfg.customHeaders.forEach { (k, v) -> header(k, v) } }
      .build()
    client().newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
      val body = resp.body ?: throw IOException("empty body for $url")
      body.byteStream().copyTo(out, 64 * 1024)
    }
  }

  /** Streams into a pipe on a background thread; the consumer reads the other end. */
  private fun pipe(write: (OutputStream) -> Unit): ParcelFileDescriptor {
    val pipe = ParcelFileDescriptor.createPipe()
    val read = pipe[0]
    val writeEnd = pipe[1]
    Thread {
      try {
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out -> write(out) }
      } catch (t: Throwable) {
        Log.w(TAG, "stream failed", t)
        try { writeEnd.close() } catch (_: Throwable) {}
      }
    }.start()
    return read
  }
}
