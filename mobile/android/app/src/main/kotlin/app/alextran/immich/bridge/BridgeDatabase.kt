package app.alextran.immich.bridge

import android.content.Context
import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * One timeline row as served to picker apps. Mirrors the app's own `mergedAsset`
 * drift view: remote assets plus local-only (not yet backed up) assets.
 */
data class BridgeAsset(
  val key: String, // "r/<uuid>" for remote rows, "l/<mediaStoreId>" for local rows
  val remoteId: String?,
  val localId: String?,
  val name: String?,
  val type: Int,
  val createdAt: Long,
  val updatedAt: Long,
  val width: Int,
  val height: Int,
  val durationMs: Int,
  val isFavorite: Boolean,
  val checksum: String?,
  val ownerId: String?,
  val thumbHash: String?,
  val orientation: Int,
  val playbackStyle: Int,
  val livePhotoVideoId: String?,
) {
  fun toBundle(): Bundle = Bundle().apply {
    putString("key", key)
    putString("remoteId", remoteId)
    putString("localId", localId)
    putString("name", name)
    putInt("type", type)
    putLong("createdAt", createdAt)
    putLong("updatedAt", updatedAt)
    putInt("width", width)
    putInt("height", height)
    putInt("durationMs", durationMs)
    putBoolean("isFavorite", isFavorite)
    putString("checksum", checksum)
    putString("ownerId", ownerId)
    putString("thumbHash", thumbHash)
    putInt("orientation", orientation)
    putInt("playbackStyle", playbackStyle)
    putString("livePhotoVideoId", livePhotoVideoId)
  }
}

/**
 * Read-only access to the app's own drift database (`app_flutter/immich.sqlite`).
 *
 * The timeline SQL is the app's own query
 * (`mobile/lib/data/db/main/query/merged_asset.drift`) with the drift parameters
 * replaced by bound ones — same rows, same order, same dedup semantics as the
 * Immich gallery. Keep it in sync when the fork rebases onto a new Immich tag.
 */
object BridgeDatabase {
  private const val TAG = "ImmichBridgeDb"
  private const val DB_RELATIVE_PATH = "app_flutter/immich.sqlite"

  // Output columns: 0 remote_id, 1 local_id, 2 name, 3 type, 4 created_at,
  // 5 updated_at, 6 width, 7 height, 8 duration_ms, 9 is_favorite, 10 thumb_hash,
  // 11 checksum, 12 owner_id, 13 live_photo_video_id, 14 orientation,
  // 15 playback_style, 16 group_date (order key only).
  private val MERGED_ASSET_SQL = """
    SELECT
      rae.id as remote_id,
      (SELECT lae.id FROM local_asset_entity lae WHERE lae.checksum = rae.checksum LIMIT 1) as local_id,
      rae.name, rae."type", rae.created_at, rae.updated_at, rae.width, rae.height,
      rae.duration_ms, rae.is_favorite, rae.thumb_hash, rae.checksum, rae.owner_id,
      rae.live_photo_video_id, 0 as orientation, 0 as playback_style, rae.group_date
    FROM remote_asset_entity rae
    LEFT JOIN stack_entity se ON rae.stack_id = se.id
    WHERE rae.deleted_at IS NULL
      AND rae.visibility = 0 -- timeline visibility
      AND rae.owner_id IN (%s)
      AND rae.group_date IS NOT NULL
      AND (rae.stack_id IS NULL OR rae.id = se.primary_asset_id)
    UNION ALL
    SELECT
      NULL as remote_id, lae.id as local_id, lae.name, lae."type", lae.created_at,
      lae.updated_at, lae.width, lae.height, lae.duration_ms, lae.is_favorite,
      NULL as thumb_hash, lae.checksum, NULL as owner_id, NULL as live_photo_video_id,
      lae.orientation, lae.playback_style, lae.group_date
    FROM local_asset_entity lae
    WHERE NOT EXISTS (
      SELECT 1 FROM remote_asset_entity rae WHERE rae.checksum = lae.checksum AND rae.owner_id IN (%s)
    )
    AND EXISTS (
      SELECT 1 FROM local_album_asset_entity laa
      INNER JOIN local_album_entity la on laa.album_id = la.id
      WHERE laa.asset_id = lae.id AND la.backup_selection = 0 -- selected
    )
    AND NOT EXISTS (
      SELECT 1 FROM local_album_asset_entity laa
      INNER JOIN local_album_entity la on laa.album_id = la.id
      WHERE laa.asset_id = lae.id AND la.backup_selection = 2 -- excluded
    )
    AND lae.group_date IS NOT NULL
    ORDER BY group_date DESC, created_at DESC
    LIMIT ? OFFSET ?
  """

