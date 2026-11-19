package com.antigravity.android.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.antigravity.android.ui.theme.GlassmorphicCard
import com.antigravity.android.ui.theme.ObsidianBorder
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianElevated
import com.antigravity.android.ui.theme.ObsidianSlate
import com.antigravity.android.ui.theme.TextMuted
import com.antigravity.android.ui.theme.TextPrimary
import com.antigravity.android.ui.theme.TextSecondary
import com.antigravity.android.ui.viewmodel.AccountProfileUiState
import com.antigravity.android.ui.viewmodel.AiModelOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAndModelSwitcherSheet(
    accountState: AccountProfileUiState,
    onDismiss: () -> Unit,
    onSelectModel: (AiModelOption) -> Unit,
    onOpenSignInAndPairingDialog: () -> Unit,
    onSignOut: () -> Unit,
    onToggleWorkspaceSync: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianDeep,
        contentColor = TextPrimary
    ) {
        AccountAndModelSheetContent(
            accountState = accountState,
            onClose = onDismiss,
            onSelectModel = onSelectModel,
            onOpenSignInAndPairingDialog = onOpenSignInAndPairingDialog,
            onSignOut = onSignOut,
            onToggleWorkspaceSync = onToggleWorkspaceSync
        )
    }
}

@Composable
fun AccountAndModelSheetContent(
    accountState: AccountProfileUiState,
    onClose: () -> Unit,
    onSelectModel: (AiModelOption) -> Unit,
    onOpenSignInAndPairingDialog: () -> Unit,
    onSignOut: () -> Unit,
    onToggleWorkspaceSync: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sheet Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ANTIGRAVITY IDENTITY & ENGINE",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
                Text(
                    text = "Account, PC Pairing & AI Model",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close sheet",
                    tint = TextSecondary
                )
            }
        }

        // 1. Signed-In Antigravity / Google Account Profile Card
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            isHighlighted = accountState.isSignedIn,
            containerColor = ObsidianSlate,
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar Badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(CinemaGradients.CyanToViolet),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accountState.displayName.take(1).uppercase().ifBlank { "A" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = ObsidianDeep,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (accountState.isSignedIn) accountState.displayName else "Not Signed In",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        CinemaStatusBadge(
                            label = accountState.planTier,
                            accentColor = AntigravityVioletBright
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (accountState.isSignedIn) accountState.email else "Sign in with Google / Antigravity OAuth or API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Auth token status",
                            tint = if (accountState.hasValidToken) AgentWorkingEmerald else AgentWaitingAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = accountState.authMethodLabel,
                            style = CinemaTextStyles.ChecksumBadge,
                            color = if (accountState.hasValidToken) AgentWorkingEmerald else AgentWaitingAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenSignInAndPairingDialog,
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AntigravityCyan,
                        contentColor = ObsidianDeep
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Sign In or Pair",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (accountState.isSignedIn) "Switch / Pair Token" else "One-Tap Sign In",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (accountState.isSignedIn) {
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier.weight(0.9f),
                        border = BorderStroke(1.dp, AgentErrorCrimson.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AgentErrorCrimson)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign Out",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out")
                    }
                }
            }
        }

        // 2. PC Orchestrator Pairing Status Card
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = ObsidianSlate,
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = "PC Orchestrator",
                        tint = AntigravityCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "PC Orchestrator Pairing",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = accountState.pcHostName,
                            style = CinemaTextStyles.ChecksumBadge,
                            color = AntigravityCyan
                        )
                    }
                }

                CinemaStatusBadge(
                    label = if (accountState.pcPairingConnected) "PAIRED • LIVE" else "OFFLINE",
                    accentColor = if (accountState.pcPairingConnected) AgentWorkingEmerald else AgentWaitingAmber,
                    isPulsing = accountState.pcPairingConnected
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianDeep)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Endpoint: ${accountState.pcEndpointUrl}",
                    style = CinemaTextStyles.TerminalOutput,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "CSRF Header: x-codeium-csrf-token (${accountState.csrfTokenPreview})",
                    style = CinemaTextStyles.TerminalOutput,
                    color = AgentWorkingEmerald
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Workspace Sync",
                        tint = AntigravityVioletBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Real-Time PC Workspace & Account Sync",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Syncs active model, cascade trajectory & swarm board",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                Switch(
                    checked = accountState.workspaceSyncEnabled,
                    onCheckedChange = onToggleWorkspaceSync,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianDeep,
                        checkedTrackColor = AntigravityCyan,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = ObsidianElevated
                    )
                )
            }
        }

        // 3. Active AI Model Selector (Gemini 4 Argon High, Pro, Flash)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "ACTIVE AI MODEL SELECTOR",
                style = CinemaTextStyles.ChecksumBadge,
                color = AntigravityCyan
            )

            AiModelOption.entries.forEach { modelOption ->
                val isSelected = modelOption == accountState.activeModel
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    isHighlighted = isSelected,
                    containerColor = if (isSelected) ObsidianElevated else ObsidianSlate,
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp),
                    onClick = { onSelectModel(modelOption) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                                contentDescription = modelOption.displayName,
                                tint = if (isSelected) AntigravityCyan else AntigravityVioletBright,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = modelOption.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isSelected) AntigravityCyan else TextPrimary
                                )
                                Text(
                                    text = modelOption.reasoningTier,
                                    style = CinemaTextStyles.ChecksumBadge,
                                    color = AntigravityVioletBright
                                )
                            }
                        }

                        CinemaStatusBadge(
                            label = if (isSelected) "ACTIVE" else modelOption.badge,
                            accentColor = if (isSelected) AgentWorkingEmerald else AntigravityVioletBright,
                            isPulsing = isSelected
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = modelOption.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SignInAndPairingDialog(
    accountState: AccountProfileUiState,
    onDismiss: () -> Unit,
    onSignInSubmit: (email: String, displayName: String, tokenOrKey: String) -> Unit,
    onPairPcSubmit: (endpointUrl: String, csrfOrPairingToken: String) -> Unit
) {
    var email by remember { mutableStateOf(accountState.email) }
    var displayName by remember { mutableStateOf(accountState.displayName) }
    var bearerToken by remember { mutableStateOf("ya29.antigravity-oauth-token-951") }
    var pcEndpoint by remember { mutableStateOf(accountState.pcEndpointUrl) }
    var csrfToken by remember { mutableStateOf("ag-csrf-89f2c01a-e41c") }

    Dialog(onDismissRequest = onDismiss) {
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            isHighlighted = true,
            containerColor = ObsidianSlate,
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = AntigravityCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sign In & Pair PC Session",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close dialog",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "1. ANTIGRAVITY / GOOGLE ACCOUNT",
                style = CinemaTextStyles.ChecksumBadge,
                color = AntigravityCyan
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianDeep,
                    unfocusedContainerColor = ObsidianDeep,
                    focusedBorderColor = AntigravityCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Google / Antigravity Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianDeep,
                    unfocusedContainerColor = ObsidianDeep,
                    focusedBorderColor = AntigravityCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                label = { Text("OAuth Bearer Token / Gemini API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianDeep,
                    unfocusedContainerColor = ObsidianDeep,
                    focusedBorderColor = AntigravityCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onSignInSubmit(email, displayName, bearerToken) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AntigravityCyan,
                    contentColor = ObsidianDeep
                )
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "One-Tap Sign In",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "One-Tap Sign In & Persist Token",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "2. ONE-TAP PC ORCHESTRATOR PAIRING",
                style = CinemaTextStyles.ChecksumBadge,
                color = AntigravityVioletBright
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = pcEndpoint,
                onValueChange = { pcEndpoint = it },
                label = { Text("PC Connect-RPC WebSocket Endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianDeep,
                    unfocusedContainerColor = ObsidianDeep,
                    focusedBorderColor = AntigravityViolet,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = csrfToken,
                onValueChange = { csrfToken = it },
                label = { Text("Pairing / x-codeium-csrf-token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianDeep,
                    unfocusedContainerColor = ObsidianDeep,
                    focusedBorderColor = AntigravityViolet,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onPairPcSubmit(pcEndpoint, csrfToken) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AntigravityViolet,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = "Pair with PC",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Pair with PC Orchestrator",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
