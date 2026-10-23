package com.reelvault.app

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.reelvault.app.databinding.ActivityMainBinding
import com.reelvault.app.databinding.DialogLinkBinding
import com.reelvault.app.databinding.ItemSheetRowBinding
import com.reelvault.app.databinding.SheetActionsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), FeedAdapter.Callbacks {

    private enum class Access { FULL, PARTIAL, NONE }

    private lateinit var b: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private val feedAdapter = FeedAdapter(this)
    private val gridAdapter = GridAdapter(onOpen = ::openFromGrid, onMenu = ::showMenu)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("reelvault", MODE_PRIVATE) }

    private var entries: List<MediaEntry> = emptyList()
    private val remote = mutableListOf<Clip>()
    private var filter: String? = null
    private var clips: List<Clip> = emptyList()
    private var visibleEntries: List<MediaEntry> = emptyList()
    private val pendingFolders = mutableSetOf<String>()
    private var pendingFocus: String? = null

    private var currentPos = 0
    private var currentKey: String? = null
    private var playingKey: String? = null
    private var activeHolder: FeedAdapter.Holder? = null
    private var embedWeb: WebView? = null
    private var userPaused = false
    private var boosting = false
    private var muted = false
    private var speedIdx = 0
    private val speeds = floatArrayOf(1f, 1.5f, 2f)
    private var showingLibrary = false
    private var loadingSnack: Snackbar? = null

    private var pendingConsent: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val action = pendingConsent
        pendingConsent = null
        if (res.resultCode == RESULT_OK) action?.invoke()
    }

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            ui.removeCallbacks(reloadRunnable)
            ui.postDelayed(reloadRunnable, 700)
        }
    }
    private val reloadRunnable = Runnable { reload() }

    private val ticker = object : Runnable {
        override fun run() {
            if (clips.getOrNull(currentPos)?.kind == Kind.VIDEO) {
                activeHolder?.updateProgress(player.currentPosition, player.duration)
            }
            ui.postDelayed(this, 200)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        pendingFolders += prefs.getStringSet("pendingFolders", emptySet()).orEmpty()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_ONE }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                activeHolder?.setBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) activeHolder?.showError(false)
            }

            override fun onPlayerError(error: PlaybackException) {
                activeHolder?.showError(true)
            }
        })

        b.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        b.pager.adapter = feedAdapter
        b.pager.offscreenPageLimit = 1
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!showingLibrary) activate(position)
            }
        })

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = gridAdapter
        b.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (b.grid.paddingTop != v.height) b.grid.updatePadding(top = v.height)
        }

        val brand = SpannableString("reel vault")
        brand.setSpan(ForegroundColorSpan(color(R.color.green)), 5, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.brand.text = brand
        b.tabFeed.setOnClickListener { setTab(false) }
        b.tabLibrary.setOnClickListener { setTab(true) }
        b.btnLink.setOnClickListener { showLinkDialog() }
        b.accessBanner.setOnClickListener { requestAccess() }
        renderTabs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showingLibrary -> setTab(false)
                    filter != null -> selectFilter(null)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)

        // Ask for the gallery straight away on first launch.
        if (access() == Access.NONE && !prefs.getBoolean("asked", false)) requestAccess()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        reload()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        player.pause()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(mediaObserver)
        destroyEmbed()
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- permissions

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun access(): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        else -> Access.NONE
    }

    private fun requestAccess() {
        val perms = mediaPermissions()
        val asked = prefs.getBoolean("asked", false)
        val canPrompt = !asked || access() == Access.PARTIAL || perms.any { shouldShowRequestPermissionRationale(it) }
        if (canPrompt) {
            prefs.edit().putBoolean("asked", true).apply()
            permLauncher.launch(perms)
        } else {
            // Android stops showing the prompt after two refusals; the switch is in app settings.
            snack("Turn on Photos and videos in Permissions")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        val a = access()
        lifecycleScope.launch {
            entries = if (a == Access.NONE) emptyList() else withContext(Dispatchers.IO) {
                runCatching { MediaRepository.load(this@MainActivity) }.getOrDefault(emptyList())
            }
            rebuild()
        }
    }

    private fun rebuild(focusKey: String? = null) {
        val keep = focusKey ?: pendingFocus ?: currentKey
        val buckets = entries.map { it.bucket }.toSet()
        if (pendingFolders.removeAll { it in buckets }) savePending()
        if (filter != null && filter !in buckets && filter !in pendingFolders) filter = null

        visibleEntries = if (filter == null) entries else entries.filter { it.bucket == filter }
        val newClips = (if (filter == null) remote.toList() else emptyList()) + visibleEntries.map { it.toClip() }
        clips = newClips
        feedAdapter.submitList(newClips) {
            if (newClips.isEmpty()) {
                stopPlayback()
                currentKey = null
            } else {
                val found = newClips.indexOfFirst { it.key == keep }
                if (found >= 0 && keep == pendingFocus) pendingFocus = null
                val idx = if (found >= 0) found else currentPos.coerceIn(0, newClips.size - 1)
                if (b.pager.currentItem != idx) b.pager.setCurrentItem(idx, false)
                b.pager.post { if (!showingLibrary) activate(b.pager.currentItem) }
            }
        }
        gridAdapter.submitList(visibleEntries)
        buildChips()
        renderEmpty()
    }

    private fun savePending() = prefs.edit().putStringSet("pendingFolders", pendingFolders.toSet()).apply()

    private fun folderNames(): List<String> {
        val counts = entries.groupingBy { it.bucket }.eachCount()
        return counts.entries.sortedByDescending { it.value }.map { it.key } + pendingFolders.filter { it !in counts }.sorted()
    }

    // ---------------------------------------------------------------- playback

    private fun holderAt(pos: Int): FeedAdapter.Holder? =
        (b.pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? FeedAdapter.Holder

    private fun activate(pos: Int, attempt: Int = 0) {
        val clip = clips.getOrNull(pos) ?: return
        val holder = holderAt(pos)
        if (holder == null) {
            if (attempt < 8) b.pager.postDelayed({ if (b.pager.currentItem == pos) activate(pos, attempt + 1) }, 60)
            return
        }
        val changed = currentKey != clip.key
        if (changed) userPaused = false
        currentPos = pos
        currentKey = clip.key
        if (activeHolder !== holder) activeHolder?.b?.playerView?.player = null
        if (changed || activeHolder !== holder) destroyEmbed()
        activeHolder = holder

        when (clip.kind) {
            Kind.VIDEO -> {
                holder.b.playerView.player = player
                if (playingKey != clip.key) {
                    holder.showError(false)
                    player.setMediaItem(MediaItem.fromUri(clip.uri ?: return))
                    player.prepare()
                    playingKey = clip.key
                }
                applyRate()
                applyVolume()
                player.playWhenReady = !userPaused && !showingLibrary && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                holder.setPaused(userPaused)
            }
            Kind.IMAGE -> stopPlayback()
            Kind.EMBED -> {
                stopPlayback()
                if (embedWeb == null && !showingLibrary) showEmbed(holder, clip)
            }
        }
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        playingKey = null
    }

    private fun showEmbed(holder: FeedAdapter.Holder, clip: Clip) {
        val url = clip.embedUrl ?: return
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(0xFF000000.toInt())
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        holder.b.webHolder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadUrl(url, mapOf("Referer" to "https://$packageName"))
        embedWeb = web
    }

    private fun destroyEmbed() {
        embedWeb?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        embedWeb = null
    }

    private fun applyRate() = player.setPlaybackSpeed(if (boosting) 2f else speeds[speedIdx])
    private fun applyVolume() {
        player.volume = if (muted) 0f else 1f
    }

    private fun refreshRails() {
        val rv = b.pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? FeedAdapter.Holder)?.renderRail()
    }

    // ---------------------------------------------------------------- feed callbacks

    override fun onTap(clip: Clip) {
        if (clip.kind != Kind.VIDEO || clip.key != currentKey) return
        userPaused = !userPaused
        player.playWhenReady = !userPaused
        activeHolder?.setPaused(userPaused)
    }

    override fun onBoost(on: Boolean) {
        boosting = on
        if (on && userPaused) {
            userPaused = false
            player.playWhenReady = true
            activeHolder?.setPaused(false)
        }
        applyRate()
        b.boostPill.animate().alpha(if (on) 1f else 0f).setDuration(120).start()
    }

    override fun onMenu(clip: Clip) {
        val e = clip.entry
        when {
            e != null -> showMenu(e)
            clip.savable -> snack("Save it first, then you can put it in a folder", "Save") { onSave(clip) }
        }
    }

    override fun onSave(clip: Clip) {
        val src = clip.uri ?: return
        if (clip.saving) return
        updateRemote(clip.key) { it.copy(saving = true) }
        lifecycleScope.launch {
            try {
                val name = MediaRepository.cleanName(clip.caption.take(50)) ?: "reel_${System.currentTimeMillis()}"
                val saved = withContext(Dispatchers.IO) {
                    if (src.scheme?.startsWith("http") == true) MediaRepository.saveFromUrl(this@MainActivity, src.toString(), name)
                    else MediaRepository.saveFromUri(this@MainActivity, src, name)
                }
                remote.removeAll { it.key == clip.key }
                pendingFocus = "m:${ContentUris.parseId(saved)}"
                snack("Saved to ${if (clip.kind == Kind.IMAGE) "Pictures" else "Movies"}/${MediaRepository.APP_FOLDER}")
                if (access() == Access.NONE) rebuild() else reload()
            } catch (ex: Exception) {
                updateRemote(clip.key) { it.copy(saving = false) }
                snack("Couldn't save: ${ex.message ?: "the download failed"}")
            }
        }
    }

    override fun onToggleSound() {
        muted = !muted
        applyVolume()
        refreshRails()
    }

    override fun onCycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        applyRate()
        refreshRails()
    }

    override fun onSeek(fraction: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * fraction).toLong())
    }

    override fun onOpenOriginal(clip: Clip) {
        val link = clip.link ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(link)), "Open with"))
        } catch (_: ActivityNotFoundException) {
            snack("No app can open that link")
        }
    }

    override fun speedLabel(): String = when (speeds[speedIdx]) {
        1.5f -> "1.5×"
        else -> "${speeds[speedIdx].toInt()}×"
    }

    override fun isMuted() = muted

    private fun updateRemote(key: String, f: (Clip) -> Clip) {
        val i = remote.indexOfFirst { it.key == key }
        if (i >= 0) {
            remote[i] = f(remote[i])
            rebuild()
        }
    }

    // ---------------------------------------------------------------- top bar, tabs, folders

    private fun setTab(library: Boolean) {
        showingLibrary = library
        b.grid.isVisible = library
        b.pager.isVisible = !library
        if (library) {
            player.pause()
            destroyEmbed()
            b.topBar.setBackgroundColor(color(R.color.ink))
        } else {
            b.topBar.setBackgroundResource(R.drawable.bg_top_shade)
            b.pager.post { activate(b.pager.currentItem) }
        }
        renderTabs()
        renderEmpty()
    }

    private fun renderTabs() {
        listOf(b.tabFeed to !showingLibrary, b.tabLibrary to showingLibrary).forEach { (tv, on) ->
            if (on) tv.setBackgroundResource(R.drawable.bg_tab_on) else tv.background = null
            tv.setTextColor(color(if (on) R.color.ink else R.color.muted))
        }
    }

    private fun selectFilter(name: String?) {
        filter = name
        currentKey = null
        currentPos = 0
        rebuild()
    }

    private fun buildChips() {
        b.chips.removeAllViews()
        val counts = entries.groupingBy { it.bucket }.eachCount()
        b.chips.addView(chip("All", entries.size, filter == null) { selectFilter(null) })
        folderNames().forEach { name ->
            b.chips.addView(chip(name, counts[name] ?: 0, filter == name) { selectFilter(name) })
        }
        b.chips.addView(chip("+ New folder", null, on = false, outlined = true) { promptNewFolder(null) })
    }

    private fun chip(label: String, count: Int?, on: Boolean, outlined: Boolean = false, click: () -> Unit) = Chip(this).apply {
        text = if (count != null) "$label  $count" else label
        isCheckable = false
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = dp(34f)
        chipBackgroundColor = ColorStateList.valueOf(
            when {
                on -> color(R.color.green)
                outlined -> 0x00000000
                else -> color(R.color.glass)
            },
        )
        setTextColor(color(if (on) R.color.green_ink else if (outlined) R.color.muted else R.color.fg))
        chipStrokeWidth = if (outlined) dp(1f) else 0f
        chipStrokeColor = ColorStateList.valueOf(color(R.color.line))
        setOnClickListener { click() }
    }

    private fun renderEmpty() {
        val a = access()
        val listEmpty = if (showingLibrary) visibleEntries.isEmpty() else clips.isEmpty()
        b.accessBanner.isVisible = a != Access.FULL && !listEmpty
        b.accessBanner.text = if (a == Access.PARTIAL) "Showing only the items you picked. Tap to allow all."
        else "Tap to let Reel Vault see your videos and photos."
        b.empty.isVisible = listEmpty
        if (!listEmpty) return

        b.emptySecondary.isVisible = true
        when {
            a == Access.NONE -> {
                b.emptyIcon.setImageResource(R.drawable.ic_shield)
                b.emptyTitle.text = "See all your videos"
                b.emptyText.text = "Reel Vault plays the videos and photos already on your phone. Nothing is uploaded."
                b.emptyPrimary.text = "Allow access"
                b.emptyPrimary.setOnClickListener { requestAccess() }
                b.emptySecondary.text = "Play a link instead"
                b.emptySecondary.setOnClickListener { showLinkDialog() }
            }
            filter != null -> {
                b.emptyIcon.setImageResource(R.drawable.ic_folder)
                b.emptyTitle.text = "$filter is empty"
                b.emptyText.text = "Hold the middle of any video, or hold a tile in Library, and choose a folder."
                b.emptyPrimary.text = "Show all videos"
                b.emptyPrimary.setOnClickListener { selectFilter(null) }
                b.emptySecondary.isVisible = false
            }
            else -> {
                b.emptyIcon.setImageResource(R.drawable.ic_play)
                b.emptyTitle.text = "No videos yet"
                b.emptyText.text = "Videos and photos on your phone show up here. You can also play a TikTok, Reels, Shorts or X link."
                b.emptyPrimary.text = "Play a link"
                b.emptyPrimary.setOnClickListener { showLinkDialog() }
                b.emptySecondary.isVisible = a == Access.PARTIAL
                b.emptySecondary.text = "Allow all videos"
                b.emptySecondary.setOnClickListener { requestAccess() }
            }
        }
    }

    private fun openFromGrid(e: MediaEntry) {
        currentKey = "m:${e.id}"
        val idx = clips.indexOfFirst { it.key == currentKey }
        setTab(false)
        if (idx >= 0) {
            currentPos = idx
            b.pager.setCurrentItem(idx, false)
            b.pager.post { activate(idx) }
        }
    }

    // ---------------------------------------------------------------- hold menu

    private fun showMenu(e: MediaEntry) {
        val dialog = BottomSheetDialog(this)
        val sb = SheetActionsBinding.inflate(layoutInflater)
        val word = if (e.kind == Kind.VIDEO) "video" else "photo"
        sb.title.text = e.name
        sb.subtitle.text = "${word.replaceFirstChar { it.uppercase() }} · in ${e.bucket} · ${fmtSize(e.size)}"

        fun row(@DrawableRes icon: Int, text: String, @ColorRes tint: Int = R.color.fg, checked: Boolean = false, onClick: () -> Unit) {
            val r = ItemSheetRowBinding.inflate(layoutInflater, sb.list, false)
            r.icon.setImageResource(icon)
            r.icon.imageTintList = ColorStateList.valueOf(color(if (tint == R.color.fg) R.color.muted else tint))
            r.label.text = text
            r.label.setTextColor(color(tint))
            r.check.isVisible = checked
            r.check.imageTintList = ColorStateList.valueOf(color(R.color.green))
            r.root.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            sb.list.addView(r.root)
        }

        row(R.drawable.ic_delete, "Delete", R.color.error) { delete(e) }
        row(R.drawable.ic_create_new_folder, "Create new folder") { promptNewFolder(e) }
        row(R.drawable.ic_share, "Share") { share(e) }
        val label = layoutInflater.inflate(R.layout.item_sheet_label, sb.list, false) as TextView
        label.text = "Add to folder"
        sb.list.addView(label)
        folderNames().forEach { name ->
            val here = name == e.bucket
            row(R.drawable.ic_folder, name, if (here) R.color.green else R.color.fg, checked = here) {
                if (!here) moveTo(e, name)
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !MediaStore.canManageMedia(this)) {
            row(R.drawable.ic_shield, "Stop Android asking before every move or delete", R.color.muted) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) {
                    snack("Your phone doesn't offer that setting")
                }
            }
        }
        dialog.setContentView(sb.root)
        dialog.show()
    }

    private fun consent(sender: IntentSender, then: () -> Unit) {
        pendingConsent = then
        consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    private fun delete(e: MediaEntry) {
        if (Build.VERSION.SDK_INT >= 30) {
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(e.uri))
            consent(request.intentSender) {
                snack("Deleted")
                reload()
            }
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { contentResolver.delete(e.uri, null, null) }
                snack("Deleted")
                reload()
            } catch (ex: RecoverableSecurityException) {
                consent(ex.userAction.actionIntent.intentSender) { delete(e) }
            } catch (ex: Exception) {
                snack("Couldn't delete: ${ex.message ?: "Android refused"}")
            }
        }
    }

    private fun moveTo(e: MediaEntry, folder: String) {
        val paths = entries.filter { it.bucket == folder }.map { it.relativePath }.distinct()
        val target = MediaRepository.targetPath(e.kind, folder, paths)
        if (target == e.relativePath) {
            snack("Already in $folder")
            return
        }
        val doMove: () -> Unit = {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { MediaRepository.move(this@MainActivity, e.uri, target) }
                    pendingFolders.remove(folder)
                    savePending()
                    snack("Moved to $folder")
                    reload()
                } catch (ex: RecoverableSecurityException) {
                    consent(ex.userAction.actionIntent.intentSender) { moveTo(e, folder) }
                } catch (ex: Exception) {
                    snack("Couldn't move it: ${ex.message ?: "Android refused"}")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) consent(MediaStore.createWriteRequest(contentResolver, listOf(e.uri)).intentSender, doMove)
        else doMove()
    }

    private fun promptNewFolder(e: MediaEntry?) {
        val input = EditText(this).apply {
            hint = "Folder name"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(24), dpi(8), dpi(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New folder")
            .setMessage(if (e != null) "The ${if (e.kind == Kind.VIDEO) "video" else "photo"} moves into it." else "Hold any video and choose this folder to add it.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (e != null) "Create and move" else "Create") { _, _ ->
                val name = MediaRepository.cleanName(input.text.toString())
                when {
                    name == null -> snack("Give the folder a name")
                    e != null -> moveTo(e, name)
                    else -> {
                        if (name !in folderNames()) pendingFolders += name
                        savePending()
                        selectFilter(name)
                    }
                }
            }
            .show()
        input.requestFocus()
    }

    private fun share(e: MediaEntry) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (e.kind == Kind.VIDEO) "video/*" else "image/*")
            .putExtra(Intent.EXTRA_STREAM, e.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share"))
    }

    // ---------------------------------------------------------------- links and sharing in

    private fun showLinkDialog(prefill: String? = null, error: String? = null) {
        val db = DialogLinkBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this).setView(db.root).create()
        val start = prefill ?: clipboardText()?.takeIf { LinkResolver.parse(it) != null }
        if (start != null) {
            db.input.setText(start)
            db.input.setSelection(db.input.text.length)
        }
        db.help.text = error ?: "TikTok and X videos can be saved to your phone. Shorts and Reels play in their own player."
        db.help.setTextColor(color(if (error != null) R.color.error else R.color.muted))
        db.btnPaste.setOnClickListener {
            val t = clipboardText()
            if (t.isNullOrBlank()) snack("Nothing copied yet") else {
                db.input.setText(t.trim())
                db.input.setSelection(db.input.text.length)
            }
        }
        val go = {
            val t = db.input.text.toString().trim()
            if (t.isNotEmpty()) {
                dialog.dismiss()
                openLink(t)
            }
        }
        db.btnPlay.setOnClickListener { go() }
        db.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                go()
                true
            } else false
        }
        dialog.show()
    }

    private fun clipboardText(): String? = runCatching {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    private fun openLink(text: String) {
        loadingSnack?.dismiss()
        loadingSnack = Snackbar.make(b.root, "Loading video…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
        lifecycleScope.launch {
            try {
                val clip = withContext(Dispatchers.IO) { LinkResolver.resolve(text) }.toClip()
                loadingSnack?.dismiss()
                remote.removeAll { it.key == clip.key }
                remote.add(0, clip)
                filter = null
                if (showingLibrary) setTab(false)
                rebuild(focusKey = clip.key)
                currentKey = null // force the new clip to start
                b.pager.post { activate(b.pager.currentItem) }
            } catch (ex: Exception) {
                loadingSnack?.dismiss()
                val msg = (ex as? LinkResolver.LinkError)?.message
                    ?: "Couldn't load that video. Check your connection and try again."
                showLinkDialog(text, msg)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) addShared(listOf(stream), intent.type)
                else {
                    val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString(" ")
                    if (text.isNotBlank()) openLink(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { addShared(it, intent.type) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") openLink(uri.toString()) else addShared(listOf(uri), intent.type)
            }
            else -> return
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun addShared(uris: List<Uri>, type: String?) {
        val added = uris.mapNotNull { u ->
            val mime = runCatching { contentResolver.getType(u) }.getOrNull() ?: type.orEmpty()
            val kind = when {
                mime.startsWith("video") -> Kind.VIDEO
                mime.startsWith("image") -> Kind.IMAGE
                else -> return@mapNotNull null
            }
            val onPhone = u.authority == MediaStore.AUTHORITY
            Clip(
                key = "shared:$u",
                kind = kind,
                uri = u,
                handle = "Shared with Reel Vault",
                caption = MediaRepository.displayName(this, u) ?: if (kind == Kind.VIDEO) "Shared video" else "Shared photo",
                tag = if (onPhone) "Already on your phone" else "Not saved yet",
                savable = !onPhone,
            )
        }
        if (added.isEmpty()) {
            snack("Reel Vault opens videos and photos")
            return
        }
        remote.removeAll { r -> added.any { it.key == r.key } }
        remote.addAll(0, added)
        filter = null
        if (showingLibrary) setTab(false)
        rebuild(focusKey = added.first().key)
    }

    // ---------------------------------------------------------------- helpers

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun snack(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val s = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) s.setAction(action) { onAction() }
        s.setActionTextColor(color(R.color.green))
        s.show()
    }
}
