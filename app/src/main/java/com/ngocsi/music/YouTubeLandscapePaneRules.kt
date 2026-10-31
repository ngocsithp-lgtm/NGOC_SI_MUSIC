package com.ngocsi.music

/**
 * Keeps the inline YouTube video and its queue readable across phone and tablet
 * landscape widths. The queue gets more room on compact displays; wider displays
 * reserve enough space for the 16:9 player without wasting the extra width.
 */
internal object YouTubeLandscapePaneRules {
    fun leftPaneWeight(screenWidthDp: Int): Float = when {
        screenWidthDp <= 0 -> 0.56f
        screenWidthDp < 720 -> 0.52f
        screenWidthDp < 900 -> 0.54f
        else -> 0.56f
    }

    fun thumbnailWidthDp(screenWidthDp: Int): Int =
        if (screenWidthDp >= 900) 88 else 80

    fun thumbnailHeightDp(screenWidthDp: Int): Int =
        if (screenWidthDp >= 900) 50 else 45

    fun titleFontSizeSp(screenWidthDp: Int): Int =
        if (screenWidthDp >= 900) 12 else 11
}
