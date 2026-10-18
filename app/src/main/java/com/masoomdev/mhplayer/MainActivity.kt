package com.masoomdev.mhplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val dark = Color(0xFF0D1020)
private val purple = Color(0xFF9B72FF)
private data class LocalMedia(val title: String, val uri: Uri, val video: Boolean, val duration: Long, val folder: String)

class MainActivity : ComponentActivity() {
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
        val projection = if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH) else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION)
        try {
            activity.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val folderIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) output.add(LocalMedia(cursor.getString(nameIndex) ?: "Unknown", ContentUris.withAppendedId(base, cursor.getLong(idIndex)), video, cursor.getLong(durationIndex), if (folderIndex >= 0) (cursor.getString(folderIndex) ?: "Other").trimEnd('/') else "Device storage"))
            }
        } catch (_: Exception) { }
    }
    return output
}

@Composable
private fun PlayerApp(activity: ComponentActivity, initialUri: Uri?) {
    val context = LocalContext.current
    val prefs = remember { activity.getSharedPreferences("mh_player", 0) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release(); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    var splash by remember { mutableStateOf(true) }
    var exitPrompt by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalMedia?>(null) }
    var showVideo by remember { mutableStateOf(true) }
    var currentFolder by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(true) }
    var libraryLoading by remember { mutableStateOf(false) }
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
    var repeatOne by remember { mutableStateOf(false) }
    var seekSeconds by remember { mutableLongStateOf(10L) }
    LaunchedEffect(selected?.uri, controlsVisible, controlsInteraction, locked, trackDialog, subtitleDialog, speedMenu) {
        if (selected != null && controlsVisible && !locked && !trackDialog && !subtitleDialog && !speedMenu) {
            delay(5000)
            controlsVisible = false
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackRevision++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selected = LocalMedia(uri.lastPathSegment ?: "Opened file", uri, true, 0L, "Opened files")
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
        if (initialUri != null) selected = LocalMedia("Opened file", initialUri, true, 0L, "Opened files")
    }
    LaunchedEffect(selected?.uri) {
        val item = selected ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
        val saved = prefs.getLong("position_${item.uri}", 0L)
        if (saved > 0) player.seekTo(saved)
        player.playWhenReady = true
        locked = false
        controlsVisible = true
        controlsInteraction++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(selected) {
        while (selected != null) {
            progress = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            if (progress > 0) prefs.edit().putLong("position_${selected?.uri}", progress).apply()
            delay(400)
        }
    }
    fun closePlayer() { player.pause(); selected = null; locked = false; activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    BackHandler { if (exitPrompt) exitPrompt = false else if (selected != null) { if (locked) locked = false else closePlayer() } else if (currentFolder != null) currentFolder = null else exitPrompt = true }
    if (trackDialog || subtitleDialog) {
        val isAudio = trackDialog
        val trackType = if (isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val revision = trackRevision
        val groups = player.currentTracks.groups.filter { it.type == trackType }
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
                            if (group.isTrackSupported(index)) {
                                val format = group.getTrackFormat(index)
                                val title = format.label ?: (if (isAudio) "Audio" else "Subtitle") + " ${groupNumber + 1}.${index + 1}"
                                TextButton(onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, false)
                                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                    trackDialog = false; subtitleDialog = false
                                }) { Text((if (group.isTrackSelected(index)) "✓ " else "") + title + " • " + (format.language ?: "Unknown")) }
                            }
                        }
                    }
                    if (groups.isEmpty()) Text("No embedded tracks available")
                    if (revision < 0) Text("")
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = false; subtitleDialog = false }) { Text("Close") } }
        )
    }
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Exit MH Player?") }, text = { Text("Are you sure you want to close MH Player?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit app") } }, dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Stay in app") } })
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = dark, surface = Color(0xFF1A2034))) {
        if (splash) {
            Box(Modifier.fillMaxSize().background(dark), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PlayCircle, null, Modifier.size(86.dp), tint = purple); Spacer(Modifier.height(12.dp)); Text("MH PLAYER", fontSize = 32.sp, color = Color.White); Text("Your media. Your device.", color = Color.LightGray) }
            }
        } else if (selected != null) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                if (controlsVisible || locked) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (locked) locked = false else closePlayer() }) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Text(selected?.title ?: "", Modifier.weight(1f), color = Color.White, maxLines = 1)
                    IconButton(onClick = { locked = !locked }) { Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock controls", tint = purple) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize(), update = { it.resizeMode = resizeChoice })
                    Box(Modifier.fillMaxSize()
                        .pointerInput(locked) {
                            detectTapGestures(onTap = {
                                if (!locked) {
                                    controlsVisible = !controlsVisible
                                    controlsInteraction++
                                }
                            })
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
                                    if (!locked && kotlin.math.abs(dragAmount.y) >= kotlin.math.abs(dragAmount.x)) {
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
                    if (gestureLevel >= 0f && !locked) {
                        Surface(Modifier.align(Alignment.Center), color = Color(0xDD151827), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(if (gestureBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp, null, tint = purple)
                                Spacer(Modifier.height(8.dp))
                                Text((if (gestureBrightness) "Brightness " else "Volume ") + "${(gestureLevel * 100).toInt()}%", color = Color.White)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { gestureLevel }, modifier = Modifier.width(130.dp))
                            }
                        }
                    }
                }
                if (!locked && controlsVisible) Column(Modifier.fillMaxWidth().background(dark).padding(14.dp)) {
                    Slider(value = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${progress / 60000}:${"%02d".format(progress / 1000 % 60)}", color = Color.White); Text("${duration / 60000}:${"%02d".format(duration / 1000 % 60)}", color = Color.White) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekTo((player.currentPosition - seekSeconds * 1000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White) }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Play pause", Modifier.size(52.dp), tint = purple) }
                        IconButton(onClick = { player.seekTo(player.currentPosition + seekSeconds * 1000) }) { Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White) }
                        Box { TextButton(onClick = { speedMenu = true }) { Text("${playbackSpeed}x") }; DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value -> DropdownMenuItem(text = { Text("${value}x") }, onClick = { playbackSpeed = value; player.setPlaybackSpeed(value); speedMenu = false }) } } }
                        IconButton(onClick = { trackDialog = true }) { Icon(Icons.Default.Audiotrack, "Audio tracks", tint = Color.White) }
                        IconButton(onClick = { subtitleDialog = true }) { Icon(Icons.Default.Subtitles, "Subtitle tracks", tint = Color.White) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }) { Text("+ Subtitle") }
                        TextButton(onClick = { resizeChoice = when (resizeChoice) { 0 -> 2; 2 -> 3; else -> 0 } }) { Text("View: " + when (resizeChoice) { 0 -> "Fit"; 2 -> "Zoom"; else -> "Stretch" }) }
                        TextButton(onClick = { repeatOne = !repeatOne; player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }) { Text(if (repeatOne) "Repeat on" else "Repeat off") }
                        TextButton(onClick = { seekSeconds = if (seekSeconds == 10L) 30L else 10L }) { Text("Seek ${seekSeconds}s") }
                    }
                } else if (locked) Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) { TextButton(onClick = { locked = false; controlsVisible = true; controlsInteraction++ }) { Text("Unlock controls") } }
            }
        } else {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text("MH Player", fontSize = 30.sp, color = Color.White)
                Text("Offline video & audio", color = Color.LightGray)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showVideo, onClick = { showVideo = true; currentFolder = null }, label = { Text("Videos") })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(selected = !showVideo, onClick = { showVideo = false; currentFolder = null }, label = { Text("Audio") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { refreshMedia() }) { Text("Refresh") }
                    TextButton(onClick = { sortByName = !sortByName }) { Text(if (sortByName) "Name ↑" else "Recent") }
                    TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*", "application/octet-stream")) }) { Text("Open file") }
                }
                OutlinedTextField(value = searchText, onValueChange = { searchText = it }, label = { Text("Search folders or files") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (currentFolder != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentFolder = null }) { Icon(Icons.Default.ArrowBack, "Folders", tint = purple) }
                        Text(currentFolder ?: "", color = Color.White, maxLines = 2)
                    }
                } else Text("Folders", color = Color.White, fontSize = 20.sp)
                if (libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val filtered = media.filter { it.video == showVideo && (searchText.isBlank() || it.title.contains(searchText, true) || it.folder.contains(searchText, true)) }
                val folders = filtered.groupBy { it.folder }
                if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No media found. Tap Open file to browse.", color = Color.LightGray)
                } else if (currentFolder == null) {
                    LazyColumn {
                        items(folders.entries.sortedBy { it.key }) { folder ->
                            Row(Modifier.fillMaxWidth().clickable { currentFolder = folder.key }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = purple, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(folder.key.substringAfterLast('/').ifBlank { "Device storage" }, color = Color.White, fontSize = 17.sp)
                                    Text(folder.key + " • " + folder.value.size + " files", color = Color.LightGray, fontSize = 12.sp, maxLines = 2)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                            }
                            HorizontalDivider(color = Color(0xFF30364C))
                        }
                    }
                } else {
                    val entries = filtered.filter { it.folder == currentFolder }
                    val sorted = if (sortByName) entries.sortedBy { it.title.lowercase() } else entries
                    LazyColumn {
                        items(sorted, key = { it.uri.toString() }) { item ->
                            Row(Modifier.fillMaxWidth().clickable { selected = item }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.video) Icons.Default.Movie else Icons.Default.MusicNote, null, tint = purple)
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(item.title, color = Color.White, maxLines = 2)
                                    Text("${item.duration / 60000} min", color = Color.LightGray, fontSize = 12.sp)
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
