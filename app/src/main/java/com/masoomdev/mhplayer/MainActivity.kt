package com.masoomdev.mhplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.provider.MediaStore
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val dark = Color(0xFF0D1020)
private val purple = Color(0xFF9B72FF)
private data class LocalMedia(val title: String, val uri: Uri, val video: Boolean, val duration: Long, val folder: String, val size: Long, val dateAdded: Long)

class MainActivity : ComponentActivity() {
    var pipActive by mutableStateOf(false)
        private set
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive = isInPictureInPictureMode
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13,16,32)
        window.navigationBarColor = android.graphics.Color.rgb(13,16,32)
        setContent { PlayerApp(this, intent?.data) }
    }
}

private fun scanMedia(activity: ComponentActivity): List<LocalMedia> {
    val output = mutableListOf<LocalMedia>()
    val permissionVideo = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    val permissionAudio = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    for (video in listOf(true, false)) {
        if ((video && !permissionVideo) || (!video && !permissionAudio)) continue
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED) else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
        try {
            activity.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val folderIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) output.add(LocalMedia(cursor.getString(nameIndex) ?: "Unknown", ContentUris.withAppendedId(base, cursor.getLong(idIndex)), video, cursor.getLong(durationIndex), if (folderIndex >= 0) (cursor.getString(folderIndex) ?: "Other").trimEnd('/') else "Device storage", if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L, if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L))
            }
        } catch (_: Exception) { }
    }
    return output
}

