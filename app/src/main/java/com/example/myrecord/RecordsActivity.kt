package com.example.myrecord

import android.content.Intent
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

class RecordsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var textEmptyState: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingFile: String? = null

    // UI Elements for Multi-Select
    private lateinit var layoutTopBar: View
    private lateinit var layoutMultiSelect: View
    private lateinit var textSelectedCount: TextView

    private var isSelectMode = false
    private val selectedFiles = mutableSetOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)

        recyclerView = findViewById(R.id.recyclerViewRecords)
        textEmptyState = findViewById(R.id.textEmptyState)
        recyclerView.layoutManager = LinearLayoutManager(this)

        layoutTopBar = findViewById(R.id.layoutTopBar)
        layoutMultiSelect = findViewById(R.id.layoutMultiSelect)
        textSelectedCount = findViewById(R.id.textSelectedCount)

        val btnBack: ImageButton = findViewById(R.id.btnBack)
        val btnSelect: TextView = findViewById(R.id.btnSelect)
        val btnCancelSelect: ImageButton = findViewById(R.id.btnCancelSelect)
        val btnDelete: ImageButton = findViewById(R.id.btnDelete)
        val btnShare: ImageButton = findViewById(R.id.btnShare)

        btnBack.setOnClickListener { finish() }
        btnSelect.setOnClickListener { toggleSelectMode(true) }
        btnCancelSelect.setOnClickListener { toggleSelectMode(false) }
        btnDelete.setOnClickListener { deleteSelected() }
        btnShare.setOnClickListener { shareSelected() }

        loadRecordings()
    }

    private fun toggleSelectMode(enable: Boolean) {
        isSelectMode = enable
        if (!enable) selectedFiles.clear()
        layoutTopBar.visibility = if (enable) View.GONE else View.VISIBLE
        layoutMultiSelect.visibility = if (enable) View.VISIBLE else View.GONE
        updateSelectedCount()
        recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun updateSelectedCount() {
        textSelectedCount.text = "${selectedFiles.size} selected"
    }

    private fun deleteSelected() {
        if (selectedFiles.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedFiles.size} recording(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                // Stop playing if the currently playing file is being deleted
                selectedFiles.forEach { file ->
                    if (currentlyPlayingFile == file.absolutePath) stopPlayer()
                    file.delete()
                }
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                toggleSelectMode(false)
                loadRecordings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSelected() {
        if (selectedFiles.isEmpty()) return
        val uris = selectedFiles.map { file ->
            FileProvider.getUriForFile(this, "${packageName}.provider", file)
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Recordings"))
    }

    private fun loadRecordings() {
        val recordDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists()) {
            textEmptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        val files = recordDir.listFiles()?.filter { it.extension == "m4a" } ?: return
        if (files.isEmpty()) {
            textEmptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        textEmptyState.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE

        val sortedFiles = files.sortedByDescending { it.lastModified() }

        val groupedMap = sortedFiles.groupBy { file ->
            SimpleDateFormat("MMMM dd, yyyy", Locale.US).format(Date(file.lastModified()))
        }

        val listItems = mutableListOf<ListItem>()
        for ((dateString, fileList) in groupedMap) {
            listItems.add(ListItem.HeaderItem(dateString))
            for (file in fileList) {
                listItems.add(ListItem.RecordItem(file))
            }
        }

        recyclerView.adapter = RecordsAdapter(listItems) { file, button ->
            playAudio(file, button)
        }
    }

    private fun playAudio(file: File, button: Button) {
        if (currentlyPlayingFile == file.absolutePath && mediaPlayer?.isPlaying == true) {
            stopPlayer()
            button.text = getString(R.string.btn_play)
            button.backgroundTintList = getColorStateList(R.color.brand_primary)
            currentlyPlayingFile = null
            return
        }

        stopPlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                val errorMessage = getString(R.string.error_could_not_play, e.message ?: "Unknown error")
                Toast.makeText(this@RecordsActivity, errorMessage, Toast.LENGTH_SHORT).show()
                return@apply
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
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (e: Exception) { Log.e("Records", e.message ?: "Unknown error") }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayer()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "$kb KB"
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    // --- DATA CLASSES & ADAPTER ---
    sealed class ListItem {
        data class HeaderItem(val date: String) : ListItem()
        data class RecordItem(val file: File) : ListItem()
    }

    inner class RecordsAdapter(
        private val items: List<ListItem>,
        private val onPlayClicked: (File, Button) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_RECORD = 1

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is ListItem.HeaderItem) TYPE_HEADER else TYPE_RECORD
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
                RecordViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is ListItem.HeaderItem) {
                holder.dateText.text = item.date
            } else if (holder is RecordViewHolder && item is ListItem.RecordItem) {
                val file = item.file

                val appName = file.nameWithoutExtension.split("_").getOrElse(0) { "Unknown" }

                // FIX: Reliably use file.lastModified() for the time display
                val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(file.lastModified()))

                var durationStr = ""
                var tempPlayer: MediaPlayer? = null
                try {
                    tempPlayer = MediaPlayer()
                    tempPlayer.setDataSource(file.absolutePath)
                    tempPlayer.prepare()
                    val dur = tempPlayer.duration / 1000
                    durationStr = if (dur < 60) "${dur}s" else "${dur / 60}m ${dur % 60}s"
                } catch (e: Exception) {
                    durationStr = ""
                } finally {
                    tempPlayer?.release()
                }

                val sizeStr = formatFileSize(file.length())

                holder.fileName.text = "${appName.replaceFirstChar { it.uppercase() }} Call"
                holder.fileDetails.text = "$timeStr  ·  $sizeStr  ·  $durationStr"

                // Handle Select Mode UI
                holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = selectedFiles.contains(file)
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedFiles.add(file) else selectedFiles.remove(file)
                    updateSelectedCount()
                }

                // Reset play button visuals
                if (currentlyPlayingFile != file.absolutePath) {
                    holder.btnPlay.text = getString(R.string.btn_play)
                    holder.btnPlay.backgroundTintList = getColorStateList(R.color.brand_primary)
                } else {
                    holder.btnPlay.text = getString(R.string.btn_stop)
                    holder.btnPlay.backgroundTintList = getColorStateList(R.color.brand_danger)
                }

                val playButton = holder.btnPlay

                // Tap behavior
                holder.itemView.setOnClickListener {
                    if (isSelectMode) {
                        holder.checkBox.isChecked = !holder.checkBox.isChecked
                    } else {
                        onPlayClicked(file, playButton)
                    }
                }

                // Long press to enter select mode and select this item instantly
                holder.itemView.setOnLongClickListener {
                    if (!isSelectMode) {
                        toggleSelectMode(true)
                        selectedFiles.add(file)
                        updateSelectedCount()
                    }
                    true
                }
            }
        }

        override fun getItemCount(): Int = items.size

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dateText: TextView = view.findViewById(R.id.textDateHeader)
        }

        inner class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileName: TextView = view.findViewById(R.id.textFileName)
            val fileDetails: TextView = view.findViewById(R.id.textFileDetails)
            val btnPlay: Button = view.findViewById(R.id.btnPlayStop)
            val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        }
    }
}