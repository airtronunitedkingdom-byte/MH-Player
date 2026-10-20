package com.masoomdev.mhplayer

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Single player instance shared by the activity and Android media notification. */
object PlaybackHolder {
    private var instance: ExoPlayer? = null

    fun player(context: Context): ExoPlayer {
        return instance ?: ExoPlayer.Builder(
            context.applicationContext,
            DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true)
        ).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build()
        ).build().also { instance = it }
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, PlaybackHolder.player(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
