package ir.rhinocloud.easyotp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest

/** A captured message, before it is sealed for storage. */
data class CapturedMessage(
    val iccid: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
)

/** A row ready to be delivered. Sender and body are decrypted on read. */
data class PendingMessage(
    val id: Long,
    val iccid: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val attempts: Int,
)

/**
 * The durable outbox. The only place in the system that stores anything.
 *
 * Written to by the broadcast receiver before it does anything else, because the
 * process may be killed the moment the receiver returns (ARCHITECTURE section 1).
 * Drained by the foreground service.
 *
 * Message content is sealed with a Keystore key and stored opaque; queue metadata
 * stays queryable in the clear. The reasoning, and what that deliberately does not
 * protect, is DECISIONS D13.
 */
class Outbox(context: Context, private val vault: KeyVault = KeyVault()) {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    dedupe_key      TEXT    NOT NULL UNIQUE,
                    iccid           TEXT    NOT NULL,
                    received_at     INTEGER NOT NULL,
                    payload         BLOB    NOT NULL,
                    state           TEXT    NOT NULL,
                    attempts        INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL DEFAULT 0,
                    last_error      TEXT
                )
                """.trimIndent(),
            )
            // Drives the drain query: pending rows whose backoff has elapsed.
            db.execSQL("CREATE INDEX idx_ready ON $TABLE (state, next_attempt_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // No migrations yet. When the first one lands it must preserve rows:
            // dropping the table would discard undelivered messages, which for
            // this product means silently losing someone's bank code.
        }
    }

    /**
     * Stores a message, ignoring one already present.
     *
     * SMS carries no message id, so identity is derived. The timestamp is bucketed
     * to ten seconds so a redelivery of the same message collapses onto the same
     * row, while two genuinely distinct messages with identical text (getting
     * "Your code is 4821" twice is normal for OTPs) stay separate.
     *
     * @return true if a new row was written.
     */
    fun enqueue(message: CapturedMessage): Boolean {
        val payload = JSONObject()
            .put("sender", message.sender)
            .put("body", message.body)
            .toString()
            .toByteArray()

        val values = ContentValues().apply {
            put("dedupe_key", dedupeKey(message))
            put("iccid", message.iccid)
            put("received_at", message.receivedAt)
            put("payload", vault.seal(payload))
            put("state", STATE_PENDING)
            put("next_attempt_at", 0L)
        }
        val rowId = helper.writableDatabase
            .insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return rowId != -1L
    }

    /** Pending rows whose backoff has elapsed, oldest first. */
    fun claimReady(now: Long, limit: Int = 20): List<PendingMessage> {
        val rows = mutableListOf<PendingMessage>()
        helper.readableDatabase.query(
            TABLE,
            arrayOf("id", "iccid", "received_at", "payload", "attempts"),
            "state = ? AND next_attempt_at <= ?",
            arrayOf(STATE_PENDING, now.toString()),
            null,
            null,
            "received_at ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // An undecryptable row skips rather than throwing: one bad blob
                // must not stop every other message in the queue from going out.
                val payload = runCatching {
                    JSONObject(String(vault.open(cursor.getBlob(3))))
                }.getOrNull() ?: continue
                rows += PendingMessage(
                    id = cursor.getLong(0),
                    iccid = cursor.getString(1),
                    sender = payload.optString("sender"),
                    body = payload.optString("body"),
                    receivedAt = cursor.getLong(2),
                    attempts = cursor.getInt(4),
                )
            }
        }
        return rows
    }

    fun markSent(id: Long) {
        val values = ContentValues().apply {
            put("state", STATE_SENT)
            putNull("last_error")
        }
        helper.writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Records a failure and schedules the retry.
     *
     * `reason` is a machine-readable code. Never an upstream error string, which
     * can echo message content back into storage (THREAT-MODEL rule 1).
     */
    fun markFailed(id: Long, reason: String, now: Long) {
        val nextAttempt = now + backoffMillis(attemptsOf(id) + 1)
        helper.writableDatabase.execSQL(
            """
            UPDATE $TABLE
               SET attempts = attempts + 1,
                   last_error = ?,
                   next_attempt_at = ?
             WHERE id = ?
            """.trimIndent(),
            arrayOf<Any>(reason, nextAttempt, id),
        )
    }

    fun pendingCount(): Long =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE state = ?",
            arrayOf(STATE_PENDING),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Delivered rows are history, not queue. Trimmed so the archive cannot grow without bound. */
    fun purgeSentBefore(cutoff: Long): Int =
        helper.writableDatabase.delete(
            TABLE,
            "state = ? AND received_at < ?",
            arrayOf(STATE_SENT, cutoff.toString()),
        )

    private fun attemptsOf(id: Long): Int =
        helper.readableDatabase.rawQuery(
            "SELECT attempts FROM $TABLE WHERE id = ?",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SENT = "SENT"

        private const val DB_NAME = "easyotp-outbox.db"
        private const val DB_VERSION = 1
        private const val TABLE = "outbox"

        /** Ten-second bucket: see [enqueue]. */
        private const val DEDUPE_BUCKET_MS = 10_000L

        fun dedupeKey(m: CapturedMessage): String {
            val material = "${m.iccid} ${m.sender} ${m.body} ${m.receivedAt / DEDUPE_BUCKET_MS}"
            return MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Exponential backoff with a ceiling of five minutes.
         *
         * The ceiling matters more than the curve. This device sits on a network
         * that disappears for hours, and a queue backing off to hours would still
         * be asleep when connectivity returns.
         */
        fun backoffMillis(attempt: Int): Long {
            val seconds = when {
                attempt <= 1 -> 5L
                attempt >= 8 -> 300L
                else -> 5L shl (attempt - 1)
            }
            return seconds * 1000
        }
    }
}