  /** Returns the timeline page, or null when the database is unavailable. */
  fun timeline(ctx: Context, count: Int, offset: Int): List<BridgeAsset>? {
    val db = open(ctx) ?: return null
    return try {
      val userIds = timelineUserIds(db) ?: return null
      if (userIds.isEmpty()) return emptyList()
      val placeholders = userIds.joinToString(",") { "?" }
      val sql = MERGED_ASSET_SQL.format(placeholders, placeholders)
      val args = (userIds + userIds + listOf(count.toString(), offset.toString())).toTypedArray()
      db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(readAsset(c)) }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "timeline query failed", t)
      null
    } finally {
      db.close()
    }
  }

  private fun open(ctx: Context): SQLiteDatabase? = try {
    val path = File(ctx.applicationInfo.dataDir, DB_RELATIVE_PATH).absolutePath
    val db = try {
      SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (t: Throwable) {
      // Read-only opens of a WAL database can still need the -shm path; fall back
      // to a writable handle pinned to read-only.
      SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).apply {
        execSQL("PRAGMA query_only = ON")
      }
    }
    db.apply {
      try {
        // PRAGMA busy_timeout returns a row, so execSQL() is not usable here.
        rawQuery("PRAGMA busy_timeout = 2000", null).use { it.moveToFirst() }
      } catch (_: Throwable) {
        // best effort; reads still work with the default timeout
      }
    }
  } catch (t: Throwable) {
    Log.w(TAG, "database unavailable", t)
    null
  }

  private fun timelineUserIds(db: SQLiteDatabase): List<String>? = try {
    val ids = mutableListOf<String>()
    db.rawQuery("SELECT id FROM auth_user_entity LIMIT 1", null).use { c ->
      if (c.moveToFirst() && !c.isNull(0)) ids.add(c.getString(0))
    }
    if (ids.isNotEmpty()) {
      db.rawQuery(
        "SELECT shared_by_id FROM partner_entity WHERE in_timeline = 1 AND shared_with_id = ?",
        arrayOf(ids[0]),
      ).use { c -> while (c.moveToNext()) if (!c.isNull(0)) ids.add(c.getString(0)) }
    }
    ids
  } catch (t: Throwable) {
    Log.w(TAG, "user query failed", t)
    null
  }

  private fun readAsset(c: Cursor): BridgeAsset {
    val remoteId = c.getString(0)
    val localId = c.getString(1)
    return BridgeAsset(
      key = if (remoteId != null) "r/$remoteId" else "l/$localId",
      remoteId = remoteId,
      localId = localId,
      name = c.getString(2),
      type = c.getInt(3),
      createdAt = parseInstant(c.getString(4)),
      updatedAt = parseInstant(c.getString(5)),
      width = c.getInt(6),
      height = c.getInt(7),
      durationMs = c.getInt(8),
      isFavorite = c.getInt(9) != 0,
      thumbHash = c.getString(10),
      checksum = c.getString(11),
      ownerId = c.getString(12),
      livePhotoVideoId = c.getString(13),
      orientation = c.getInt(14),
      playbackStyle = c.getInt(15),
    )
  }

  private fun parseInstant(value: String?): Long = try {
    if (value.isNullOrBlank()) 0L else Instant.parse(value).toEpochMilli()
  } catch (_: Exception) {
    0L
  }
}
