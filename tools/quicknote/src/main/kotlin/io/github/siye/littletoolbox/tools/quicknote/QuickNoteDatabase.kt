package io.github.siye.littletoolbox.tools.quicknote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

internal class QuickNoteDatabase(context: Context) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, "quick-note.db").absolutePath,
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE draft (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE notes (" +
                "id INTEGER PRIMARY KEY, body TEXT NOT NULL, saved_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing quick note database migration from $oldVersion to $newVersion")
    }

    fun read(): NoteState {
        val db = readableDatabase
        val draft = db.rawQuery("SELECT body FROM draft WHERE id = 1", null).use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else ""
        }
        val notes = db.rawQuery("SELECT id, body, saved_at FROM notes ORDER BY id DESC", null)
            .use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        add(SavedNote(rows.getLong(0), rows.getString(1), rows.getLong(2)))
                    }
                }
            }
        return NoteState(ready = true, draft = draft, notes = notes)
    }

    fun writeDraft(body: String) {
        if (body.isEmpty()) {
            writableDatabase.delete("draft", "id = 1", null)
        } else {
            writableDatabase.replaceOrThrow(
                "draft",
                null,
                ContentValues().apply {
                    put("id", 1)
                    put("body", body)
                },
            )
        }
    }

    fun saveNote(body: String): SavedNote {
        val db = writableDatabase
        val savedAt = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val id = db.insertOrThrow(
                "notes",
                null,
                ContentValues().apply {
                    put("body", body)
                    put("saved_at", savedAt)
                },
            )
            db.delete("draft", "id = 1", null)
            db.setTransactionSuccessful()
            return SavedNote(id, body, savedAt)
        } finally {
            db.endTransaction()
        }
    }
}
