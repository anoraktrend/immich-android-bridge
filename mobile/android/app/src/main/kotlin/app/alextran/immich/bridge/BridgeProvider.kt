package app.alextran.immich.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import app.alextran.immich.widget.ImmichAPI
import java.io.IOException

/**
 * Read-only bridge provider for the Immich media picker patches (v2 orchestrator).
 *
 * The patched picker apps (Discord, Tumblr) read configuration and media from here,
 * so credentials live in exactly one place: this app's own session.
 *
 * Endpoints:
 *  - `call("config")`   → server endpoint + live session key (read per call)
 *  - `call("timeline", extras{count,offset})` → page of the app's own merged timeline
 *  - `call("size", "r/<uuid>"|"l/<mediaId>")` → byte size of the original
 *  - `openFile(content://<authority>/thumb/r/<uuid>|l/<id>)` → preview bytes (WebP/JPEG)
 *  - `openFile(content://<authority>/original/r/<uuid>|l/<id>)` → original bytes
 *
 * Guarded by a signature-level permission, so only apps signed with the same key
 * (i.e. built or patched by the same person) can reach it. Framework enforcement via
 * `android:readPermission`; `call()` re-checks because the framework does not gate it.
 *
 * No work in onCreate: config is read per call, which keeps it in sync with the
 * app's login state for free.
 */
class BridgeProvider : ContentProvider() {
  companion object {
    const val AUTHORITY = "app.alextran.immich.bridge"
    const val PERMISSION = "app.alextran.immich.permission.BRIDGE"
    const val PROTOCOL_VERSION = 1
    private const val TAG = "ImmichBridge"
    private const val METHOD_CONFIG = "config"
    private const val METHOD_TIMELINE = "timeline"
    private const val METHOD_SIZE = "size"
  }

  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    requireBridgePermission()
    return when (method) {
      METHOD_CONFIG -> config()
      METHOD_TIMELINE -> timeline(extras)
      METHOD_SIZE -> size(arg)
      else -> throw IllegalArgumentException("unknown bridge method: $method")
    }
  }

  /** Server endpoint + live session key, or `configured=false` when logged out. */
  private fun config(): Bundle {
    val ctx = requireNotNull(context) { "provider detached" }
    val cfg = ImmichAPI.getServerConfig(ctx)
    return Bundle().apply {
      putInt("protocol", PROTOCOL_VERSION)
      if (cfg == null) {
        putBoolean("configured", false)
      } else {
        putBoolean("configured", true)
        putString("serverUrl", cfg.serverEndpoint)
        putString("sessionKey", cfg.sessionKey)
        putBundle(
          "customHeaders",
          Bundle().apply { cfg.customHeaders.forEach { (k, v) -> putString(k, v) } },
        )
      }
    }
  }

  /** A page of the app's own timeline (remote + local-only rows, app ordering). */
  private fun timeline(extras: Bundle?): Bundle {
    val ctx = requireNotNull(context) { "provider detached" }
    val count = (extras?.getInt("count", 100) ?: 100).coerceIn(1, 1000)
    val offset = (extras?.getInt("offset", 0) ?: 0).coerceAtLeast(0)
    return try {
      val rows = BridgeDatabase.timeline(ctx, count, offset)
      Bundle().apply {
        putInt("protocol", PROTOCOL_VERSION)
        if (rows == null) {
          putBoolean("available", false)
        } else {
          putBoolean("available", true)
          putParcelableArrayList("assets", ArrayList(rows.map { it.toBundle() }))
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "timeline failed", t)
      Bundle().apply {
        putInt("protocol", PROTOCOL_VERSION)
        putBoolean("available", false)
      }
    }
  }

  private fun size(key: String?): Bundle {
    val ctx = requireNotNull(context) { "provider detached" }
    val parts = key?.split("/")
    val cfg = ImmichAPI.getServerConfig(ctx)
    val size = try {
      if (cfg == null) {
        -1L
      } else {
        when (parts?.getOrNull(0)) {
          "r" -> parts.getOrNull(1)?.let { BridgeMedia.remoteSize(cfg, it) } ?: -1L
          "l" -> parts.getOrNull(1)?.toLongOrNull()?.let { BridgeMedia.localSize(ctx, it) } ?: -1L
          else -> -1L
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "size lookup failed for $key", t)
      -1L
    }
    return Bundle().apply { putLong("size", size) }
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    requireBridgePermission()
    if (mode.contains('w')) throw SecurityException("bridge is read-only")
    val ctx = requireNotNull(context) { "provider detached" }
    val segs = uri.pathSegments
    if (segs.size != 3) throw IllegalArgumentException("bad bridge uri: $uri")
    val cfg = ImmichAPI.getServerConfig(ctx) ?: throw IOException("Immich is not signed in")
    val kind = segs[0]
    val idKind = segs[1]
    val id = segs[2]
    return when (kind) {
      "thumb" -> when (idKind) {
        "r" -> BridgeMedia.openRemoteThumb(ctx, cfg, id)
        "l" -> BridgeMedia.openLocalThumb(ctx, id.toLong())
        else -> throw IllegalArgumentException("bad bridge uri: $uri")
      }
      "original" -> when (idKind) {
        "r" -> BridgeMedia.openRemoteOriginal(ctx, cfg, id)
        "l" -> BridgeMedia.openLocalOriginal(ctx, id.toLong())
        else -> throw IllegalArgumentException("bad bridge uri: $uri")
      }
      else -> throw IllegalArgumentException("bad bridge uri: $uri")
    }
  }

  private fun requireBridgePermission() {
    val ctx = requireNotNull(context) { "provider detached" }
    if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      throw SecurityException("caller lacks $PERMISSION")
    }
  }

  // The bridge has no tabular endpoints; everything is `call()` or `openFile()`.

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor = throw UnsupportedOperationException("bridge has no query endpoints")

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("bridge is read-only")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("bridge is read-only")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("bridge is read-only")
}
