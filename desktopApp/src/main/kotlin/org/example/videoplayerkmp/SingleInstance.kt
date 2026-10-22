package org.example.videoplayerkmp

import java.awt.Frame
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Только одна копия приложения: две копии одновременно писали бы в один файл библиотеки и кэш.
 * Повторный запуск не открывает второе окно, а выводит на передний план уже открытое.
 */
object SingleInstance {
    private var lock: FileLock? = null

    /** Окно, которое показать при повторном запуске. */
    @Volatile var window: Frame? = null

    /** true — это первая копия, можно запускаться; false — копия уже работает и её окно показано. */
    fun acquire(dir: File): Boolean {
        val portFile = File(dir, "instance.port")
        val channel = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            channel.close()
            // Просим работающую копию показать окно
            portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let { port ->
                runCatching { Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(1) } }
            }
            return false
        }
        lock = acquired

        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (true) {
                runCatching { server.accept().use { } }.onFailure { return@thread }
                SwingUtilities.invokeLater(::showWindow)
            }
        }
        return true
    }

    private fun showWindow() {
        val frame = window ?: return
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }
}
