package com.antigravity.android.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon

val ObsidianCinemaColorScheme = darkColorScheme(
    primary = AntigravityViolet,
    onPrimary = TextPrimary,
    primaryContainer = ObsidianElevated,
    onPrimaryContainer = AntigravityVioletBright,
    secondary = AntigravityCyan,
    onSecondary = ObsidianDeep,
    secondaryContainer = AntigravityCyanSurface,
    onSecondaryContainer = AntigravityCyan,
    tertiary = AgentWorkingEmerald,
    onTertiary = ObsidianDeep,
    background = ObsidianDeep,
    onBackground = TextPrimary,
    surface = ObsidianSlate,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianBorder,
    outlineVariant = ObsidianBorderSubtle,
    error = AgentErrorCrimson,
    onError = TextPrimary
)

@Composable
fun ObsidianCinemaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ObsidianCinemaColorScheme,
        typography = AntigravityTypography,
        content = content
    )
}

@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    accentBorderBrush: Brush? = null,
    cornerRadius: Dp = 16.dp,
    containerColor: Color = ObsidianSlate,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = accentBorderBrush ?: if (isHighlighted) {
        CinemaGradients.ActiveGlassCardBorder
    } else {
        CinemaGradients.GlassCardBorder
    }
    val clickModifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .then(clickModifier)
            .border(
                width = if (isHighlighted) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = shape
            ),
        color = containerColor,
        shape = shape,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun PulsingAgentIndicator(
    color: Color = AgentWorkingEmerald,
    isPulsing: Boolean = true,
    dotSize: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    if (isPulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "agent_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 2.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_scale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.65f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_alpha"
        )

        Box(
            modifier = modifier.size(dotSize * 2.2f),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .scale(pulseScale)
                    .background(color.copy(alpha = pulseAlpha), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color, CircleShape)
            )
        }
    } else {
        Box(
            modifier = modifier.size(dotSize * 2.2f),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color.copy(alpha = 0.75f), CircleShape)
            )
        }
    }
}

@Composable
fun CinemaStatusBadge(
    label: String,
    accentColor: Color,
    isPulsing: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(accentColor.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (isPulsing) {
            PulsingAgentIndicator(
                color = accentColor,
                isPulsing = true,
                dotSize = 6.dp
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = CinemaTextStyles.ChecksumBadge,
            color = accentColor
        )
    }
}
