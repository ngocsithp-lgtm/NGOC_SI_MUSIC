package com.ngocsi.music

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Central visual system for NGỌC SĨ MUSIC.
 * Deep slate surfaces keep long listening sessions calm; violet identifies AI and
 * library actions, while cyan is reserved for active playback and primary actions.
 */
object NgocSiVisuals {
    val Background = Color(0xFF090C12)
    val Surface = Color(0xFF111722)
    val SurfaceRaised = Color(0xFF171F2D)
    val SurfaceSoft = Color(0xFF1D2636)
    val Border = Color(0xFF2B3547)
    val Primary = Color(0xFFB7A0FF)
    val PrimaryStrong = Color(0xFF9676F0)
    val Secondary = Color(0xFF78E3FF)
    val Success = Color(0xFF75E3B2)
    val Text = Color(0xFFF7F8FC)
    val TextSecondary = Color(0xFFA7B0C2)
    val TextMuted = Color(0xFF7F899D)
    val Danger = Color(0xFFFFB4AB)

    val CardShape = RoundedCornerShape(22.dp)
    val HeroShape = RoundedCornerShape(28.dp)
    val ControlShape = RoundedCornerShape(18.dp)
    val ChipShape = RoundedCornerShape(16.dp)
}
