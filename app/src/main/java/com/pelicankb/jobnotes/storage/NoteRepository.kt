package com.pelicankb.jobnotes.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

class NoteRepository(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }
    }

    fun saveAs(name: String, bmp: Bitmap): File {
        val safe = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    fun loadLatest(): Pair<Bitmap, String>? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return null
        val latest = files.maxByOrNull { it.lastModified() } ?: return null
        val bmp = BitmapFactory.decodeFile(latest.absolutePath) ?: return null
        val name = latest.nameWithoutExtension.substringBeforeLast("_")
        return bmp to name
    }
}