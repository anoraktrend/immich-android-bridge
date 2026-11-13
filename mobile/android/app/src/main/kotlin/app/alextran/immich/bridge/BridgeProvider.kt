package app.alextran.immich.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import app.alextran.immich.widget.ImmichAPI

/**
 * Read-only bridge provider for the Immich media picker patches (v2 orchestrator).
 *
 * The patched picker apps (Discord, Tumblr) read configuration — and soon media —
 * from here, so credentials live in exactly one place: this app's own session.
 *
 * Guarded by a signature-level permission, so only apps signed with the same key
 * (i.e. built or patched by the same person) can reach it.
 *
 * Notes:
 *  - No work in onCreate: configuration is read per call, which keeps it in sync
 *    with login/logout for free.
 *  - Media endpoints (timeline from the app's own mergedAsset SQL, thumbnails,
 *    originals) land here next; see `immich-media-patches/docs/v2-orchestrator.md`.
 */
class BridgeProvider : ContentProvider() {
  companion object {
    const val AUTHORITY = "app.alextran.immich.bridge"
    const val PERMISSION = "app.alextran.immich.permission.BRIDGE"
    const val PROTOCOL_VERSION = 1
    private const val METHOD_CONFIG = "config"
  }

  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    requireBridgePermission()
    return when (method) {
      METHOD_CONFIG -> config()
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

  private fun requireBridgePermission() {
    val ctx = requireNotNull(context) { "provider detached" }
    if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      throw SecurityException("caller lacks $PERMISSION")
    }
  }

  // Tabular and file access (timeline, thumbnails, originals) arrive with the
  // media endpoints; until then the bridge is read-only by construction.

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor = throw UnsupportedOperationException("bridge has no query endpoints yet")

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

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
    throw UnsupportedOperationException("bridge has no file endpoints yet")
}
