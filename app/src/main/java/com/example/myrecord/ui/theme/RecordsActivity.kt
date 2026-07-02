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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            // Stop logic
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            button.text = "PLAY"
            currentlyPlayingFile = null
            return
        }

        // Before stopping, check if media player is actually initialized
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            // Log it or ignore, we are about to overwrite it anyway
        }
        mediaPlayer = null 

        // Start new audio
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                Toast.makeText(this@RecordsActivity, "Could not play file: ${e.message}", Toast.LENGTH_SHORT).show()
                return@apply
            }
            setOnCompletionListener { 
                button.text = "PLAY" 
                currentlyPlayingFile = null
            }
        }
        button.text = "STOP"
        currentlyPlayingFile = file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
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
                holder.appNameText.text = if (parts.isNotEmpty()) parts[0] else "Unknown Call"
                
                // Format the time from the file's metadata
                holder.timeText.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date(item.file.lastModified()))

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
            val playBtn: Button = view.findViewById(R.id.btnPlay)
        }
    }
}