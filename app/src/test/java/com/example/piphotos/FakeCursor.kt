package com.example.piphotos

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle

class FakeCursor(
    private val columnNames: Array<String>,
    private val rows: List<Array<Any?>>
) : Cursor {

    private var position = -1
    private var isClosed = false

    override fun getCount(): Int = rows.size
    override fun getPosition(): Int = position
    override fun move(offset: Int): Boolean = moveToPosition(position + offset)
    override fun moveToPosition(pos: Int): Boolean {
        return if (pos in 0 until rows.size) {
            position = pos
            true
        } else {
            position = if (pos < 0) -1 else rows.size
            false
        }
    }

    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(rows.size - 1)
    override fun moveToNext(): Boolean = moveToPosition(position + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(position - 1)
    override fun isFirst(): Boolean = position == 0 && rows.isNotEmpty()
    override fun isLast(): Boolean = position == rows.size - 1 && rows.isNotEmpty()
    override fun isBeforeFirst(): Boolean = position < 0
    override fun isAfterLast(): Boolean = position >= rows.size

    override fun getColumnIndex(columnName: String): Int = columnNames.indexOf(columnName)
    override fun getColumnIndexOrThrow(columnName: String): Int {
        val idx = getColumnIndex(columnName)
        if (idx < 0) throw IllegalArgumentException("Column $columnName not found")
        return idx
    }

    override fun getColumnName(columnIndex: Int): String = columnNames[columnIndex]
    override fun getColumnNames(): Array<String> = columnNames
    override fun getColumnCount(): Int = columnNames.size

    private fun getCurrentValue(columnIndex: Int): Any? {
        if (position !in 0 until rows.size) throw IllegalStateException("Cursor position $position out of bounds")
        return rows[position][columnIndex]
    }

    override fun getString(columnIndex: Int): String? = getCurrentValue(columnIndex)?.toString()
    override fun getShort(columnIndex: Int): Short = (getCurrentValue(columnIndex) as? Number)?.toShort() ?: 0
    override fun getInt(columnIndex: Int): Int = (getCurrentValue(columnIndex) as? Number)?.toInt() ?: 0
    override fun getLong(columnIndex: Int): Long = (getCurrentValue(columnIndex) as? Number)?.toLong() ?: 0L
    override fun getFloat(columnIndex: Int): Float = (getCurrentValue(columnIndex) as? Number)?.toFloat() ?: 0f
    override fun getDouble(columnIndex: Int): Double = (getCurrentValue(columnIndex) as? Number)?.toDouble() ?: 0.0
    override fun getType(columnIndex: Int): Int = Cursor.FIELD_TYPE_STRING
    override fun isNull(columnIndex: Int): Boolean = getCurrentValue(columnIndex) == null

    override fun close() { isClosed = true }
    override fun isClosed(): Boolean = isClosed
    @Deprecated("Deprecated in Java")
    override fun deactivate() {}
    @Deprecated("Deprecated in Java")
    override fun requery(): Boolean = true

    // Stubs for unused Cursor methods
    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer?) {}
    override fun getBlob(columnIndex: Int): ByteArray = ByteArray(0)
    override fun registerContentObserver(observer: ContentObserver?) {}
    override fun unregisterContentObserver(observer: ContentObserver?) {}
    override fun registerDataSetObserver(observer: DataSetObserver?) {}
    override fun unregisterDataSetObserver(observer: DataSetObserver?) {}
    override fun setNotificationUri(cr: ContentResolver?, uri: Uri?) {}
    override fun getNotificationUri(): Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun setExtras(extras: Bundle?) {}
    override fun getExtras(): Bundle = Bundle.EMPTY
    override fun respond(extras: Bundle?): Bundle = Bundle.EMPTY
    override fun getNotificationUris(): List<Uri>? = null
    override fun setNotificationUris(cr: ContentResolver, uris: List<Uri>) {}
}
