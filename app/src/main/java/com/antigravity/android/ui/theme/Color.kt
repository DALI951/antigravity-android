package com.antigravity.android.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Deep Obsidian Surfaces
val ObsidianDeep = Color(0xFF080A0F)
val ObsidianSlate = Color(0xFF10141D)
val ObsidianElevated = Color(0xFF171D2B)
val ObsidianGlass = Color(0xD9121723)
val ObsidianTerminalBg = Color(0xFF05070B)

// Borders & Dividers
val ObsidianBorder = Color(0xFF222B3B)
val ObsidianBorderSubtle = Color(0xFF181F2C)
val CyanGlowBorder = Color(0x6600F0FF)
val VioletGlowBorder = Color(0x668B6FFF)

// Electric Antigravity Accents
val AntigravityCyan = Color(0xFF00F0FF)
val AntigravityCyanDim = Color(0xFF00B8C4)
val AntigravityCyanSurface = Color(0x1F00F0FF)

val AntigravityViolet = Color(0xFF8B6FFF)
val AntigravityVioletBright = Color(0xFFA992FF)
val AntigravityVioletSurface = Color(0x248B6FFF)

val AntigravityMagenta = Color(0xFFFF3D9A)

// Semantic & Agent Status Colors
val AgentWorkingEmerald = Color(0xFF10B981)
val AgentWorkingSurface = Color(0x2210B981)
val AgentWaitingAmber = Color(0xFFF59E0B)
val AgentWaitingSurface = Color(0x22F59E0B)
val AgentVerifierCyan = Color(0xFF06B6D4)
val AgentCoordinatorViolet = Color(0xFF8B5CF6)
val AgentErrorCrimson = Color(0xFFEF4444)
val AgentErrorSurface = Color(0x22EF4444)

// Diff Highlight Colors
val DiffAdditionBg = Color(0x2410B981)
val DiffAdditionText = Color(0xFF34D399)
val DiffDeletionBg = Color(0x24EF4444)
val DiffDeletionText = Color(0xFFF87171)

// Typography Colors
val TextPrimary = Color(0xFFF3F6FC)
val TextSecondary = Color(0xFF9BA5B7)
val TextMuted = Color(0xFF647087)

// Cinema Gradients
object CinemaGradients {
    val CyanToViolet = Brush.linearGradient(
        colors = listOf(AntigravityCyan, AntigravityViolet),
        start = Offset(0f, 0f),
        end = Offset(1000f, 400f)
    )

    val GlassCardBorder = Brush.linearGradient(
        colors = listOf(
            Color(0x5500F0FF),
            Color(0x228B6FFF),
            Color(0x15FFFFFF)
        )
    )

    val ActiveGlassCardBorder = Brush.linearGradient(
        colors = listOf(
            AntigravityCyan,
            AntigravityViolet
        )
    )

    val ObsidianHeaderBackdrop = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF111726),
            ObsidianDeep
        )
    )

    val EmeraldInstallGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF059669),
            Color(0xFF10B981),
            Color(0xFF00F0FF)
        )
    )
}