@Composable
private fun PlayerApp(activity: MainActivity, initialUri: Uri?) {
    val context = LocalContext.current
    val pipActive = activity.pipActive
    val prefs = remember { activity.getSharedPreferences("mh_player", 0) }
    val player = remember {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
    }
    DisposableEffect(player) { onDispose { player.release(); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    var splash by remember { mutableStateOf(true) }
    var exitPrompt by remember { mutableStateOf(false) }
    var streamDialog by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var streamError by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var quickSettingsOpen by remember { mutableStateOf(false) }
    var infoPage by remember { mutableStateOf<String?>(null) }
    var equalizerOpen by remember { mutableStateOf(false) }
    var equalizerEnabled by remember { mutableStateOf(prefs.getBoolean("eq_enabled", false)) }
    var eqLevels by remember { mutableStateOf(List(5) { prefs.getInt("eq_band_$it", 0) }) }
    var eqSession by remember { mutableIntStateOf(C.AUDIO_SESSION_ID_UNSET) }
    var eqAvailable by remember { mutableStateOf(true) }
    var eqBandCount by remember { mutableIntStateOf(5) }
    var eqRange by remember { mutableStateOf(-1500..1500) }
    var eqEffect by remember { mutableStateOf<Equalizer?>(null) }
    var autoHideSeconds by remember { mutableIntStateOf(prefs.getInt("auto_hide", 5)) }
    var resumePlayback by remember { mutableStateOf(prefs.getBoolean("resume", true)) }
    var autoPlay by remember { mutableStateOf(prefs.getBoolean("autoplay", true)) }
    var backgroundPlay by remember { mutableStateOf(prefs.getBoolean("background_play", false)) }
    var brightnessGesture by remember { mutableStateOf(prefs.getBoolean("brightness_gesture", true)) }
    var volumeGesture by remember { mutableStateOf(prefs.getBoolean("volume_gesture", true)) }
    var media by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalMedia?>(null) }
    var showVideo by remember { mutableStateOf(true) }
    var currentFolder by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(prefs.getString("sort_mode", "Name") ?: "Name") }
    var sortDescending by remember { mutableStateOf(prefs.getBoolean("sort_desc", false)) }
    var libraryView by remember { mutableStateOf(prefs.getString("library_view", "Folders") ?: "Folders") }
    var gridView by remember { mutableStateOf(prefs.getBoolean("grid_view", false)) }
    var libraryOptions by remember { mutableStateOf(false) }
    var libraryLoading by remember { mutableStateOf(false) }
    var listSettingsOpen by remember { mutableStateOf(false) }
    var markLastPlayed by remember { mutableStateOf(prefs.getBoolean("list_last_played", true)) }
    var scrollToLast by remember { mutableStateOf(prefs.getBoolean("list_scroll_last", false)) }
    var floatingResume by remember { mutableStateOf(prefs.getBoolean("list_floating_resume", false)) }
    var newTagDays by remember { mutableIntStateOf(prefs.getInt("list_new_days", 7)) }
    var includeAndroidMedia by remember { mutableStateOf(prefs.getBoolean("include_android_media", false)) }
    var videoLayout by remember { mutableStateOf(prefs.getString("video_layout", "Classic") ?: "Classic") }
    var audioLayout by remember { mutableStateOf(prefs.getString("audio_layout", "Classic") ?: "Classic") }
    var themeChoice by remember { mutableStateOf(prefs.getString("ui_theme", "Purple") ?: "Purple") }
    val dark = when (themeChoice) { "White" -> Color(0xFFF8F9FD); "Dark" -> Color(0xFF111318); else -> Color(0xFF0D1020) }
    val purple = when (themeChoice) { "White" -> Color(0xFF4259A7); "Dark" -> Color(0xFF8EA3FF); else -> Color(0xFF9B72FF) }
    val foreground = if (themeChoice == "White") Color(0xFF151827) else Color.White
    val secondaryText = if (themeChoice == "White") Color(0xFF565D70) else Color.LightGray
    SideEffect {
        val barColor = if (themeChoice == "White") android.graphics.Color.rgb(248,249,253)
            else if (themeChoice == "Dark") android.graphics.Color.rgb(17,19,24)
            else android.graphics.Color.rgb(13,16,32)
        activity.window.statusBarColor = barColor
        activity.window.navigationBarColor = barColor
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (themeChoice == "White")
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }
    var showHidden by remember { mutableStateOf(prefs.getBoolean("list_show_hidden", false)) }
    var includedFolders by remember { mutableStateOf(prefs.getString("list_folders", "") ?: "") }
    var foldersDialog by remember { mutableStateOf(false) }
    var lastPlayedUri by remember { mutableStateOf(prefs.getString("last_played_uri", "") ?: "") }
    val scope = rememberCoroutineScope()
    fun refreshMedia() { scope.launch { libraryLoading = true; media = withContext(Dispatchers.IO) { scanMedia(activity) }; libraryLoading = false } }
    var locked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var gestureLevel by remember { mutableFloatStateOf(-1f) }
    var gestureBrightness by remember { mutableStateOf(true) }
    var speedMenu by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf(false) }
    var subtitleDialog by remember { mutableStateOf(false) }
    var trackRevision by remember { mutableIntStateOf(0) }
    var resizeChoice by remember { mutableIntStateOf(0) }
    var pinchZoom by remember { mutableFloatStateOf(1f) }
    var repeatOne by remember { mutableStateOf(false) }
    var seekSeconds by remember { mutableLongStateOf(prefs.getLong("seek_seconds", 10L)) }
    DisposableEffect(selected != null, pipActive) {
        val window = activity.window
        if (selected != null && !pipActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
    }
    var holdSpeedActive by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf("") }
    LaunchedEffect(selected?.uri, controlsVisible, controlsInteraction, locked, trackDialog, subtitleDialog, speedMenu, quickSettingsOpen, autoHideSeconds) {
        if (selected != null && controlsVisible && !locked && !trackDialog && !subtitleDialog && !speedMenu && !quickSettingsOpen) {
            delay(autoHideSeconds * 1000L)
            controlsVisible = false
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackRevision++ }
            override fun onPlaybackStateChanged(playbackState: Int) { trackRevision++ }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (selected?.video == true && videoSize.width > 0 && videoSize.height > 0) {
                    val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
                    activity.requestedOrientation = if (displayWidth > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(eqSession, equalizerEnabled) {
        var effect: Equalizer? = null
        if (equalizerEnabled && eqSession != C.AUDIO_SESSION_ID_UNSET) {
            try {
                effect = Equalizer(0, eqSession)
                eqBandCount = effect.numberOfBands.toInt()
                val range = effect.bandLevelRange
                eqRange = range[0].toInt()..range[1].toInt()
                effect.enabled = true
                eqAvailable = true
                eqEffect = effect
            } catch (_: Exception) {
                eqAvailable = false
                eqEffect = null
                effect?.release()
                effect = null
            }
        } else eqEffect = null
        onDispose {
            if (eqEffect === effect) eqEffect = null
            effect?.release()
        }
    }
    LaunchedEffect(eqEffect, eqLevels) {
        val effect = eqEffect ?: return@LaunchedEffect
        try {
            for (band in 0 until effect.numberOfBands.toInt()) {
                val value = eqLevels.getOrElse(band) { 0 }.coerceIn(eqRange.first, eqRange.last)
                effect.setBandLevel(band.toShort(), value.toShort())
            }
        } catch (_: Exception) { eqAvailable = false }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player, backgroundPlay, pipActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !backgroundPlay && !pipActive && !activity.isInPictureInPictureMode) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selected = LocalMedia(uri.lastPathSegment ?: "Opened file", uri, true, 0L, "Opened files", 0L, 0L)
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val current = selected
            if (current != null) {
                val position = player.currentPosition
                val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(if (uri.toString().lowercase().endsWith(".vtt")) "text/vtt" else "application/x-subrip")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
                player.setMediaItem(MediaItem.Builder().setUri(current.uri).setSubtitleConfigurations(listOf(subtitle)).build())
                player.prepare(); player.seekTo(position); player.play()
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshMedia() }
    LaunchedEffect(Unit) {
        delay(1200)
        splash = false
        val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else media = withContext(Dispatchers.IO) { scanMedia(activity) }
        if (initialUri != null) selected = LocalMedia("Opened file", initialUri, true, 0L, "Opened files", 0L, 0L)
    }
    LaunchedEffect(selected?.uri) {
        val item = selected ?: return@LaunchedEffect
        pinchZoom = 1f
        holdSpeedActive = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        lastPlayedUri = item.uri.toString()
        prefs.edit().putString("last_played_uri", lastPlayedUri).apply()
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
        val saved = prefs.getLong("position_${item.uri}", 0L)
        if (resumePlayback && saved > 0) player.seekTo(saved)
        player.playWhenReady = autoPlay
        locked = false
        controlsVisible = true
        controlsInteraction++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(selected?.uri) {
        val current = selected ?: return@LaunchedEffect
        var lastSavedAt = 0L
        while (selected?.uri == current.uri) {
            val session = player.audioSessionId
            if (eqSession != session) eqSession = session
            val nextProgress = player.currentPosition.coerceAtLeast(0L)
            val nextDuration = player.duration.coerceAtLeast(0L)
            val nextPlaying = player.isPlaying
            if (progress != nextProgress) progress = nextProgress
            if (duration != nextDuration) duration = nextDuration
            if (playing != nextPlaying) playing = nextPlaying
            val now = android.os.SystemClock.elapsedRealtime()
            if (nextProgress > 0 && now - lastSavedAt >= 5000L) {
                prefs.edit().putLong("position_${current.uri}", nextProgress).apply()
                lastSavedAt = now
            }
            delay(750)
        }
    }
    fun closePlayer() {
        selected?.let { prefs.edit().putLong("position_${it.uri}", player.currentPosition.coerceAtLeast(0L)).apply() }
        player.pause(); if (holdSpeedActive) player.setPlaybackSpeed(playbackSpeed); holdSpeedActive = false; selected = null; locked = false; pinchZoom = 1f; activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    BackHandler(enabled = !pipActive) { if (quickSettingsOpen) quickSettingsOpen = false else if (exitPrompt) exitPrompt = false else if (selected != null) { if (locked) locked = false else closePlayer() } else if (infoPage != null) infoPage = null else if (settingsOpen) settingsOpen = false else if (currentFolder != null) currentFolder = null else exitPrompt = true }
    if (streamDialog) AlertDialog(
        onDismissRequest = { streamDialog = false },
        title = { Text("Network stream") },
        text = {
            Column {
                Text("Paste a direct video or audio URL (HTTPS). MP4, MP3, HLS (.m3u8) and DASH (.mpd) supported.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it; streamError = "" },
                    label = { Text("Stream URL") },
                    singleLine = true
                )
                if (streamError.isNotEmpty()) Text(streamError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = streamUrl.trim()
                val parsed = Uri.parse(url)
                if (parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank()) {
                    streamError = "Enter a valid HTTPS stream URL"
                } else {
                    selected = LocalMedia(parsed.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Network stream", parsed, true, 0L, "Network", 0L, 0L)
                    streamDialog = false
                }
            }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = { streamDialog = false }) { Text("Cancel") } }
    )
    if (trackDialog || subtitleDialog) {
        val isAudio = trackDialog
        val trackType = if (isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val revision = trackRevision
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        val tracksLoading = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
        AlertDialog(
            onDismissRequest = { trackDialog = false; subtitleDialog = false },
            title = { Text(if (isAudio) "Audio track selection" else "Subtitle selection") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, !isAudio).build()
                        trackDialog = false; subtitleDialog = false
                    }) { Text(if (isAudio) "Automatic audio" else "Subtitles off") }
                    groups.forEachIndexed { groupNumber, group ->
                        for (index in 0 until group.length) {
                            val format = group.getTrackFormat(index)
                            val supported = group.isTrackSupported(index)
                            val languageCode = format.language?.takeUnless { it.isBlank() || it == "und" }
                            val languageName = languageCode?.let { code ->
                                java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
                                    .takeUnless { it.isBlank() || it.equals(code, ignoreCase = true) }
                            }
                            val trackName = format.label?.takeUnless { it.isBlank() }
                                ?: languageName?.replaceFirstChar { it.uppercase() }
                                ?: (if (isAudio) "Audio" else "Subtitle") + " ${groupNumber + 1}.${index + 1}"
                            val codec = format.sampleMimeType?.substringAfter('/') ?: "unknown codec"
                            val detail = listOfNotNull(
                                languageCode?.uppercase(),
                                if (isAudio) codec.uppercase() else null,
                                if (!supported) "Unsupported codec" else null
                            ).joinToString(" • ")
                            TextButton(
                                enabled = supported,
                                onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .clearOverridesOfType(trackType)
                                        .setTrackTypeDisabled(trackType, false)
                                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                                        .build()
                                    trackDialog = false; subtitleDialog = false
                                }
                            ) {
                                Column {
                                    Text((if (group.isTrackSelected(index)) "✓ " else "") + trackName)
                                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (groups.isEmpty()) Text(if (tracksLoading) "Loading embedded tracks..." else "No embedded tracks found in this file")
                    if (groups.isNotEmpty() && groups.none { group -> (0 until group.length).any { group.isTrackSupported(it) } })
                        Text("This file contains tracks, but their codecs are not supported by this device.")
                    if (revision < 0) Text("")
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = false; subtitleDialog = false }) { Text("Close") } }
        )
    }
    if (equalizerOpen) AlertDialog(
        onDismissRequest = { equalizerOpen = false },
        title = { Text("Audio Equalizer") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("Applies to video and audio played in MH Player")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Flat", "Bass", "Treble").forEach { preset ->
                        TextButton(onClick = {
                            val n = eqBandCount.coerceAtLeast(1)
                            val max = eqRange.last.coerceAtLeast(0)
                            val next = List(n) { index ->
                                when (preset) {
                                    "Bass" -> if (index < n / 2) max / 2 else 0
                                    "Treble" -> if (index >= n / 2) max / 2 else 0
                                    else -> 0
                                }
                            }
                            eqLevels = next
                            next.forEachIndexed { i, v -> prefs.edit().putInt("eq_band_$i", v).apply() }
                        }) { Text(preset) }
                    }
                }
                for (band in 0 until eqBandCount.coerceIn(1, 10)) {
                    val level = eqLevels.getOrElse(band) { 0 }
                    val hz = try { eqEffect?.getCenterFreq(band.toShort())?.div(1000) } catch (_: Exception) { null }
                    Text(if (hz != null) "$hz Hz: ${level / 100} dB" else "Band ${band + 1}: ${level / 100} dB")
                    Slider(
                        value = level.toFloat().coerceIn(eqRange.first.toFloat(), eqRange.last.toFloat()),
                        onValueChange = { value ->
                            val next = eqLevels.toMutableList()
                            while (next.size <= band) next.add(0)
                            next[band] = value.toInt()
                            eqLevels = next
                            prefs.edit().putInt("eq_band_$band", value.toInt()).apply()
                        },
                        valueRange = eqRange.first.toFloat()..eqRange.last.toFloat()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { equalizerOpen = false }) { Text("Done") } }
    )
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Exit MH Player?") }, text = { Text("Are you sure you want to close MH Player?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit app") } }, dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Stay in app") } })
    // Keep the active player mounted while changing its settings.
    MaterialTheme(colorScheme = if (themeChoice == "White")
        lightColorScheme(primary = purple, background = dark, surface = Color.White, onBackground = foreground, onSurface = foreground)
        else darkColorScheme(primary = purple, background = dark,
            surface = if (themeChoice == "Dark") Color(0xFF20232A) else Color(0xFF1A2034))) {
        if (quickSettingsOpen && selected != null && !pipActive) {
            AlertDialog(
                onDismissRequest = { quickSettingsOpen = false },
                title = { Text("Playback settings") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text("PLAYER DESIGN", color = MaterialTheme.colorScheme.primary)
                        Text(if (selected?.video == true) "Video layout" else "Audio layout")
                        val options = if (selected?.video == true) listOf("Classic", "Modern", "Cinema")
                            else listOf("Classic", "Modern", "Studio")
                        options.forEach { option ->
                            val active = if (selected?.video == true) videoLayout == option else audioLayout == option
                            Row(Modifier.fillMaxWidth().clickable {
                                if (selected?.video == true) {
                                    videoLayout = option
                                    prefs.edit().putString("video_layout", option).apply()
                                } else {
                                    audioLayout = option
                                    prefs.edit().putString("audio_layout", option).apply()
                                }
                                controlsVisible = true
                                controlsInteraction++
                            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = active, onClick = null)
                                Text(option)
                            }
                        }
                        HorizontalDivider()
                        Text("AUDIO", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                        SettingsToggle("Equalizer", "Apply audio effects during playback", equalizerEnabled) {
                            equalizerEnabled = it
                            prefs.edit().putBoolean("eq_enabled", it).apply()
                        }
                        TextButton(onClick = {
                            quickSettingsOpen = false
                            equalizerOpen = true
                        }) { Text("Equalizer bands & presets") }
                        if (equalizerEnabled && !eqAvailable) {
                            Text("Equalizer is unavailable on this device.", color = MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                        Text("PLAYBACK", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                        SettingsToggle("Resume playback", "Remember playback position", resumePlayback) {
                            resumePlayback = it; prefs.edit().putBoolean("resume", it).apply()
                        }
                        SettingsToggle("Background playback", "Continue audio outside the app", backgroundPlay) {
                            backgroundPlay = it; prefs.edit().putBoolean("background_play", it).apply()
                        }
                        Text("THEME", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("White", "Dark", "Purple").forEach { option ->
                                FilterChip(selected = themeChoice == option, onClick = {
                                    themeChoice = option; prefs.edit().putString("ui_theme", option).apply()
                                }, label = { Text(option) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { quickSettingsOpen = false; controlsVisible = true; controlsInteraction++ }) {
                        Text("Done")
                    }
                }
            )
        }
        if (splash) {
            Box(Modifier.fillMaxSize().background(dark), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PlayCircle, null, Modifier.size(86.dp), tint = purple); Spacer(Modifier.height(12.dp)); Text("MH PLAYER", fontSize = 32.sp, color = foreground); Text("Your media. Your device.", color = secondaryText) }
            }
        } else if (infoPage != null) {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { infoPage = null }) { Icon(Icons.Default.ArrowBack, "Back", tint = foreground) }
                    Text(infoPage ?: "", fontSize = 24.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        when (infoPage) {
                            "About" -> """MH Player
Version 1.0.0

Developed by MasoomDev.

A lightweight offline audio and video player for files stored on your device.

Features include folder browsing, playback resume, audio equalizer, gesture controls, subtitles and optional background playback.

No account or sign-in is required."""
                            "Privacy Policy" -> """MH Player Privacy Policy
Last updated: 18 October 2026

MH Player is an offline media player developed by MasoomDev.

Media access: With your permission, MH Player reads video and audio files on your device to show your media library and play selected files. It does not upload your media to a server.

Local data: Playback positions, equalizer settings and player preferences are saved locally on your device. You can remove this app data using Android settings or by uninstalling the app.

Data collection: The current app does not contain advertising SDKs, analytics SDKs, account registration or cloud syncing. The app does not intentionally transmit personal data to the developer.

Permissions: Audio and video access permissions are used for browsing and playing local files. Android audio settings access is used for audio effects.

Children: MH Player is a general-purpose local media player and does not provide child-directed online services.

Changes: This policy may be updated if app functionality changes. A public version will be made available on the developer's website before publication.

Contact: Please use the support contact provided on the developer's website or Google Play listing."""
                            "Terms & Conditions" -> """MH Player Terms & Conditions
Last updated: 18 October 2026

MH Player is provided as a local media playback utility.

You are responsible for ensuring that you have the right to access and play your media files. MH Player does not supply, host or distribute video or audio content.

Media format support depends on your device and Android decoding capabilities. Playback features and audio effects may vary by device.

The app is provided as-is to the extent permitted by applicable law. Nothing in these terms excludes rights that cannot legally be excluded.

For support, use the contact details on the developer website or Google Play listing."""
                            else -> """Open Source Licenses

MH Player uses open-source components including:

AndroidX Media3 / ExoPlayer — Apache License 2.0
AndroidX libraries and Jetpack Compose — Apache License 2.0
Kotlin — Apache License 2.0

Copyright and license notices for dependencies remain with their respective owners.

Full license texts and dependency notices should be made available on the developer website or in the release package before publication."""
                        },
                        color = foreground,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                    )
                }
            }
        } else if (listSettingsOpen) {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { listSettingsOpen = false }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = foreground)
                    }
                    Text("List", fontSize = 26.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("APPEARANCE", color = purple, modifier = Modifier.padding(top = 20.dp))
                    SettingsToggle("Last media in each folder", "Mark the last played media in the library.", markLastPlayed) {
                        markLastPlayed = it; prefs.edit().putBoolean("list_last_played", it).apply()
                    }
                    SettingsToggle("Scroll down to last media", "Auto-scroll to the last played item when opening its folder.", scrollToLast) {
                        scrollToLast = it; prefs.edit().putBoolean("list_scroll_last", it).apply()
                    }
                    SettingsToggle("Floating button", "Show a shortcut to resume the last played media in the library.", floatingResume) {
                        floatingResume = it; prefs.edit().putBoolean("list_floating_resume", it).apply()
                    }
                    Text("Period tagged as NEW", color = foreground, modifier = Modifier.padding(top = 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 7, 30).forEach { days ->
                            FilterChip(selected = newTagDays == days, onClick = {
                                newTagDays = days; prefs.edit().putInt("list_new_days", days).apply()
                            }, label = { Text(if (days == 0) "Off" else "${days}d") })
                        }
                    }
                    Text("SCAN", color = purple, modifier = Modifier.padding(top = 24.dp))
                    Row(Modifier.fillMaxWidth().clickable { foldersDialog = true }.padding(vertical = 16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Folders", color = foreground)
                            Text("Choose which media folders appear in the library.", color = secondaryText, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = purple)
                    }
                    SettingsToggle("Include Android app media",
                        "Off by default. Hide WhatsApp and other app media under Android/ from the library.",
                        includeAndroidMedia) {
                        includeAndroidMedia = it
                        prefs.edit().putBoolean("include_android_media", it).apply()
                    }
                    SettingsToggle("Show hidden files and folders",
                        "Include media with names or folder paths starting with a dot, if Android exposes them.",
                        showHidden) {
                        showHidden = it; prefs.edit().putBoolean("list_show_hidden", it).apply()
                    }
                    Text("Android MediaStore normally excludes .nomedia folders. System media scanning rules still apply.",
                        color = secondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (foldersDialog) {
                var folderSelection by remember { mutableStateOf(includedFolders) }
                AlertDialog(onDismissRequest = { foldersDialog = false },
                    title = { Text("Included folders") },
                    text = {
                        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                            Text("Leave all selected to scan every available folder.")
                            val options = media.map { it.folder }.distinct().sorted()
                            options.forEach { folder ->
                                val active = folderSelection.isBlank() || folder in folderSelection.split("|")
                                Row(Modifier.fillMaxWidth().clickable {
                                    val selectedFolders = if (folderSelection.isBlank()) options.toMutableSet()
                                        else folderSelection.split("|").filter { it.isNotBlank() }.toMutableSet()
                                    if (active) selectedFolders.remove(folder) else selectedFolders.add(folder)
                                    folderSelection = selectedFolders.joinToString("|")
                                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = active, onCheckedChange = null)
                                    Text(folder, color = Color.DarkGray, fontSize = 12.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            includedFolders = folderSelection
                            prefs.edit().putString("list_folders", includedFolders).apply()
                            foldersDialog = false
                        }) { Text("Done") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            includedFolders = ""
                            prefs.edit().putString("list_folders", "").apply()
                            foldersDialog = false
                        }) { Text("All folders") }
                    })
            }
        } else if (settingsOpen) {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { settingsOpen = false }) { Icon(Icons.Default.ArrowBack, "Back", tint = foreground) }
                    Text("Settings", fontSize = 26.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("PLAYBACK", color = purple, modifier = Modifier.padding(top = 20.dp))
                    Text("Controls auto-hide: $autoHideSeconds seconds", color = foreground)
                    Slider(value = autoHideSeconds.toFloat(), onValueChange = { autoHideSeconds = it.toInt().coerceIn(3, 15); prefs.edit().putInt("auto_hide", autoHideSeconds).apply() }, valueRange = 3f..15f, steps = 11)
                    SettingsToggle("Resume playback", "Continue from saved position", resumePlayback) { resumePlayback = it; prefs.edit().putBoolean("resume", it).apply() }
                    SettingsToggle("Auto-play", "Play immediately on opening a file", autoPlay) { autoPlay = it; prefs.edit().putBoolean("autoplay", it).apply() }
                    SettingsToggle("Equalizer", "Audio effects for music and video playback (default off)", equalizerEnabled) { equalizerEnabled = it; prefs.edit().putBoolean("eq_enabled", it).apply() }
                    TextButton(onClick = { equalizerOpen = true }) { Text("Equalizer bands & presets") }
                    if (equalizerEnabled && !eqAvailable) Text("Equalizer is unavailable on this device or audio output.", color = Color.Yellow)
                    SettingsToggle("Background playback", "Keep audio playing when you switch apps or lock the screen", backgroundPlay) { backgroundPlay = it; prefs.edit().putBoolean("background_play", it).apply() }
                    Text("GESTURES", color = purple, modifier = Modifier.padding(top = 20.dp))
                    SettingsToggle("Brightness swipe", "Left side vertical gesture", brightnessGesture) { brightnessGesture = it; prefs.edit().putBoolean("brightness_gesture", it).apply() }
                    SettingsToggle("Volume swipe", "Right side vertical gesture", volumeGesture) { volumeGesture = it; prefs.edit().putBoolean("volume_gesture", it).apply() }
                    Text("SEEK", color = purple, modifier = Modifier.padding(top = 20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Skip duration", color = foreground, modifier = Modifier.weight(1f))
                        FilterChip(selected = seekSeconds == 5L, onClick = { seekSeconds = 5L; prefs.edit().putLong("seek_seconds", 5L).apply() }, label = { Text("5s") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = seekSeconds == 10L, onClick = { seekSeconds = 10L; prefs.edit().putLong("seek_seconds", 10L).apply() }, label = { Text("10s") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = seekSeconds == 30L, onClick = { seekSeconds = 30L; prefs.edit().putLong("seek_seconds", 30L).apply() }, label = { Text("30s") })
                    }
                    Text("APPEARANCE", color = purple, modifier = Modifier.padding(top = 20.dp))
                    Text("Theme", color = foreground)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("White", "Dark", "Purple").forEach { option ->
                            FilterChip(selected = themeChoice == option, onClick = {
                                themeChoice = option; prefs.edit().putString("ui_theme", option).apply()
                            }, label = { Text(option) })
                        }
                    }
                    Text("PLAYER LAYOUTS", color = purple, modifier = Modifier.padding(top = 20.dp))
                    Text("Video player", color = foreground, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Classic", "Modern", "Cinema").forEach { option ->
                            FilterChip(selected = videoLayout == option, onClick = {
                                videoLayout = option; prefs.edit().putString("video_layout", option).apply()
                            }, label = { Text(option) })
                        }
                    }
                    Text("Audio player", color = foreground, modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Classic", "Modern", "Studio").forEach { option ->
                            FilterChip(selected = audioLayout == option, onClick = {
                                audioLayout = option; prefs.edit().putString("audio_layout", option).apply()
                            }, label = { Text(option) })
                        }
                    }
                    Text("Audio and video layouts are saved independently.", color = secondaryText, fontSize = 12.sp)
                    Text("LIBRARY", color = purple, modifier = Modifier.padding(top = 20.dp))
                    Row(Modifier.fillMaxWidth().clickable { listSettingsOpen = true }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("List", color = foreground, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, "List settings", tint = purple)
                    }
                    Text("Library sorting and layout can be changed from the library view menu.", color = secondaryText)
                    Text("ABOUT & LEGAL", color = purple, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                    TextButton(onClick = {
                        val url = "https://masoomdev-website.vercel.app/apps/mh-player/privacy/"
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    }) { Text("View public Privacy Policy ↗") }
                    TextButton(onClick = {
                        val url = "https://masoomdev-website.vercel.app/apps/mh-player/"
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    }) { Text("MH Player website ↗") }
                    listOf("About", "Privacy Policy", "Terms & Conditions", "Open Source Licenses").forEach { page ->
                        Row(Modifier.fillMaxWidth().clickable { infoPage = page }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(page, color = foreground, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = secondaryText)
                        }
                        HorizontalDivider(color = Color(0xFF30364C))
                    }
                    Text("MH Player • Offline • ExoPlayer", color = secondaryText, modifier = Modifier.padding(top = 24.dp))
                }
            }
        } else if (selected != null) {
            val audioMode = selected?.video == false
            val activeLayout = if (audioMode) audioLayout else videoLayout
            val overlayText = Color.White
            Box(Modifier.fillMaxSize().background(if (audioMode) dark else Color.Black)) {
                Box(Modifier.fillMaxSize()) {
                    if (!audioMode) {
                        AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(com.masoomdev.mhplayer.R.layout.mh_video_view, null) as PlayerView).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = pinchZoom; scaleY = pinchZoom }, update = { it.resizeMode = resizeChoice })
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 85.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (activeLayout == "Studio") {
                                Text("STUDIO", color = purple, fontSize = 14.sp)
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    val bars = listOf(20, 48, 32, 78, 56, 94, 45, 68, 35, 82, 52, 25, 60, 40, 72)
                                    bars.forEach { height ->
                                        Box(Modifier.width(6.dp).height(height.dp).background(purple, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.size(if (activeLayout == "Modern") 200.dp else 140.dp),
                                    color = if (activeLayout == "Modern") purple.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(if (activeLayout == "Modern") 32.dp else 18.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(if (activeLayout == "Modern") Icons.Default.Album else Icons.Default.MusicNote,
                                            null, Modifier.size(90.dp), tint = purple)
                                    }
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Text(selected?.title ?: "Audio", color = foreground, fontSize = if (activeLayout == "Modern") 24.sp else 20.sp, maxLines = 2)
                            Text(if (activeLayout == "Studio") "Audio playback • Equalizer available in Settings" else "MH Player • Offline audio",
                                color = secondaryText, fontSize = 12.sp)
                        }
                    }
                    if (!pipActive) Box(Modifier.fillMaxSize()
                        .pointerInput(audioMode, locked) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (!audioMode && !locked && zoom != 1f) {
                                    pinchZoom = (pinchZoom * zoom).coerceIn(1f, 4f)
                                    controlsInteraction++
                                }
                            }
                        }
                        .pointerInput(locked) {
                            detectTapGestures(
                                onTap = {
                                    if (!locked) {
                                        controlsVisible = !controlsVisible
                                        controlsInteraction++
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (!locked && !audioMode) {
                                        val skipMs = seekSeconds * 1000L
                                        val forward = offset.x >= size.width / 2f
                                        val target = if (forward) player.currentPosition + skipMs else player.currentPosition - skipMs
                                        val maxPosition = if (player.duration > 0) player.duration else Long.MAX_VALUE
                                        player.seekTo(target.coerceIn(0L, maxPosition))
                                        seekFeedback = (if (forward) "+" else "-") + "${seekSeconds}s"
                                        controlsInteraction++
                                    }
                                },
                                onLongPress = { offset ->
                                    if (!locked && !audioMode && offset.x >= size.width / 2f && !holdSpeedActive) {
                                        holdSpeedActive = true
                                        player.setPlaybackSpeed(2f)
                                        controlsInteraction++
                                    }
                                },
                                onPress = {
                                    tryAwaitRelease()
                                    if (holdSpeedActive) {
                                        holdSpeedActive = false
                                        player.setPlaybackSpeed(playbackSpeed)
                                        controlsInteraction++
                                    }
                                }
                            )
                        }
                        .pointerInput(locked) {
                            var startX = 0f
                            var startLevel = 0f
                            var accumulated = 0f
                            var brightnessSide = true
                            detectDragGestures(
                                onDragStart = { offset ->
                                    startX = offset.x
                                    brightnessSide = startX < size.width / 2f
                                    accumulated = 0f
                                    startLevel = if (brightnessSide) {
                                        val value = activity.window.attributes.screenBrightness
                                        if (value < 0f) 0.5f else value
                                    } else {
                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                                    }
                                    gestureBrightness = brightnessSide
                                    gestureLevel = startLevel
                                    controlsInteraction++
                                },
                                onDragEnd = { gestureLevel = -1f },
                                onDragCancel = { gestureLevel = -1f },
                                onDrag = { change, dragAmount ->
                                    if (!locked && (if (brightnessSide) brightnessGesture else volumeGesture) && kotlin.math.abs(dragAmount.y) >= kotlin.math.abs(dragAmount.x)) {
                                        change.consume()
                                        accumulated -= dragAmount.y / size.height.coerceAtLeast(1).toFloat()
                                        val level = (startLevel + accumulated * 1.5f).coerceIn(0f, 1f)
                                        gestureLevel = level
                                        if (brightnessSide) {
                                            val params = activity.window.attributes
                                            params.screenBrightness = level.coerceAtLeast(0.01f)
                                            activity.window.attributes = params
                                        } else {
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
                                        }
                                    }
                                }
                            )
                        }
                    )
                    if (holdSpeedActive && !audioMode) {
                        Text("2× Speed • Release to resume", modifier = Modifier.align(Alignment.Center), color = Color.White)
                    }
                    if (gestureLevel >= 0f && !locked && !audioMode) {
                        Surface(Modifier.align(Alignment.Center), color = Color(0xDD151827), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(if (gestureBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp, null, tint = purple)
                                Spacer(Modifier.height(8.dp))
                                Text((if (gestureBrightness) "Brightness " else "Volume ") + "${(gestureLevel * 100).toInt()}%", color = foreground)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { gestureLevel }, modifier = Modifier.width(130.dp))
                            }
                        }
                    }
                }
                if (!pipActive && (controlsVisible || locked || audioMode)) Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Color(0xC9000000)).padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (locked) locked = false else closePlayer() }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = foreground)
                        }
                        Text(selected?.title ?: "", Modifier.weight(1f), color = foreground, maxLines = 1)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && selected?.video == true) {
                            IconButton(onClick = {
                                try {
                                    val params = PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9)).build()
                                    activity.enterPictureInPictureMode(params)
                                } catch (_: Exception) { }
                            }) {
                                Icon(Icons.Default.PictureInPictureAlt, "Picture-in-Picture", tint = purple)
                            }
                        }
                        if (!locked) IconButton(onClick = {
                            quickSettingsOpen = true
                            controlsVisible = true
                            controlsInteraction++
                        }) {
                            Icon(Icons.Default.Settings, "Playback settings", tint = purple)
                        }
                        IconButton(onClick = { locked = !locked; controlsInteraction++ }) {
                            Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock controls", tint = purple)
                        }
                    }
                    if (!locked && activeLayout != "Cinema") Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            TextButton(onClick = { speedMenu = true; controlsInteraction++ }) { Text("${playbackSpeed}x") }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                                    DropdownMenuItem(text = { Text("${value}x") }, onClick = {
                                        playbackSpeed = value; player.setPlaybackSpeed(value); speedMenu = false; controlsInteraction++
                                    })
                                }
                            }
                        }
                        TextButton(onClick = { trackDialog = true; controlsInteraction++ }) {
                            Icon(Icons.Default.Audiotrack, "Audio tracks", tint = purple)
                            Text("Audio")
                        }
                        TextButton(onClick = { subtitleDialog = true; controlsInteraction++ }) {
                            Icon(Icons.Default.Subtitles, "Subtitles", tint = purple)
                            Text("CC")
                        }
                        TextButton(onClick = { subtitlePicker.launch(arrayOf("*/*")); controlsInteraction++ }) { Text("+ Subtitle") }
                        TextButton(onClick = {
                            resizeChoice = when (resizeChoice) { 0 -> 2; 2 -> 3; else -> 0 }
                            controlsInteraction++
                        }) { Text("View: " + when (resizeChoice) { 0 -> "Fit"; 2 -> "Zoom"; else -> "Stretch" }) }
                        TextButton(onClick = {
                            repeatOne = !repeatOne
                            player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            controlsInteraction++
                        }) { Text(if (repeatOne) "Repeat on" else "Repeat off") }
                    }
                }
                if (!pipActive && !locked && (controlsVisible || audioMode)) Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xC9000000)).padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (activeLayout == "Cinema") Text("CINEMA", color = purple, fontSize = 11.sp)
                    Slider(
                        value = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()); controlsInteraction++ }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${progress / 60000}:${"%02d".format(progress / 1000 % 60)}", color = foreground)
                        Text("${duration / 60000}:${"%02d".format(duration / 1000 % 60)}", color = foreground)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (activeLayout == "Modern") Arrangement.SpaceAround else Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            player.seekTo((player.currentPosition - seekSeconds * 1000).coerceAtLeast(0))
                            controlsInteraction++
                        }) { Icon(Icons.Default.Replay10, "Seek backward", tint = foreground) }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play(); controlsInteraction++ }) {
                            Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                "Play or pause", Modifier.size(54.dp), tint = purple)
                        }
                        IconButton(onClick = { player.seekTo(player.currentPosition + seekSeconds * 1000); controlsInteraction++ }) {
                            Icon(Icons.Default.Forward10, "Seek forward", tint = foreground)
                        }
                        if (activeLayout != "Cinema") TextButton(onClick = {
                            seekSeconds = if (seekSeconds == 10L) 30L else 10L
                            controlsInteraction++
                        }) { Text("${seekSeconds}s") }
                    }
                } else if (!pipActive && locked) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { locked = false; controlsVisible = true; controlsInteraction++ }) {
                        Text("Unlock controls")
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MH Player", fontSize = 30.sp, color = foreground, modifier = Modifier.weight(1f))
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "Settings", tint = purple) }
                }
                Text("Offline video & audio", color = secondaryText)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showVideo, onClick = { showVideo = true; currentFolder = null }, label = { Text("Videos") })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(selected = !showVideo, onClick = { showVideo = false; currentFolder = null }, label = { Text("Audio") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { refreshMedia() }) { Text("Refresh") }
                    TextButton(onClick = { libraryOptions = true }) {
                        Icon(Icons.Default.Tune, "Library options", tint = purple)
                        Text("View / Sort")
                    }
                    TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*", "application/octet-stream")) }) { Text("Open file") }
                    TextButton(onClick = { streamDialog = true; streamError = "" }) { Text("Network stream") }
                }
                if (libraryOptions) AlertDialog(
                    onDismissRequest = { libraryOptions = false },
                    title = { Text("Library view & sort") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("View mode")
                            Row {
                                listOf("Folders", "All files").forEach { mode ->
                                    FilterChip(selected = libraryView == mode, onClick = {
                                        libraryView = mode; currentFolder = null
                                        prefs.edit().putString("library_view", mode).apply()
                                    }, label = { Text(mode) })
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            Text("Layout")
                            Row {
                                listOf(false to "List", true to "Grid").forEach { (grid, label) ->
                                    FilterChip(selected = gridView == grid, onClick = {
                                        gridView = grid; prefs.edit().putBoolean("grid_view", grid).apply()
                                    }, label = { Text(label) })
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            Text("Sort by")
                            listOf("Name", "Date", "Duration", "Size").chunked(2).forEach { modes ->
                                Row {
                                    modes.forEach { mode ->
                                        FilterChip(selected = sortMode == mode, onClick = {
                                            sortMode = mode; prefs.edit().putString("sort_mode", mode).apply()
                                        }, label = { Text(mode) })
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }
                            Row {
                                FilterChip(selected = !sortDescending, onClick = {
                                    sortDescending = false; prefs.edit().putBoolean("sort_desc", false).apply()
                                }, label = { Text("Ascending") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = sortDescending, onClick = {
                                    sortDescending = true; prefs.edit().putBoolean("sort_desc", true).apply()
                                }, label = { Text("Descending") })
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { libraryOptions = false }) { Text("Done") } }
                )
                OutlinedTextField(value = searchText, onValueChange = { searchText = it }, label = { Text("Search folders or files") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (currentFolder != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentFolder = null }) { Icon(Icons.Default.ArrowBack, "Folders", tint = purple) }
                        Text(currentFolder ?: "", color = foreground, maxLines = 2)
                    }
                } else Text(if (libraryView == "Folders") "Folders" else "All files", color = foreground, fontSize = 20.sp)
                if (libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val filtered = media.filter {
                    it.video == showVideo &&
                    (includeAndroidMedia || !it.folder.trimStart('/').startsWith("Android/", ignoreCase = true)) &&
                    (includedFolders.isBlank() || it.folder in includedFolders.split("|")) &&
                    (showHidden || (!it.title.startsWith(".") && it.folder.split("/").none { part -> part.startsWith(".") })) &&
                    (searchText.isBlank() || it.title.contains(searchText, true) || it.folder.contains(searchText, true))
                }
                val folders = filtered.groupBy { it.folder }
                if (floatingResume && lastPlayedUri.isNotBlank()) {
                    val resumeItem = media.firstOrNull { it.uri.toString() == lastPlayedUri }
                    if (resumeItem != null) TextButton(onClick = { selected = resumeItem }) {
                        Icon(Icons.Default.PlayCircle, "Resume last played", tint = purple)
                        Text("Resume last played")
                    }
                }
                if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No media found. Tap Open file to browse.", color = secondaryText)
                } else if (currentFolder == null && libraryView == "Folders") {
                    LazyColumn {
                        items(
                            folders.entries.let { groups ->
                                val orderedFolders = when (sortMode) {
                                    "Date" -> groups.sortedWith(compareBy({ it.value.maxOfOrNull { media -> media.dateAdded } ?: 0L }, { it.key.lowercase() }))
                                    "Duration" -> groups.sortedWith(compareBy({ it.value.sumOf { media -> media.duration.coerceAtLeast(0L) } }, { it.key.lowercase() }))
                                    "Size" -> groups.sortedWith(compareBy({ it.value.sumOf { media -> media.size.coerceAtLeast(0L) } }, { it.key.lowercase() }))
                                    else -> groups.sortedBy { it.key.lowercase() }
                                }
                                if (sortDescending) orderedFolders.reversed() else orderedFolders
                            },
                            key = { it.key }
                        ) { folder ->
                            Row(Modifier.fillMaxWidth().clickable { currentFolder = folder.key }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = purple, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(folder.key.substringAfterLast('/').ifBlank { "Device storage" }, color = foreground, fontSize = 17.sp)
                                    Text(folder.key + " • " + folder.value.size + " files", color = secondaryText, fontSize = 12.sp, maxLines = 2)
                                    if (markLastPlayed && folder.value.any { it.uri.toString() == lastPlayedUri }) {
                                        Text("Last played", color = purple, fontSize = 12.sp)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = secondaryText)
                            }
                            HorizontalDivider(color = Color(0xFF30364C))
                        }
                    }
                } else {
                    val entries = if (currentFolder == null) filtered else filtered.filter { it.folder == currentFolder }
                    val ordered = when (sortMode) {
                        "Date" -> entries.sortedWith(compareBy({ it.dateAdded }, { it.title.lowercase() }))
                        "Duration" -> entries.sortedWith(compareBy({ it.duration }, { it.title.lowercase() }))
                        "Size" -> entries.sortedWith(compareBy({ it.size }, { it.title.lowercase() }))
                        else -> entries.sortedBy { it.title.lowercase() }
                    }
                    val sorted = if (sortDescending) ordered.reversed() else ordered
                    if (gridView) {
                        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 140.dp)) {
                            gridItems(sorted, key = { it.uri.toString() }) { item ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { selected = item }.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(if (item.video) Icons.Default.Movie else Icons.Default.MusicNote,
                                        null, Modifier.size(46.dp), tint = purple)
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.title, color = foreground, maxLines = 2)
                                    if (markLastPlayed && item.uri.toString() == lastPlayedUri) Text("Last played", color = purple, fontSize = 12.sp)
                                    if (newTagDays > 0 && item.dateAdded > 0 && System.currentTimeMillis() / 1000 - item.dateAdded <= newTagDays * 86400L) Text("NEW", color = purple, fontSize = 11.sp)
                                    Text("${item.duration / 60000} min", color = secondaryText, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        LazyColumn {
                            items(sorted, key = { it.uri.toString() }) { item ->
                                Row(Modifier.fillMaxWidth().clickable { selected = item }.padding(vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (item.video) Icons.Default.Movie else Icons.Default.MusicNote, null, tint = purple)
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text(item.title, color = foreground, maxLines = 2)
                                        Text("${item.duration / 60000} min", color = secondaryText, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF30364C))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
