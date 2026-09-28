package dev.handeye.demo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** feed 表薄封装：全量替换 + 单条点赞更新 + dumpAll 快照（供 handeye persist 源）。 */
class FeedDb(context: Context) : SQLiteOpenHelper(context, "feed.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE feed (id INTEGER PRIMARY KEY, title TEXT NOT NULL, " +
                "liked INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceAll(items: List<FeedItem>) {
        writableDatabase.use { db ->
            db.beginTransaction()
            try {
                db.delete("feed", null, null)
                items.forEach { item ->
                    db.execSQL(
                        "INSERT INTO feed VALUES (?, ?, ?)",
                        arrayOf(item.id, item.title, if (item.liked) 1 else 0),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun setLiked(id: Int, liked: Boolean) {
        writableDatabase.use { db ->
            db.execSQL("UPDATE feed SET liked = ? WHERE id = ?", arrayOf(if (liked) 1 else 0, id))
        }
    }

    fun count(): Int =
        readableDatabase.use { db ->
            db.rawQuery("SELECT COUNT(*) FROM feed", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    fun dumpAll(): JsonArray {
        readableDatabase.use { db ->
            db.rawQuery("SELECT id, title, liked FROM feed ORDER BY id", null).use { cursor ->
                val out = buildJsonArray {
                    while (cursor.moveToNext()) {
                        add(buildJsonObject {
                            put("id", cursor.getInt(0))
                            put("title", cursor.getString(1))
                            put("liked", cursor.getInt(2) == 1)
                        })
                    }
                }
                return out
            }
        }
    }

    fun clear() {
        writableDatabase.use { db -> db.delete("feed", null, null) }
    }
}
