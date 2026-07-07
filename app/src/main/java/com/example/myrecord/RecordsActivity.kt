package com.example.myrecord

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log

class RecordsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingFile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)

        recyclerView = findViewById(R.id.recyclerViewRecords)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadRecordings()
    }

    private fun loadRecordings() {
        val recordDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists() || recordDir.listFiles() == null) return

        val files = recordDir.listFiles()?.filter { it.extension == "m4a" } ?: return

        // Sort files by newest first
        val sortedFiles = files.sortedByDescending { it.lastModified() }

        // Group the files by their Date string
        val groupedMap = sortedFiles.groupBy { file ->
            SimpleDateFormat("MMMM dd, yyyy", Locale.US).format(Date(file.lastModified()))
        }

        // Flatten the map into a single list of Items (Headers and Records mixed)
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
            // Stop logic for the currently playing file
            try {
                mediaPlayer?.apply {
                    if (isPlaying) stop()
                    release()
                }
            } catch (e: Exception) {
                Log.e("RecordsActivity", "Error releasing previous player: ${e.message}")
            }
            mediaPlayer = null

            button.setText(R.string.btn_play) // Use Resource ID
            currentlyPlayingFile = null
            return
        }

        // Stop any existing player before starting a new one
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("RecordsActivity", "Error releasing previous player: ${e.message}")
        }
        mediaPlayer = null

        // Start new audio
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                // Correct way to pass the error message into the string resource
                val errorMessage = getString(R.string.error_could_not_play, e.message)
                Toast.makeText(this@RecordsActivity, errorMessage, Toast.LENGTH_SHORT).show()
                return@apply
            }

            setOnCompletionListener {
                button.setText(R.string.btn_play) // Use Resource ID
                currentlyPlayingFile = null
            }
        }

        button.setText(R.string.btn_stop) // Use Resource ID
        currentlyPlayingFile = file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("RecordsActivity", "Error releasing player on destroy: ${e.message}")
        }
        mediaPlayer = null
    }

    // --- ADAPTER AND DATA CLASSES ---

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
                // Parse the filename (e.g. "WhatsApp_20260612_143000.m4a")
                val parts = item.file.nameWithoutExtension.split("_")
                val appName = if (parts.isNotEmpty() && parts[0].isNotBlank()) parts[0] else "Unknown"
                holder.appNameText.text = appName

                // Format the time from the file's metadata
                holder.timeText.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date(item.file.lastModified()))

                // Avatar: first letter + a color cue per source app
                holder.avatarText.text = appName.take(1).uppercase()
                val avatarColorRes = when (appName.lowercase()) {
                    "whatsapp" -> R.color.avatar_whatsapp
                    "telegram" -> R.color.avatar_telegram
                    "instagram" -> R.color.avatar_instagram
                    "snapchat" -> R.color.avatar_snapchat
                    "messenger" -> R.color.avatar_messenger
                    "cellular" -> R.color.avatar_cellular
                    else -> R.color.avatar_default
                }
                holder.avatarText.background.setTint(
                    ContextCompat.getColor(holder.itemView.context, avatarColorRes)
                )

                holder.playBtn.setOnClickListener { onPlayClicked(item.file, holder.playBtn) }
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dateText: TextView = view.findViewById(R.id.textDateHeader)
        }

        inner class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appNameText: TextView = view.findViewById(R.id.textAppName)
            val timeText: TextView = view.findViewById(R.id.textTime)
            val avatarText: TextView = view.findViewById(R.id.textAvatar)
            val playBtn: Button = view.findViewById(R.id.btnPlay)
        }
    }
}