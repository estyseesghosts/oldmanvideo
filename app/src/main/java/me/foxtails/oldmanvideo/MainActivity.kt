package me.foxtails.oldmanvideo

import android.content.Intent
import android.graphics.Typeface
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
import com.google.android.material.color.MaterialColors
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        libraryRoots = libraryViewModel.libraryRoots.ifEmpty { loadCachedLibrary() }
        val shouldScanSavedFolders = libraryRoots.isEmpty() && getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getStringSet(FOLDER_URIS_KEY, emptySet())
            .orEmpty()
            .any(String::isNotBlank)
        if (libraryViewModel.libraryRoots.isEmpty() && libraryRoots.isNotEmpty()) {
            libraryViewModel.libraryRoots = libraryRoots
        }
        folderStack.addAll(libraryViewModel.folderStack)
        videoAdapter = VideoAdapter(
            onVideoClicked = { video ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.VIDEO_URI_EXTRA, video.uri.toString())
                    putExtra(PlayerActivity.EXTERNAL_SUBTITLE_EXTRA, video.externalSubtitleUri?.toString())
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
            setBackgroundColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorSurface, android.graphics.Color.DKGRAY))
            setColorSchemeColors(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorPrimary, android.graphics.Color.WHITE))
            setOnRefreshListener { scanSavedFolders() }
            addView(grid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        emptyState = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.SERIF
            setTextColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
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
        showEntries(folderStack.lastOrNull()?.children ?: rootEntries(libraryRoots))
        if (shouldScanSavedFolders) {
            swipeRefresh.isRefreshing = true
            scanSavedFolders()
        }
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
        private const val LIBRARY_CACHE_KEY = "library_cache"
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
                saveCachedLibrary(libraryRoots)
                folderStack.clear()
                libraryViewModel.folderStack = emptyList()
                showEntries(rootEntries(libraryRoots))
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
                    file.isDirectory && file.name.orEmpty().endsWith(".movie", ignoreCase = true) ->
                        buildMovieVideo(file)?.let(::add)
                    file.isDirectory -> buildFolder(file)?.let(::add)
                    file.isFile && isVideo(file) -> add(
                        LibraryVideo(
                            name = file.name.orEmpty(),
                            uri = file.uri,
                            externalSubtitleUri = findExternalSubtitle(folder, file)?.uri,
                        ),
                    )
                }
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return if (children.isEmpty()) null else LibraryFolder(
            name = folder.name.orEmpty(),
            uri = folder.uri,
            children = children,
        )
    }

    private fun buildMovieVideo(movieFolder: DocumentFile): LibraryVideo? {
        val assets = try {
            movieFolder.listFiles().toList()
        } catch (_: Exception) {
            return null
        }
        val titleFile = assets.firstOrNull { it.isFile && it.name.orEmpty().equals("title.json", ignoreCase = true) }
        val thumbnailFile = assets.firstOrNull { it.isFile && it.name.orEmpty().equals("thumbnail.png", ignoreCase = true) }
        val videoFile = assets.firstOrNull { it.isFile && it.name.orEmpty().matches(Regex("video\\..+", RegexOption.IGNORE_CASE)) }
        val subtitleFile = assets.firstOrNull { it.isFile && it.name.orEmpty().matches(Regex("subs\\..+", RegexOption.IGNORE_CASE)) }
        if (titleFile?.isFile != true || thumbnailFile?.isFile != true || videoFile?.isFile != true) return null
        val title = runCatching {
            contentResolver.openInputStream(titleFile.uri)?.bufferedReader()?.use { reader ->
                JSONObject(reader.readText()).optString("title").trim()
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return LibraryVideo(
            name = title,
            uri = videoFile.uri,
            thumbnailUri = thumbnailFile.uri,
            externalSubtitleUri = subtitleFile?.uri,
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
            showEntries(folderStack.lastOrNull()?.children ?: rootEntries(libraryRoots))
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

    private fun findExternalSubtitle(parent: DocumentFile, video: DocumentFile): DocumentFile? {
        val videoName = video.name.orEmpty()
        val baseName = videoName.substringBeforeLast('.', videoName)
        return parent.listFiles().firstOrNull { sibling ->
            sibling.isFile && sibling.name.orEmpty().equals("$baseName.srt", ignoreCase = true)
        }
    }

    private fun saveCachedLibrary(roots: List<LibraryFolder>) {
        val json = JSONArray().apply { roots.forEach { put(folderToJson(it)) } }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putString(LIBRARY_CACHE_KEY, json.toString())
            .apply()
    }

    private fun loadCachedLibrary(): List<LibraryFolder> {
        val cached = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(LIBRARY_CACHE_KEY, null)
            ?: return emptyList()
        return runCatching {
            val json = JSONArray(cached)
            (0 until json.length()).mapNotNull { index -> json.optJSONObject(index)?.let(::folderFromJson) }
        }.getOrDefault(emptyList())
    }

    private fun folderToJson(folder: LibraryFolder): JSONObject = JSONObject().apply {
        put("name", folder.name)
        put("uri", folder.uri.toString())
        put("children", JSONArray().apply {
            folder.children.forEach { child ->
                put(when (child) {
                    is LibraryFolder -> folderToJson(child)
                    is LibraryVideo -> JSONObject().apply {
                        put("type", "video")
                        put("name", child.name)
                        put("uri", child.uri.toString())
                        put("thumbnailUri", child.thumbnailUri.toString())
                        child.externalSubtitleUri?.let { put("externalSubtitleUri", it.toString()) }
                    }
                })
            }
        })
    }

    private fun folderFromJson(json: JSONObject): LibraryFolder? {
        val name = json.optString("name")
        val uri = json.optString("uri").takeIf(String::isNotEmpty)?.let(android.net.Uri::parse) ?: return null
        val childrenJson = json.optJSONArray("children") ?: return null
        val children = (0 until childrenJson.length()).mapNotNull { index ->
            val child = childrenJson.optJSONObject(index) ?: return@mapNotNull null
            if (child.optString("type") == "video") {
                val childUri = child.optString("uri").takeIf(String::isNotEmpty)?.let(android.net.Uri::parse)
                childUri?.let {
                    val subtitleUri = child.optString("externalSubtitleUri")
                        .takeIf(String::isNotEmpty)
                        ?.let(android.net.Uri::parse)
                    val thumbnailUri = child.optString("thumbnailUri")
                        .takeIf(String::isNotEmpty)
                        ?.let(android.net.Uri::parse)
                        ?: it
                    LibraryVideo(child.optString("name"), it, thumbnailUri, subtitleUri)
                }
            } else {
                folderFromJson(child)
            }
        }
        return LibraryFolder(name, uri, children)
    }
}

class LibraryViewModel : ViewModel() {
    var libraryRoots: List<LibraryFolder> = emptyList()
    var folderStack: List<LibraryFolder> = emptyList()
}

sealed interface LibraryEntry {
    val name: String
    val thumbnailUri: android.net.Uri
}

internal fun <T> flattenRootEntries(roots: List<List<T>>): List<T> = roots.flatten()

internal fun rootEntries(roots: List<LibraryFolder>): List<LibraryEntry> =
    flattenRootEntries(roots.map { it.children })

data class LibraryVideo(
    override val name: String,
    val uri: android.net.Uri,
    override val thumbnailUri: android.net.Uri = uri,
    val externalSubtitleUri: android.net.Uri? = null,
) : LibraryEntry

data class LibraryFolder(
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
            typeface = Typeface.SERIF
            setTextColor(MaterialColors.getColor(parent.context, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
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