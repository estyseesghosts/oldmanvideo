package me.foxtails.oldmanvideo

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var emptyState: TextView
    private var scanJob: Job? = null
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val folders = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .getStringSet(FOLDER_URIS_KEY, emptySet())
                    .orEmpty()
                    .toMutableSet()
                folders.add(uri.toString())
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putStringSet(FOLDER_URIS_KEY, folders)
                    .apply()
                scanSavedFolders()
            } catch (_: SecurityException) {
                // The selected tree was not granted persistable access.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoAdapter = VideoAdapter { video ->
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.VIDEO_URI_EXTRA, video.uri.toString())
            })
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = videoAdapter
        }
        emptyState = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = getString(R.string.empty_library)
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        setContentView(FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(emptyState, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
    }

    override fun onStart() {
        super.onStart()
        scanSavedFolders()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, ADD_FOLDER_MENU_ID, Menu.NONE, R.string.add_folder)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == ADD_FOLDER_MENU_ID) {
            folderPicker.launch(null)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val PREFERENCES_NAME = "video_player_preferences"
        private const val FOLDER_URIS_KEY = "folder_uris"
        private const val ADD_FOLDER_MENU_ID = 1
    }

    private fun scanSavedFolders() {
        val folderUris = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getStringSet(FOLDER_URIS_KEY, emptySet())
            .orEmpty()
            .mapNotNull { uriString ->
                runCatching { android.net.Uri.parse(uriString) }.getOrNull()
            }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                folderUris.flatMap { uri ->
                    DocumentFile.fromTreeUri(this@MainActivity, uri)
                        ?.let(::findVideos)
                        .orEmpty()
                }
                    .distinctBy(VideoFile::uri)
                    .sortedBy { it.name.lowercase() }
            }
            videoAdapter.submitList(videos)
            emptyState.visibility = if (videos.isEmpty()) TextView.VISIBLE else TextView.GONE
        }
    }

    private fun findVideos(folder: DocumentFile): List<VideoFile> {
        val result = mutableListOf<VideoFile>()
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                result += findVideos(file)
            } else if (file.isFile && isVideo(file)) {
                result += VideoFile(file.name.orEmpty(), file.uri)
            }
        }
        return result
    }

    private fun isVideo(file: DocumentFile): Boolean {
        val mimeType = file.type.orEmpty()
        if (mimeType.startsWith("video/")) return true
        return VIDEO_EXTENSIONS.any { extension ->
            file.name.orEmpty().endsWith(extension, ignoreCase = true)
        }
    }
}

private data class VideoFile(val name: String, val uri: android.net.Uri)

private class VideoAdapter(
    private val onVideoClicked: (VideoFile) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    private var videos = emptyList<VideoFile>()

    fun submitList(newVideos: List<VideoFile>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        return VideoViewHolder(TextView(parent.context).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 24f
            setPadding(16, 32, 16, 32)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    inner class VideoViewHolder(private val title: TextView) : RecyclerView.ViewHolder(title) {
        fun bind(video: VideoFile) {
            title.text = video.name
            title.setOnClickListener { onVideoClicked(video) }
        }
    }
}

private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v")