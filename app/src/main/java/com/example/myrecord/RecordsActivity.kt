package com.example.myrecord

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RecordsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var textEmptyState: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingFile: String? = null

    private lateinit var layoutTopBar: View
    private lateinit var layoutMultiSelect: View
    private lateinit var textSelectedCount: TextView

    private var isSelectMode = false
    private val selectedFiles = mutableSetOf<File>()
    private val durationExecutor = Executors.newSingleThreadExecutor()

    // BATTERY OPTIMIZATION: Cache durations so we don't read file headers twice
    private val durationCache = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)

        recyclerView = findViewById(R.id.recyclerViewRecords)
        textEmptyState = findViewById(R.id.textEmptyState)
        recyclerView.layoutManager = LinearLayoutManager(this)

        layoutTopBar = findViewById(R.id.layoutTopBar)
        layoutMultiSelect = findViewById(R.id.layoutMultiSelect)
        textSelectedCount = findViewById(R.id.textSelectedCount)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSelect).setOnClickListener { toggleSelectMode(true) }
        findViewById<ImageButton>(R.id.btnCancelSelect).setOnClickListener { toggleSelectMode(false) }
        findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { shareSelected() }

        loadRecordings()
    }

    override fun onDestroy() {
        super.onDestroy()
        durationExecutor.shutdownNow()
        stopPlayer()
    }

    private fun toggleSelectMode(enable: Boolean) {
        isSelectMode = enable
        if (!enable) selectedFiles.clear()
        layoutTopBar.visibility = if (enable) View.GONE else View.VISIBLE
        layoutMultiSelect.visibility = if (enable) View.VISIBLE else View.GONE
        updateSelectedCount()
        recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun updateSelectedCount() { textSelectedCount.text = "${selectedFiles.size} selected" }

    private fun deleteSelected() {
        if (selectedFiles.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete ${selectedFiles.size} recording(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                selectedFiles.forEach { file ->
                    if (currentlyPlayingFile == file.absolutePath) stopPlayer()
                    file.delete()
                    durationCache.remove(file.absolutePath) // Clear cache
                }
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                toggleSelectMode(false)
                loadRecordings()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun shareSelected() {
        if (selectedFiles.isEmpty()) return
        val uris = selectedFiles.map { FileProvider.getUriForFile(this, "${packageName}.provider", it) }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Recordings"))
    }

    private fun loadRecordings() {
        val recordDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists() || recordDir.listFiles().isNullOrEmpty()) {
            textEmptyState.visibility = View.VISIBLE; recyclerView.visibility = View.GONE; return
        }

        var files = recordDir.listFiles()?.filter { it.extension == "m4a" } ?: return

        // FINAL POLISH 1: Auto-delete ghost/corrupted files (e.g. if phone rebooted during recording)
        files.filter { it.length() < 1000 }.forEach {
            Log.w("RecordsActivity", "Deleting corrupted/empty file: ${it.name}")
            it.delete()
            durationCache.remove(it.absolutePath)
        }

        // FINAL POLISH 2: Keep storage clean (Auto-delete files older than 30 days)
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        files.filter { System.currentTimeMillis() - it.lastModified() > thirtyDaysMs }.forEach {
            Log.w("RecordsActivity", "Auto-deleting old file (>30d): ${it.name}")
            it.delete()
            durationCache.remove(it.absolutePath)
        }

        // Reload the list after cleanup
        files = recordDir.listFiles()?.filter { it.extension == "m4a" } ?: return
        if (files.isEmpty()) { textEmptyState.visibility = View.VISIBLE; recyclerView.visibility = View.GONE; return }

        textEmptyState.visibility = View.GONE; recyclerView.visibility = View.VISIBLE
        val sortedFiles = files.sortedByDescending { it.lastModified() }
        val groupedMap = sortedFiles.groupBy { SimpleDateFormat("MMMM dd, yyyy", Locale.US).format(Date(it.lastModified())) }

        val listItems = mutableListOf<ListItem>()
        for ((dateString, fileList) in groupedMap) {
            listItems.add(ListItem.HeaderItem(dateString))
            for (file in fileList) listItems.add(ListItem.RecordItem(file))
        }
        recyclerView.adapter = RecordsAdapter(listItems) { file, button -> playAudio(file, button) }
    }

    private fun playAudio(file: File, button: Button) {
        if (currentlyPlayingFile == file.absolutePath && mediaPlayer?.isPlaying == true) {
            stopPlayer()
            button.text = getString(R.string.btn_play)
            button.backgroundTintList = getColorStateList(R.color.brand_primary)
            currentlyPlayingFile = null; return
        }
        stopPlayer()
        mediaPlayer = MediaPlayer().apply {
            try { setDataSource(file.absolutePath); prepare(); start() }
            catch (e: Exception) {
                Toast.makeText(this@RecordsActivity, getString(R.string.error_could_not_play, e.message ?: "Error"), Toast.LENGTH_SHORT).show(); return@apply
            }
            setOnCompletionListener {
                button.text = getString(R.string.btn_play)
                button.backgroundTintList = getColorStateList(R.color.brand_primary)
                currentlyPlayingFile = null
            }
        }
        button.text = getString(R.string.btn_stop)
        button.backgroundTintList = getColorStateList(R.color.brand_danger)
        currentlyPlayingFile = file.absolutePath
    }

    private fun stopPlayer() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (e: Exception) { Log.e("Records", e.message ?: "Error") }
        mediaPlayer = null
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "$kb KB"
        return String.format("%.1f MB", kb / 1024.0)
    }

    // BATTERY OPTIMIZATION: Use MediaMetadataRetriever for speed, but safely fall back to MediaPlayer if it fails
    private fun getDurationBackground(file: File, callback: (String) -> Unit) {
        val cached = durationCache[file.absolutePath]
        if (cached != null) { callback(cached); return }

        durationExecutor.execute {
            var durationStr = ""
            var retriever: MediaMetadataRetriever? = null
            var mediaPlayer: MediaPlayer? = null

            try {
                // Try the fast retriever first
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                if (durMs > 0) {
                    val durSec = durMs / 1000
                    durationStr = if (durSec < 60) "${durSec}s" else "${durSec / 60}m ${durSec % 60}s"
                    durationCache[file.absolutePath] = durationStr
                }
            } catch (e: Exception) {
                Log.w("Records", "Retriever failed, falling back to MediaPlayer")
                // Fallback to the slower MediaPlayer if the OS blocks the Retriever
                try {
                    mediaPlayer = MediaPlayer()
                    mediaPlayer.setDataSource(file.absolutePath)
                    mediaPlayer.prepare()
                    val durSec = mediaPlayer.duration / 1000
                    durationStr = if (durSec < 60) "${durSec}s" else "${durSec / 60}m ${durSec % 60}s"
                    durationCache[file.absolutePath] = durationStr
                } catch (e2: Exception) {
                    Log.e("Records", "Both retriever and player failed for file: ${file.name}")
                } finally {
                    retriever?.release()
                    mediaPlayer?.release()
                }
            }

            runOnUiThread { callback(durationStr) }
        }
    }

    sealed class ListItem {
        data class HeaderItem(val date: String) : ListItem()
        data class RecordItem(val file: File) : ListItem()
    }

    inner class RecordsAdapter(private val items: List<ListItem>, private val onPlayClicked: (File, Button) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0; private val TYPE_RECORD = 1
        override fun getItemViewType(position: Int) = if (items[position] is ListItem.HeaderItem) TYPE_HEADER else TYPE_RECORD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_record_header, parent, false))
            else RecordViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is ListItem.HeaderItem) {
                holder.dateText.text = item.date
            } else if (holder is RecordViewHolder && item is ListItem.RecordItem) {
                val file = item.file
                val appName = file.nameWithoutExtension.split("_").getOrElse(0) { "Unknown" }
                val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(file.lastModified()))
                val sizeStr = formatFileSize(file.length())

                holder.fileName.text = "${appName.replaceFirstChar { it.uppercase() }} Call"
                holder.fileDetails.text = "$timeStr  ·  $sizeStr  · ..."

                getDurationBackground(file) { durStr ->
                    if (holder.fileDetails != null) holder.fileDetails.text = "$timeStr  · $sizeStr  · $durStr"
                }

                holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = selectedFiles.contains(file)
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedFiles.add(file) else selectedFiles.remove(file)
                    updateSelectedCount()
                }

                if (currentlyPlayingFile != file.absolutePath) {
                    holder.btnPlay.text = getString(R.string.btn_play)
                    holder.btnPlay.backgroundTintList = getColorStateList(R.color.brand_primary)
                } else {
                    holder.btnPlay.text = getString(R.string.btn_stop)
                    holder.btnPlay.backgroundTintList = getColorStateList(R.color.brand_danger)
                }

                holder.itemView.setOnClickListener {
                    if (isSelectMode) holder.checkBox.isChecked = !holder.checkBox.isChecked
                    else onPlayClicked(file, holder.btnPlay)
                }
                holder.itemView.setOnLongClickListener {
                    if (!isSelectMode) { toggleSelectMode(true); selectedFiles.add(file); updateSelectedCount() }; true
                }
            }
        }
        override fun getItemCount(): Int = items.size
        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) { val dateText: TextView = view.findViewById(R.id.textDateHeader) }
        inner class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileName: TextView = view.findViewById(R.id.textFileName)
            val fileDetails: TextView = view.findViewById(R.id.textFileDetails)
            val btnPlay: Button = view.findViewById(R.id.btnPlayStop)
            val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        }
    }
}