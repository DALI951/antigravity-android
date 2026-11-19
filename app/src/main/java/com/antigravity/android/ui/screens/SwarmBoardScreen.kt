package com.antigravity.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.android.ui.theme.AgentCoordinatorViolet
import com.antigravity.android.ui.theme.AgentErrorCrimson
import com.antigravity.android.ui.theme.AgentVerifierCyan
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
import com.antigravity.android.ui.viewmodel.CinemaUiState
import com.antigravity.android.ui.viewmodel.SwarmBoardMessage
import com.antigravity.android.ui.viewmodel.SwarmChannelFilter
import com.antigravity.android.ui.viewmodel.SwarmMemberState
import com.antigravity.android.ui.viewmodel.SwarmRosterMember

@Composable
fun SwarmBoardScreen(
    uiState: CinemaUiState,
    onSelectFilter: (SwarmChannelFilter) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onPostBoardMessage: (String?) -> Unit,
    onSpawnHelperAgent: (name: String, role: String, brief: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianDeep)
    ) {
        // Swarm Overview Header + Roster Strip
        SwarmRosterHeaderSection(
            swarmTitle = uiState.swarmTitle,
            roster = uiState.swarmRoster,
            headSeq = uiState.swarmMessages.maxOfOrNull { it.seq } ?: 0,
            onMentionMember = { memberName ->
                val prefix = "@$memberName "
                if (!uiState.swarmComposerText.contains(prefix)) {
                    onComposerTextChanged(prefix + uiState.swarmComposerText)
                }
            },
            onSpawnDefaultHelper = {
                val count = uiState.swarmRoster.size + 1
                onSpawnHelperAgent(
                    "perf-auditor-$count",
                    "Worker • Perf",
                    "Verify frame pacing and PackageInstaller latency, then report to @architect and @verifier."
                )
            }
        )

        // Filter Pills & Search Strip
        SwarmFilterAndSearchBar(
            selectedFilter = uiState.swarmFilter,
            searchQuery = uiState.swarmSearchQuery,
            onSelectFilter = onSelectFilter,
            onSearchQueryChanged = onSearchQueryChanged
        )

        // Shared Message Board Feed (#seq, @mentions)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = uiState.filteredSwarmMessages,
                key = { "msg-${it.seq}" }
            ) { msg ->
                SwarmBoardMessageCard(
                    message = msg,
                    onMentionClicked = { mention ->
                        onComposerTextChanged("$mention ${uiState.swarmComposerText}")
                    }
                )
            }
        }

        // Bottom Swarm Board Composer (@mention wake doorbell)
        SwarmBoardComposerDock(
            composerText = uiState.swarmComposerText,
            onTextChanged = onComposerTextChanged,
            onQuickTag = { tag ->
                onComposerTextChanged("$tag ${uiState.swarmComposerText}".trim() + " ")
            },
            onPostClicked = { onPostBoardMessage(null) }
        )
    }
}

