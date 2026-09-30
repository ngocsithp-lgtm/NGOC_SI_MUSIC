package com.ngocsi.music

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Centralized visual tokens for the NGỌC SĨ MUSIC Pro interface.
 * The visual language is inspired by the compact, card-based flow of
 * Lâm Music, while remaining an independent implementation.
 */
object NgocSiVisuals {
    val Background = Color(0xFF08090D)
    val Surface = Color(0xFF11131A)
    val SurfaceRaised = Color(0xFF15161E)
    val SurfaceSoft = Color(0xFF1B1D27)
    val Border = Color(0xFF252936)
    val Primary = Color(0xFFB18CFF)
    val PrimaryStrong = Color(0xFF8C64E8)
    val Secondary = Color(0xFF7DD3FC)
    val Text = Color.White
    val TextSecondary = Color(0xFF9698A7)
    val TextMuted = Color(0xFF777D8D)
    val Danger = Color(0xFFFFB4AB)

    val CardShape = RoundedCornerShape(20.dp)
    val HeroShape = RoundedCornerShape(26.dp)
    val ControlShape = RoundedCornerShape(16.dp)
    val ChipShape = RoundedCornerShape(14.dp)
}
