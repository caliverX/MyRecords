package com.example.myrecord

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
    private var isPreparing = false

    private lateinit var layoutTopBar: View
    private lateinit var layoutMultiSelect: View
    private lateinit var textSelectedCount: TextView

    private var isSelectMode = false
    private val selectedFiles = mutableSetOf<File>()
    private val durationExecutor = Executors.newSingleThreadExecutor()
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

    override fun onPause() {
        super.onPause()
        stopPlayer()
        currentlyPlayingFile = null
        recyclerView.adapter?.notifyDataSetChanged()
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

    private fun updateSelectedCount() {
        textSelectedCount.text = getString(R.string.selected_count, selectedFiles.size)
    }

    private fun deleteSelected() {
        if (selectedFiles.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title, selectedFiles.size))
            .setMessage(getString(R.string.dialog_delete_msg))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                selectedFiles.forEach { file ->
                    if (currentlyPlayingFile == file.absolutePath) {
                        stopPlayer()
                        currentlyPlayingFile = null
                    }
                    file.delete()
                    durationCache.remove(file.absolutePath)
                }
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                toggleSelectMode(false)
                loadRecordings()
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun shareSelected() {
        if (selectedFiles.isEmpty()) return
        val uris = selectedFiles.map { FileProvider.getUriForFile(this, "${packageName}.provider", it) }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_chooser)))
    }

    private fun loadRecordings() {
        val recordDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists() || recordDir.listFiles().isNullOrEmpty()) {
            textEmptyState.visibility = View.VISIBLE; recyclerView.visibility = View.GONE; return
        }

        var files = recordDir.listFiles()?.filter { it.extension == "m4a" } ?: return

        files.filter { it.length() < 1000 }.forEach {
            Log.w("RecordsActivity", "Deleting corrupted/empty file: ${it.name}")
            it.delete()
            durationCache.remove(it.absolutePath)
        }

        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
        files.filter {
            System.currentTimeMillis() - it.lastModified() > ninetyDaysMs && !it.name.contains("_LOCKED")
        }.forEach {
            Log.w("RecordsActivity", "Auto-deleting old file (>90d): ${it.name}")
            it.delete()
            durationCache.remove(it.absolutePath)
        }

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
        recyclerView.adapter = RecordsAdapter(listItems) { file -> playAudio(file) }
    }

    private fun playAudio(file: File) {
        if (isPreparing) return

        if (currentlyPlayingFile == file.absolutePath && mediaPlayer?.isPlaying == true) {
            stopPlayer()
            currentlyPlayingFile = null
            recyclerView.adapter?.notifyDataSetChanged()
            return
        }

        stopPlayer()
        volumeControlStream = AudioManager.STREAM_MUSIC

        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}

        currentlyPlayingFile = file.absolutePath
        isPreparing = true
        recyclerView.adapter?.notifyDataSetChanged()

        mediaPlayer = MediaPlayer()

        try {
            mediaPlayer!!.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mediaPlayer!!.setDataSource(file.absolutePath)

            mediaPlayer!!.setOnPreparedListener { mp ->
                isPreparing = false
                mp.start()
                recyclerView.adapter?.notifyDataSetChanged()
            }

            mediaPlayer!!.setOnCompletionListener {
                stopPlayer()
                currentlyPlayingFile = null
                recyclerView.adapter?.notifyDataSetChanged()
            }

            mediaPlayer!!.setOnErrorListener { mp, what, extra ->
                isPreparing = false
                Toast.makeText(this, getString(R.string.toast_playback_error, what), Toast.LENGTH_SHORT).show()
                stopPlayer()
                currentlyPlayingFile = null
                recyclerView.adapter?.notifyDataSetChanged()
                true
            }

            mediaPlayer!!.prepareAsync()
        } catch (e: Exception) {
            isPreparing = false
            Toast.makeText(this, getString(R.string.error_could_not_play, e.message ?: "Error"), Toast.LENGTH_SHORT).show()
            stopPlayer()
            currentlyPlayingFile = null
            recyclerView.adapter?.notifyDataSetChanged()
        }
    }

    private fun stopPlayer() {
        isPreparing = false
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (e: Exception) { Log.e("Records", e.message ?: "Error") }
        mediaPlayer = null
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "$kb KB"
        return String.format("%.1f MB", kb / 1024.0)
    }

    private fun getDurationBackground(file: File, callback: (String) -> Unit) {
        val cached = durationCache[file.absolutePath]
        if (cached != null) { callback(cached); return }

        durationExecutor.execute {
            var durationStr = ""
            var retriever: MediaMetadataRetriever? = null
            var tempPlayer: MediaPlayer? = null

            try {
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
                try {
                    tempPlayer = MediaPlayer()
                    tempPlayer.setDataSource(file.absolutePath)
                    tempPlayer.prepare()
                    val durSec = tempPlayer.duration / 1000
                    durationStr = if (durSec < 60) "${durSec}s" else "${durSec / 60}m ${durSec % 60}s"
                    durationCache[file.absolutePath] = durationStr
                } catch (e2: Exception) {
                    Log.e("Records", "Both retriever and player failed for file: ${file.name}")
                } finally {
                    retriever?.release()
                    tempPlayer?.release()
                }
            }
            runOnUiThread { callback(durationStr) }
        }
    }

    private fun toggleLock(file: File) {
        val isCurrentlyLocked = file.name.contains("_LOCKED")
        val newName = if (isCurrentlyLocked) {
            file.name.replace("_LOCKED", "")
        } else {
            file.name.replace(".m4a", "_LOCKED.m4a")
        }
        val newFile = File(file.parentFile, newName)

        if (file.renameTo(newFile)) {
            if (isCurrentlyLocked) {
                Toast.makeText(this, R.string.toast_saved_unlocked, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_saved_locked, Toast.LENGTH_SHORT).show()
            }
            loadRecordings()
        } else {
            Toast.makeText(this, R.string.toast_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameFile(file: File) {
        val isLocked = file.name.contains("_LOCKED")
        val currentName = file.nameWithoutExtension.replace("_LOCKED", "")

        val input = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            inputType = InputType.TYPE_CLASS_TEXT

            // 1. Force Text to be White
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            highlightColor = getColor(R.color.brand_primary)

            // 2. Force Text Box background to be Dark Grey with rounded corners
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.bg_surface_high)) // Dark grey (#1E1E1E)
                cornerRadius = 16f
            }
            background = drawable
            setPadding(30, 20, 30, 20)
        }

        // Wrap in FrameLayout to prevent touching screen edges
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(50, 20, 50, 0)
        input.layoutParams = params
        container.addView(input)

        // Create the dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val safeName = newName.replace(Regex("[^A-Za-z0-9_ -]"), "")
                    val suffix = if (isLocked) "_LOCKED.m4a" else ".m4a"
                    val newFile = File(file.parentFile, "$safeName$suffix")

                    if (file.renameTo(newFile)) {
                        Toast.makeText(this, R.string.toast_renamed, Toast.LENGTH_SHORT).show()
                        loadRecordings()
                    } else {
                        Toast.makeText(this, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        // 3. Force the Floating Window to be Black, and fix button colors
        dialog.setOnShowListener {
            // Set background to Black (bg_primary)
            dialog.window?.setBackgroundDrawableResource(R.color.bg_primary)

            // Find and fix the Title color
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(getColor(R.color.text_primary))
            }

            // Find and fix the Buttons color so they aren't invisible
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.brand_primary))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.text_secondary))
        }

        dialog.show()
    }

    sealed class ListItem {
        data class HeaderItem(val date: String) : ListItem()
        data class RecordItem(val file: File) : ListItem()
    }

    inner class RecordsAdapter(private val items: List<ListItem>, private val onPlayClicked: (File) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                val isLocked = file.name.contains("_LOCKED")

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

                holder.btnStar.setImageResource(if (isLocked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)

                val actionVisibility = if (isSelectMode) View.GONE else View.VISIBLE
                holder.btnPlay.visibility = actionVisibility
                holder.btnStar.visibility = actionVisibility
                holder.btnRename.visibility = actionVisibility

                holder.btnPlay.setOnClickListener {
                    if (isSelectMode) { holder.checkBox.isChecked = !holder.checkBox.isChecked }
                    else onPlayClicked(file)
                }

                holder.btnStar.setOnClickListener { toggleLock(file) }
                holder.btnRename.setOnClickListener { renameFile(file) }

                holder.itemView.setOnClickListener {
                    if (isSelectMode) holder.checkBox.isChecked = !holder.checkBox.isChecked
                    else onPlayClicked(file)
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
            val btnStar: ImageButton = view.findViewById(R.id.btnStar)
            val btnRename: ImageButton = view.findViewById(R.id.btnRename)
        }
    }
}