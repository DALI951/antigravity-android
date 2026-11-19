package com.antigravity.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.android.ui.theme.AgentErrorCrimson
import com.antigravity.android.ui.theme.AgentWaitingAmber
import com.antigravity.android.ui.theme.AgentWorkingEmerald
import com.antigravity.android.ui.theme.AntigravityCyan
import com.antigravity.android.ui.theme.AntigravityCyanSurface
import com.antigravity.android.ui.theme.AntigravityViolet
import com.antigravity.android.ui.theme.AntigravityVioletBright
import com.antigravity.android.ui.theme.AntigravityVioletSurface
import com.antigravity.android.ui.theme.CinemaGradients
import com.antigravity.android.ui.theme.CinemaStatusBadge
import com.antigravity.android.ui.theme.CinemaTextStyles
import com.antigravity.android.ui.theme.DiffAdditionBg
import com.antigravity.android.ui.theme.DiffAdditionText
import com.antigravity.android.ui.theme.DiffDeletionBg
import com.antigravity.android.ui.theme.DiffDeletionText
import com.antigravity.android.ui.theme.GlassmorphicCard
import com.antigravity.android.ui.theme.ObsidianBorder
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianElevated
import com.antigravity.android.ui.theme.ObsidianSlate
import com.antigravity.android.ui.theme.ObsidianTerminalBg
import com.antigravity.android.ui.theme.PulsingAgentIndicator
import com.antigravity.android.ui.theme.TextMuted
import com.antigravity.android.ui.theme.TextPrimary
import com.antigravity.android.ui.theme.TextSecondary
import com.antigravity.android.ui.viewmodel.AppInstallStatus
import com.antigravity.android.ui.viewmodel.CinemaTrajectoryItem
import com.antigravity.android.ui.viewmodel.DirectAppInstallItem
import com.antigravity.android.ui.viewmodel.FileDiffApprovalItem
import com.antigravity.android.ui.viewmodel.TerminalCommandItem

@Composable
fun UserCommandCard(
    item: CinemaTrajectoryItem,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(0.9f),
            isHighlighted = true,
            containerColor = ObsidianElevated,
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "DIRECTIVE • #${item.stepIndex}",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityVioletBright
                )
                Text(
                    text = item.modelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
    }
}

@Composable
fun PlannerResponseCard(
    item: CinemaTrajectoryItem,
    onToggleThinking: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = ObsidianSlate,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingAgentIndicator(
                    color = AntigravityCyan,
                    isPulsing = item.isStreaming,
                    dotSize = 8.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.title.ifBlank { "PLANNER_RESPONSE" },
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
            }
            CinemaStatusBadge(
                label = item.modelName,
                accentColor = AntigravityVioletBright,
                icon = Icons.Default.AutoAwesome
            )
        }

        // Collapsible Thinking Chain
        if (item.thinkingChain.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            ThinkingChainSection(
                thinkingText = item.thinkingChain,
                durationMs = item.thinkingDurationMs,
                isExpanded = item.isThinkingExpanded,
                onToggle = { onToggleThinking(item.id) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary
        )
    }
}

@Composable
fun ThinkingChainSection(
    thinkingText: String,
    durationMs: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "thinking_chevron"
    )
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObsidianDeep)
            .border(1.dp, ObsidianBorder, shape)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Thinking chain",
                    tint = AntigravityVioletBright,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isExpanded) "Hide Reasoning Chain" else "Thought for ${durationMs}ms • Tap to inspect",
                    style = MaterialTheme.typography.labelMedium,
                    color = AntigravityVioletBright
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Expand thinking",
                tint = TextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = thinkingText,
                    style = CinemaTextStyles.TerminalOutput,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun TerminalCommandCard(
    itemId: String,
    terminal: TerminalCommandItem,
    onRerunCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = ObsidianTerminalBg,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Shell command",
                    tint = AntigravityCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ON-DEVICE SHELL",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val isSuccess = (terminal.exitCode ?: 0) == 0
                CinemaStatusBadge(
                    label = if (terminal.isRunning) {
                        "RUNNING"
                    } else {
                        "EXIT ${terminal.exitCode ?: 0} • ${terminal.durationMs}ms"
                    },
                    accentColor = if (isSuccess) AgentWorkingEmerald else AgentErrorCrimson,
                    isPulsing = terminal.isRunning
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AntigravityCyanSurface)
                        .clickable { onRerunCommand(itemId) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Re-run command",
                            tint = AntigravityCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Re-run",
                            style = CinemaTextStyles.ChecksumBadge,
                            color = AntigravityCyan
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "$ ${terminal.commandLine}",
            style = CinemaTextStyles.TerminalCommand,
            color = AntigravityCyan
        )

        if (terminal.stdout.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianDeep)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = terminal.stdout,
                    style = CinemaTextStyles.TerminalOutput,
                    color = TextSecondary
                )
            }
        }

        if (terminal.stderr.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = terminal.stderr,
                style = CinemaTextStyles.TerminalOutput,
                color = AgentErrorCrimson
            )
        }
    }
}

