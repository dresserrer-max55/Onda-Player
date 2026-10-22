package com.onda.player

import android.graphics.Bitmap

/** Estado de lo que suena, enviado desde la página (JavaScript). */
object NowPlaying {
    @Volatile var has = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var playing = false
    @Volatile var video = false
    @Volatile var posMs = 0L
    @Volatile var durMs = 0L
    @Volatile var art: Bitmap? = null
}