@Composable
private fun SwarmRosterHeaderSection(
    swarmTitle: String,
    roster: List<SwarmRosterMember>,
    headSeq: Int,
    onMentionMember: (String) -> Unit,
    onSpawnDefaultHelper: () -> Unit
) {
    val workingCount = roster.count { it.state == SwarmMemberState.WORKING }
    val waitingCount = roster.count { it.state == SwarmMemberState.WAITING }
    val verifierCount = roster.count { it.state == SwarmMemberState.VERIFIER }
    val coordinatorCount = roster.count { it.state == SwarmMemberState.COORDINATOR }

    Surface(
        color = ObsidianSlate,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            // Top summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "Swarm Roster",
                            tint = AntigravityCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NATIVE SWARM LIVE BOARD • HEAD #$headSeq",
                            style = CinemaTextStyles.ChecksumBadge,
                            color = AntigravityCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = swarmTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onSpawnDefaultHelper,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AntigravityVioletSurface,
                        contentColor = AntigravityVioletBright
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GroupAdd,
                        contentDescription = "Spawn Helper",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "+ Helper",
                        style = CinemaTextStyles.ChecksumBadge
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Roster status breakdown pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CinemaStatusBadge(
                    label = "$workingCount WORKING",
                    accentColor = AgentWorkingEmerald,
                    isPulsing = workingCount > 0
                )
                CinemaStatusBadge(
                    label = "$waitingCount WAITING",
                    accentColor = AgentWaitingAmber,
                    isPulsing = false
                )
                CinemaStatusBadge(
                    label = "$verifierCount VERIFIER",
                    accentColor = AgentVerifierCyan,
                    isPulsing = false
                )
                CinemaStatusBadge(
                    label = "$coordinatorCount COORDINATOR",
                    accentColor = AgentCoordinatorViolet,
                    isPulsing = false
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Horizontal Scrollable Agent Roster Cards
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = roster,
                    key = { it.name }
                ) { member ->
                    SwarmRosterMemberCard(
                        member = member,
                        onClick = { onMentionMember(member.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwarmRosterMemberCard(
    member: SwarmRosterMember,
    onClick: () -> Unit
) {
    val stateColor = swarmStateColor(member.state)
    val isPulsing = member.state == SwarmMemberState.WORKING || member.state == SwarmMemberState.COORDINATOR

    GlassmorphicCard(
        modifier = Modifier.width(220.dp),
        isHighlighted = member.state == SwarmMemberState.WORKING,
        containerColor = ObsidianElevated,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "@${member.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            CinemaStatusBadge(
                label = member.state.displayLabel,
                accentColor = stateColor,
                isPulsing = isPulsing
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${member.roleLabel} • ${member.modelDisplayName}",
            style = MaterialTheme.typography.labelSmall,
            color = AntigravityCyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = member.currentSlice,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "read #${member.lastReadSeq}",
                style = CinemaTextStyles.ChecksumBadge,
                color = TextMuted
            )
            Text(
                text = "Tap to @mention",
                style = CinemaTextStyles.ChecksumBadge,
                color = AntigravityVioletBright
            )
        }
    }
}

@Composable
private fun SwarmFilterAndSearchBar(
    selectedFilter: SwarmChannelFilter,
    searchQuery: String,
    onSelectFilter: (SwarmChannelFilter) -> Unit,
    onSearchQueryChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianDeep)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Channel Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SwarmChannelFilter.entries.forEach { filter ->
                val isSelected = filter == selectedFilter
                val shape = RoundedCornerShape(50)
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (isSelected) AntigravityCyanSurface else ObsidianSlate)
                        .border(
                            1.dp,
                            if (isSelected) AntigravityCyan else ObsidianBorder,
                            shape
                        )
                        .clickable { onSelectFilter(filter) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) AntigravityCyan else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Filter board by #seq, @agent, or keyword...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search swarm board",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ObsidianSlate,
                unfocusedContainerColor = ObsidianSlate,
                focusedBorderColor = AntigravityCyan,
                unfocusedBorderColor = ObsidianBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwarmBoardMessageCard(
    message: SwarmBoardMessage,
    onMentionClicked: (String) -> Unit
) {
    val authorAccent = swarmStateColor(message.authorState)

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        isHighlighted = message.kind == "architecture" || message.kind == "verify",
        containerColor = ObsidianSlate,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // #seq pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AntigravityCyanSurface)
                        .border(1.dp, AntigravityCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "#${message.seq}",
                        style = CinemaTextStyles.SequenceNumber
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = message.author,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.width(8.dp))

                CinemaStatusBadge(
                    label = message.authorState.displayLabel,
                    accentColor = authorAccent,
                    isPulsing = false
                )
            }

            Text(
                text = message.timestampLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        // @Mentions badges row
        if (message.mentions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.mentions.forEach { mention ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AntigravityVioletSurface)
                            .border(1.dp, AntigravityViolet.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .clickable { onMentionClicked(mention) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = mention,
                            style = CinemaTextStyles.ChecksumBadge,
                            color = AntigravityVioletBright
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}

@Composable
private fun SwarmBoardComposerDock(
    composerText: String,
    onTextChanged: (String) -> Unit,
    onQuickTag: (String) -> Unit,
    onPostClicked: () -> Unit
) {
    val quickMentions = listOf("@all", "@coordinator", "@architect", "@phone-agent", "@auth-account", "@verifier")

    Surface(
        color = ObsidianSlate,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickMentions.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ObsidianElevated)
                            .border(BorderStroke(1.dp, ObsidianBorder), RoundedCornerShape(50))
                            .clickable { onQuickTag(tag) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tag,
                            style = CinemaTextStyles.ChecksumBadge,
                            color = AntigravityCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = composerText,
                    onValueChange = onTextChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Post to swarm message board (@name to wake agent)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = "Mention agent",
                            tint = AntigravityVioletBright,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ObsidianDeep,
                        unfocusedContainerColor = ObsidianDeep,
                        focusedBorderColor = AntigravityViolet,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(CinemaGradients.CyanToViolet),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onPostClicked) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Post to Swarm Board",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun swarmStateColor(state: SwarmMemberState): Color {
    return when (state) {
        SwarmMemberState.WORKING -> AgentWorkingEmerald
        SwarmMemberState.WAITING -> AgentWaitingAmber
        SwarmMemberState.VERIFIER -> AgentVerifierCyan
        SwarmMemberState.COORDINATOR -> AgentCoordinatorViolet
        SwarmMemberState.COMPLETED -> AgentWorkingEmerald
        SwarmMemberState.FAILED -> AgentErrorCrimson
    }
}
