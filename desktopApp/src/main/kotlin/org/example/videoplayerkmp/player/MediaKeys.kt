package org.example.videoplayerkmp.player

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.SwingUtilities

/**
 * Медиаклавиши клавиатуры (на ноутбуках — Fn + F-клавиши): ⏯ ⏭ ⏮ ⏹.
 *
 * Windows отдаёт их как системные клавиши, которые в обычные события окна не попадают,
 * поэтому слушаем их через системный перехватчик — они работают, даже когда окно свёрнуто.
 */
object MediaKeys {
    private var listener: NativeKeyListener? = null

    fun start(player: PlayerController) {
        if (listener != null) return
        // JNativeHook по умолчанию пишет в журнал каждое нажатие клавиши
        Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
            level = Level.WARNING
            useParentHandlers = false
        }
        try {
            GlobalScreen.registerNativeHook()
        } catch (e: NativeHookException) {
            System.err.println("Медиаклавиши недоступны: ${e.message}")
            return
        }
        listener = object : NativeKeyListener {
            override fun nativeKeyPressed(event: NativeKeyEvent) {
                val action: (() -> Unit)? = when (event.keyCode) {
                    NativeKeyEvent.VC_MEDIA_PLAY -> player::togglePlay
                    NativeKeyEvent.VC_MEDIA_NEXT -> player::next
                    NativeKeyEvent.VC_MEDIA_PREVIOUS -> player::previous
                    NativeKeyEvent.VC_MEDIA_STOP -> player::pause
                    else -> null
                }
                // Плеер меняет состояние только в главном потоке
                if (action != null) SwingUtilities.invokeLater(action)
            }
        }.also(GlobalScreen::addNativeKeyListener)
    }

    /** Без снятия перехватчика его поток не даёт процессу завершиться. */
    fun stop() {
        listener?.let(GlobalScreen::removeNativeKeyListener)
        listener = null
        runCatching { GlobalScreen.unregisterNativeHook() }
    }
}
