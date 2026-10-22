package org.example.videoplayerkmp.android

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Фоновое воспроизведение: уведомление с кнопками, экран блокировки, кнопки наушников.
 * Media3 сам держит сервис на переднем плане, пока музыка играет.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as KmpMusicApp
        val queueCommands = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        // В ExoPlayer всегда один трек — «следующий» и «предыдущий» из уведомления
        // переадресуем нашей очереди, а play/pause — контроллеру, чтобы интерфейс знал о них
        val player = object : ForwardingPlayer(app.engine.player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*queueCommands.toIntArray()).build()

            override fun isCommandAvailable(command: Int) = command in queueCommands || super.isCommandAvailable(command)

            override fun seekToNext() = app.player.next()

            override fun seekToNextMediaItem() = app.player.next()

            override fun seekToPrevious() = app.player.previous()

            override fun seekToPreviousMediaItem() = app.player.previous()

            override fun play() = app.player.play()

            override fun pause() = app.player.pause()
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Приложение смахнули из недавних: если музыка не играет, сервису незачем жить. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
