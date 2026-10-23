package org.example.videoplayerkmp.account

import android.os.Build

actual fun deviceName(): String {
    val model = Build.MODEL.orEmpty()
    // У многих моделей производитель уже есть в названии: «Pixel 9», но «SM-S921B» без «Samsung»
    return if (model.startsWith(Build.MANUFACTURER, ignoreCase = true)) model
    else "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} $model"
}
