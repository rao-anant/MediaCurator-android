package com.anant.mediacurator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.anant.mediacurator.databinding.ActivityMediaViewerBinding
import com.anant.mediacurator.databinding.ItemViewerMediaBinding

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: MediaPagerAdapter
    
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressAction: Runnable? = null

    // ID of the item currently being renamed. While non-negative:
    //   • prevents the viewer from closing if flatMediaItems briefly goes empty
    //     (MediaStore re-indexes the renamed file, postValue coalesces isLoading, so
    //      the isEmpty+!isLoading guard fires even though the file still exists)
    //   • restores the current page by ID after any list reload during the rename window
    private var renamingItemId = -1L

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            viewModel.onDeletePermissionResult(success)
            // Don't finish here — flatMediaItems update will advance to the next item,
            // and the viewer closes automatically when the list becomes empty.
        }

    private val renameLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onRenamePermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    companion object {
        const val EXTRA_START_POSITION = "extra_start_position"
        const val EXTRA_START_ID = "extra_start_id"
        // Optional explicit, ordered id list to page through (Gallery month / Search / Hidden).
        // When absent, the viewer falls back to the full gallery list via loadMedia().
        const val EXTRA_ID_LIST = "extra_id_list"
        // DEBUG: show item id + type + position on each viewer page. Flip to true to diagnose; ship false.
        const val DEBUG_OVERLAY = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Material3 auto-applies colorControlNormal as imageTintList on ImageButtons.
        // Null it out so our vector's hardcoded white fill renders directly.
        binding.btnShare.imageTintList = null
        binding.btnDelete.imageTintList = null
        binding.btnRename.imageTintList = null

        // Push the bottom toolbar above the system navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            (binding.bottomToolbar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = navBars.bottom
                binding.bottomToolbar.layoutParams = it
            }
            insets
        }

        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        val startPosition = intent.getIntExtra(EXTRA_START_POSITION, 0)

        viewModel.flatMediaItems.observe(this) { mediaItems ->
            if (mediaItems.isEmpty() && viewModel.isLoading.value == false) {
                // Don't close the viewer while a rename is in progress — MediaStore may
                // briefly not return the renamed file while it re-indexes it on disk.
                if (renamingItemId == -1L) finish()
                return@observe
            }

            if (mediaItems.isNotEmpty()) {
                if (!::adapter.isInitialized) {
                    adapter = MediaPagerAdapter(mediaItems) { finish() }
                    binding.viewPager.adapter = adapter

                    // Find position by ID if provided, else fallback to index
                    val initialPos = if (startId != -1L) {
                        mediaItems.indexOfFirst { it.id == startId }.takeIf { it != -1 } ?: startPosition
                    } else {
                        startPosition
                    }

                    binding.viewPager.setCurrentItem(initialPos, false)

                    binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            if (position < mediaItems.size) {
                                updateUIForItem(mediaItems[position])
                            }
                        }
                    })
                    if (initialPos < mediaItems.size) {
                        updateUIForItem(mediaItems[initialPos])
                    }
                } else {
                    val prevPos = binding.viewPager.currentItem
                    adapter.updateItems(mediaItems)

                    val rId = renamingItemId
                    when {
                        // Rename in progress: find the item by its original ID and stay on it.
                        // The item may have shifted position if Android assigned a new MediaStore
                        // ID for the renamed file, so ID-lookup is more reliable than prevPos.
                        rId != -1L -> {
                            val pos = mediaItems.indexOfFirst { it.id == rId }
                            if (pos != -1) {
                                binding.viewPager.setCurrentItem(pos, false)
                                updateUIForItem(mediaItems[pos])
                            }
                            // If pos == -1 the file is still being indexed — keep waiting;
                            // renamingItemId will be cleared when renameResult fires.
                        }
                        // Normal case (deletion, sort change, etc.) — back up if past end
                        prevPos >= mediaItems.size -> {
                            val newPos = mediaItems.size - 1
                            binding.viewPager.setCurrentItem(newPos, false)
                            updateUIForItem(mediaItems[newPos])
                        }
                    }
                }
            }
        }

        viewModel.deletePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }

        // Soft-delete is silent (no per-delete snackbar). Instead, a ↶ button appears in the
        // bottom toolbar whenever there's a recoverable last batch — one-tap undo right here.
        binding.btnUndoDelete.imageTintList = null
        binding.btnUndoDelete.setOnClickListener { viewModel.restoreLastBatch() }
        viewModel.lastBatchSize.observe(this) { n ->
            binding.btnUndoDelete.isVisible = (n ?: 0) > 0
        }

        viewModel.renamePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                renameLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }

        viewModel.renameResult.observe(this) { result ->
            result ?: return@observe   // null = idle, nothing to show
            renamingItemId = -1L       // rename complete (success or failure) — lift the guard
            if (result.isNotEmpty()) {
                Toast.makeText(this, "Renamed to \"$result\"", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
            }
            viewModel.clearRenameResult()
        }

        // deletionCompletedEvent no longer closes the viewer — the flatMediaItems
        // observer advances to the next item and closes only when the list is empty.

        binding.btnOpenInGallery.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val item = adapter.getItem(binding.viewPager.currentItem)
                val mime = when (item.type) {
                    MediaType.IMAGE -> "image/*"
                    MediaType.VIDEO -> "video/*"
                    MediaType.AUDIO -> "audio/*"
                    MediaType.PDF   -> return@setOnClickListener
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(item.uri), mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "No gallery app found", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnShare.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val currentItem = adapter.getItem(binding.viewPager.currentItem)
                shareMedia(currentItem)
            }
        }

        binding.btnDelete.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val currentItem = adapter.getItem(binding.viewPager.currentItem)
                viewModel.deleteMedia(listOf(currentItem))
            }
        }

        binding.btnRename.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val currentItem = adapter.getItem(binding.viewPager.currentItem)
                showRenameDialog(currentItem)
            }
        }

        binding.videoScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    getCurrentViewHolder()?.binding?.videoView?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val idList = intent.getLongArrayExtra(EXTRA_ID_LIST)
        if (idList != null && idList.isNotEmpty()) {
            // Page exactly the list the caller provided (month / search / hidden), by id.
            viewModel.loadExplicit(idList)
        } else {
            viewModel.loadMedia()
        }
    }

    private fun getCurrentViewHolder(): MediaPagerAdapter.ViewHolder? {
        if (!::binding.isInitialized) return null
        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView
        return recyclerView?.findViewHolderForAdapterPosition(binding.viewPager.currentItem) as? MediaPagerAdapter.ViewHolder
    }

    private fun updateUIForItem(item: MediaItem) {
        val isVideo = item.type == MediaType.VIDEO
        binding.videoScrubber.isVisible = isVideo
        binding.btnOpenInGallery.isVisible = item.type != MediaType.PDF
        
        stopProgressUpdate()
        if (isVideo) {
            startProgressUpdate()
        }
    }

    private fun startProgressUpdate() {
        updateProgressAction = object : Runnable {
            override fun run() {
                val videoView = getCurrentViewHolder()?.binding?.videoView
                if (videoView != null && videoView.isPlaying) {
                    binding.videoScrubber.max = videoView.duration
                    binding.videoScrubber.progress = videoView.currentPosition
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateProgressAction!!)
    }

    private fun stopProgressUpdate() {
        updateProgressAction?.let { handler.removeCallbacks(it) }
        updateProgressAction = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdate()
    }

    private fun showRenameDialog(item: MediaItem) {
        val fullName = item.displayName
        val dotIdx = fullName.lastIndexOf('.')
        val baseName = if (dotIdx > 0) fullName.substring(0, dotIdx) else fullName
        val ext = if (dotIdx > 0) fullName.substring(dotIdx) else ""  // e.g. ".mp3" (includes dot)

        val input = EditText(this).apply {
            setText(baseName)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            if (ext.isNotEmpty()) {
                val hint = TextView(context).apply {
                    text = "Extension \"$ext\" will be preserved"
                    textSize = 12f
                    alpha = 0.6f
                    setPadding(0, 6, 0, 0)
                }
                addView(hint, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                val newBase = input.text.toString().trim()
                if (newBase.isNotEmpty()) {
                    renamingItemId = item.id   // raise guard before IO starts
                    viewModel.initiateRename(item, newBase + ext)
                }
            }
            .create()

        // Force the keyboard to appear automatically when the dialog opens —
        // more reliable than showSoftInput() for dialog windows.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()

        // Select all text after the window is attached and focus is settled.
        // Keyboard is already coming up via setSoftInputMode, so the user can
        // just start typing to replace the highlighted name.
        input.post {
            input.requestFocus()
            input.selectAll()
        }
    }

    private fun shareMedia(item: MediaItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = when (item.type) {
                MediaType.IMAGE -> "image/*"
                MediaType.VIDEO -> "video/*"
                MediaType.AUDIO -> "audio/*"
                MediaType.PDF   -> "application/pdf"
            }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    inner class MediaPagerAdapter(
        private var items: List<MediaItem>,
        private val onShortPress: () -> Unit
    ) : RecyclerView.Adapter<MediaPagerAdapter.ViewHolder>() {

        fun updateItems(newItems: List<MediaItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun getItem(position: Int) = items[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemViewerMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(val binding: ItemViewerMediaBinding) : RecyclerView.ViewHolder(binding.root) {
            private var pdfJob: Job? = null

            fun bind(item: MediaItem) {
                // Reset state from previous bind.  ViewPager2 recycles ViewHolders, so any
                // state a prior item type set must be cleared here — otherwise e.g. a PDF/audio
                // page's "Tap to open" overlay leaks onto a recycled video/image page.
                pdfJob?.cancel()
                pdfJob = null
                binding.videoView.stopPlayback()
                binding.photoView.setOnPhotoTapListener(null)
                binding.photoView.setOnOutsidePhotoTapListener(null)
                binding.photoView.setImageDrawable(null)
                binding.tvError.isVisible = false
                binding.tvError.text = ""
                binding.tvPdfHint.isVisible = false
                binding.tvPdfHint.setOnClickListener(null)
                binding.videoContainer.setOnClickListener(null)

                if (DEBUG_OVERLAY) {
                    binding.tvDebugId.isVisible = true
                    binding.tvDebugId.text = "id=${item.id}  ${item.type}  [${bindingAdapterPosition + 1}/${items.size}]"
                } else {
                    binding.tvDebugId.isVisible = false
                }

                when (item.type) {
                    MediaType.IMAGE -> {
                        binding.photoView.isVisible = true
                        binding.videoContainer.isVisible = false
                        Glide.with(binding.photoView).load(item.uri).into(binding.photoView)
                        binding.photoView.setOnPhotoTapListener { _, _, _ -> onShortPress() }
                        binding.photoView.setOnOutsidePhotoTapListener { onShortPress() }
                    }
                    MediaType.VIDEO -> {
                        binding.photoView.isVisible = false
                        binding.videoContainer.isVisible = true
                        binding.btnPlayPause.isVisible = false
                        binding.videoView.setVideoURI(Uri.parse(item.uri))
                        binding.videoView.setOnPreparedListener { mp ->
                            mp.start()
                            binding.btnPlayPause.isVisible = false
                        }
                        binding.videoView.setOnErrorListener { _, _, _ ->
                            binding.btnPlayPause.isVisible = true
                            true
                        }
                        binding.videoContainer.setOnClickListener { onShortPress() }
                        binding.btnPlayPause.setOnClickListener {
                            binding.videoView.start()
                            binding.btnPlayPause.isVisible = false
                        }
                        binding.videoView.setOnClickListener {
                            if (binding.videoView.isPlaying) {
                                binding.videoView.pause()
                                binding.btnPlayPause.isVisible = true
                            } else {
                                binding.videoView.start()
                                binding.btnPlayPause.isVisible = false
                            }
                        }
                    }
                    MediaType.AUDIO -> {
                        binding.photoView.isVisible = false
                        binding.videoContainer.isVisible = true
                        binding.btnPlayPause.isVisible = false
                        binding.tvError.isVisible = true
                        binding.tvError.text = "Tap to play audio"
                        binding.videoContainer.setOnClickListener {
                            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uri), "audio/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                binding.root.context.startActivity(openIntent)
                            } catch (e: Exception) {
                                try {
                                    binding.root.context.startActivity(
                                        Intent.createChooser(openIntent, "Play audio with")
                                    )
                                } catch (e2: Exception) { /* no audio player installed */ }
                            }
                        }
                    }
                    MediaType.PDF -> {
                        // Render the first page so the viewer shows the document (like the grid),
                        // with a persistent "Tap to open PDF" hint.  Tapping launches an external
                        // PDF viewer — PDFs can't be read inline here.
                        binding.videoContainer.isVisible = false
                        binding.photoView.isVisible = true
                        binding.photoView.setImageDrawable(null)
                        binding.tvPdfHint.isVisible = true

                        val open = {
                            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uri), "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                binding.root.context.startActivity(openIntent)
                            } catch (e: Exception) {
                                try {
                                    binding.root.context.startActivity(
                                        Intent.createChooser(openIntent, "Open PDF with")
                                    )
                                } catch (e2: Exception) { /* no PDF viewer installed */ }
                            }
                        }
                        binding.photoView.setOnPhotoTapListener { _, _, _ -> open() }
                        binding.photoView.setOnOutsidePhotoTapListener { open() }
                        // Hint is always tappable, even before/if the page render finishes.
                        binding.tvPdfHint.setOnClickListener { open() }

                        val appContext = binding.root.context.applicationContext
                        val uri = Uri.parse(item.uri)
                        val targetId = item.id
                        binding.photoView.tag = targetId
                        pdfJob = lifecycleScope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                try {
                                    appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                                            renderer.openPage(0).use { page ->
                                                val target = 1440
                                                val scale  = target.toFloat() / maxOf(page.width, page.height)
                                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                                bmp.eraseColor(android.graphics.Color.WHITE)
                                                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                bmp
                                            }
                                        }
                                    }
                                } catch (e: Exception) { null }
                            }
                            // Guard against view recycling: only apply if still bound to this item.
                            if (binding.photoView.tag == targetId && bitmap != null) {
                                binding.photoView.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }
    }
}
