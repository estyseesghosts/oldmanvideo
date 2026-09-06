package me.foxtails.oldmanvideo

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

class MainActivity : AppCompatActivity() {
    private val libraryViewModel: LibraryViewModel by viewModels()
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var emptyState: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var scanJob: Job? = null
    private var libraryRoots = emptyList<LibraryFolder>()
    private val folderStack = ArrayDeque<LibraryFolder>()
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
        libraryRoots = libraryViewModel.libraryRoots
        folderStack.addAll(libraryViewModel.folderStack)
        videoAdapter = VideoAdapter(
            onVideoClicked = { video ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.VIDEO_URI_EXTRA, video.uri.toString())
                })
            },
            onFolderClicked = { folder ->
                folderStack.addLast(folder)
                libraryViewModel.folderStack = folderStack.toList()
                showEntries(folder.children)
            },
        )
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, gridSpanCount())
            adapter = videoAdapter
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setOnRefreshListener { scanSavedFolders() }
            addView(grid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        emptyState = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = getString(R.string.empty_library)
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        setContentView(FrameLayout(this).apply {
            addView(swipeRefresh, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(emptyState, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
        showEntries(folderStack.lastOrNull()?.children ?: libraryRoots)
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
            try {
                val roots = withContext(Dispatchers.IO) {
                    folderUris.flatMap { uri ->
                        DocumentFile.fromTreeUri(this@MainActivity, uri)
                            ?.let { root -> buildFolder(root) }
                            ?.let(::listOf)
                            .orEmpty()
                    }
                }
                libraryRoots = roots.sortedBy { it.name.lowercase(Locale.ROOT) }
                libraryViewModel.libraryRoots = libraryRoots
                folderStack.clear()
                libraryViewModel.folderStack = emptyList()
                showEntries(libraryRoots)
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun buildFolder(folder: DocumentFile): LibraryFolder? {
        coroutineContext.ensureActive()
        val files = try {
            folder.listFiles()
        } catch (_: Exception) {
            return null
        }
        val children = buildList {
            for (file in files) {
                coroutineContext.ensureActive()
                when {
                    file.isDirectory -> buildFolder(file)?.let(::add)
                    file.isFile && isVideo(file) -> add(LibraryVideo(file.name.orEmpty(), file.uri))
                }
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return if (children.isEmpty()) null else LibraryFolder(
            name = folder.name.orEmpty(),
            uri = folder.uri,
            children = children,
        )
    }

    private fun showEntries(entries: List<LibraryEntry>) {
        videoAdapter.submitList(entries)
        emptyState.visibility = if (entries.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    private fun gridSpanCount(): Int = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    @Deprecated("Use OnBackPressedDispatcher for new code")
    override fun onBackPressed() {
        if (folderStack.isNotEmpty()) {
            folderStack.removeLast()
            libraryViewModel.folderStack = folderStack.toList()
            showEntries(folderStack.lastOrNull()?.children ?: libraryRoots)
        } else {
            super.onBackPressed()
        }
    }

    private fun isVideo(file: DocumentFile): Boolean {
        val mimeType = file.type.orEmpty()
        if (mimeType.startsWith("video/")) return true
        return VIDEO_EXTENSIONS.any { extension ->
            file.name.orEmpty().endsWith(extension, ignoreCase = true)
        }
    }
}

private class LibraryViewModel : ViewModel() {
    var libraryRoots: List<LibraryFolder> = emptyList()
    var folderStack: List<LibraryFolder> = emptyList()
}

private sealed interface LibraryEntry {
    val name: String
    val thumbnailUri: android.net.Uri
}

private data class LibraryVideo(
    override val name: String,
    val uri: android.net.Uri,
) : LibraryEntry {
    override val thumbnailUri: android.net.Uri get() = uri
}

private data class LibraryFolder(
    override val name: String,
    val uri: android.net.Uri,
    val children: List<LibraryEntry>,
) : LibraryEntry {
    override val thumbnailUri: android.net.Uri get() = children.first().thumbnailUri
}

private class VideoAdapter(
    private val onVideoClicked: (LibraryVideo) -> Unit,
    private val onFolderClicked: (LibraryFolder) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    private var entries = emptyList<LibraryEntry>()

    fun submitList(newEntries: List<LibraryEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val image = AspectRatioImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val title = TextView(parent.context).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            setPadding(8, 8, 8, 16)
        }
        return VideoViewHolder(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }, image, title)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class VideoViewHolder(
        itemView: LinearLayout,
        private val thumbnail: ImageView,
        private val title: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(entry: LibraryEntry) {
            title.text = entry.name
            thumbnail.load(entry.thumbnailUri) {
                crossfade(true)
                placeholder(R.drawable.placeholder)
                error(R.drawable.placeholder)
            }
            itemView.setOnClickListener {
                when (entry) {
                    is LibraryVideo -> onVideoClicked(entry)
                    is LibraryFolder -> onFolderClicked(entry)
                }
            }
        }
    }
}

private class AspectRatioImageView(context: android.content.Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth * 4 / 3)
    }
}

private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v")