@Composable
fun FileDiffApprovalCard(
    approval: FileDiffApprovalItem,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        isHighlighted = approval.isPending,
        containerColor = ObsidianSlate,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "Code diff",
                    tint = AntigravityVioletBright,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = approval.filePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val (badgeText, badgeColor) = when {
                approval.isPending -> "APPROVAL NEEDED" to AgentWaitingAmber
                approval.isApproved == true -> "APPLIED ✓" to AgentWorkingEmerald
                else -> "REJECTED" to AgentErrorCrimson
            }
            CinemaStatusBadge(
                label = badgeText,
                accentColor = badgeColor,
                isPulsing = approval.isPending
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = approval.filePath,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )

        if (approval.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = approval.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianTerminalBg)
                .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            approval.diffLines.forEach { line ->
                val isAdd = line.startsWith("+") && !line.startsWith("+++")
                val isDel = line.startsWith("-") && !line.startsWith("---")
                val bg = when {
                    isAdd -> DiffAdditionBg
                    isDel -> DiffDeletionBg
                    else -> Color.Transparent
                }
                val fg = when {
                    isAdd -> DiffAdditionText
                    isDel -> DiffDeletionText
                    else -> TextSecondary
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = line,
                        style = CinemaTextStyles.CodeDiffLine,
                        color = fg
                    )
                }
            }
        }

        if (approval.isPending) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onReject(approval.editId) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, AgentErrorCrimson.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AgentErrorCrimson)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Reject diff",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject")
                }

                Button(
                    onClick = { onApprove(approval.editId) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgentWorkingEmerald,
                        contentColor = ObsidianDeep
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Approve diff",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Approve Edit",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun DirectAppInstallCard(
    app: DirectAppInstallItem,
    onInstallClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        isHighlighted = true,
        accentBorderBrush = CinemaGradients.ActiveGlassCardBorder,
        containerColor = ObsidianSlate,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Top row: App Icon + Title + Version + Source Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Futuristic App Icon Badge
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CinemaGradients.CyanToViolet)
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(ObsidianDeep),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = app.appName,
                    tint = AntigravityCyan,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${app.packageName} • v${app.versionName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AntigravityCyan
                )
            }

            CinemaStatusBadge(
                label = app.sourceLabel,
                accentColor = AntigravityVioletBright
            )
        }

        if (app.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = app.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Download Progress Bar + Size Metrics
        val fileSizeMb = "%.1f MB".format(app.fileSizeBytes / (1024f * 1024f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ObsidianDeep)
                .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download Progress",
                        tint = AntigravityCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (app.status) {
                            AppInstallStatus.DOWNLOADING -> "Streaming APK (${app.downloadProgress}%)"
                            AppInstallStatus.INSTALLED -> "Installed via PackageInstaller"
                            AppInstallStatus.INSTALLING -> "Staging PackageInstaller Session..."
                            else -> "APK Payload Ready ($fileSizeMb)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "${app.downloadProgress}% • $fileSizeMb",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (app.downloadProgress.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = if (app.status == AppInstallStatus.INSTALLED) AgentWorkingEmerald else AntigravityCyan,
                trackColor = ObsidianElevated
            )

            Spacer(modifier = Modifier.height(10.dp))

            // SHA-256 Checksum Badge Row
            val shaShort = if (app.sha256.length >= 16) {
                "${app.sha256.take(10)}...${app.sha256.takeLast(6)}"
            } else {
                app.sha256
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (app.isSha256Verified) Icons.Default.VerifiedUser else Icons.Default.Security,
                        contentDescription = "SHA-256 Checksum",
                        tint = if (app.isSha256Verified) AgentWorkingEmerald else AgentWaitingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (app.isSha256Verified) "SHA-256 VERIFIED" else "SHA-256 PENDING",
                        style = CinemaTextStyles.ChecksumBadge,
                        color = if (app.isSha256Verified) AgentWorkingEmerald else AgentWaitingAmber
                    )
                }
                Text(
                    text = shaShort,
                    style = CinemaTextStyles.ChecksumBadge,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (app.errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = app.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AgentErrorCrimson
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // One-Tap "Install on Phone" CTA Button
        val isInstalled = app.status == AppInstallStatus.INSTALLED
        Button(
            onClick = { onInstallClick(app.id) },
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isInstalled) AgentWorkingEmerald else AntigravityCyan,
                contentColor = ObsidianDeep
            )
        ) {
            Icon(
                imageVector = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.InstallMobile,
                contentDescription = "Install on Phone",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (app.status) {
                    AppInstallStatus.INSTALLED -> "Installed on Phone ✓ (Tap to Re-install)"
                    AppInstallStatus.INSTALLING -> "Launching Android PackageInstaller..."
                    AppInstallStatus.DOWNLOADING -> "Downloading & Verifying APK..."
                    else -> "Install on Phone"
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
        }
    }
}
