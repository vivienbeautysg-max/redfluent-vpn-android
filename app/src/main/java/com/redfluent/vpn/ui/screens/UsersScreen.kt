package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.data.ACCESS_PRESETS
import com.redfluent.vpn.data.AccessChoice
import com.redfluent.vpn.data.DAYS_LEFT_WARN
import com.redfluent.vpn.data.MAX_DURATION_DAYS
import com.redfluent.vpn.data.OwnerInvite
import com.redfluent.vpn.data.ProtectedKind
import com.redfluent.vpn.data.RegionOption
import com.redfluent.vpn.data.accessChoiceOf
import com.redfluent.vpn.data.buildInviteUpdate
import com.redfluent.vpn.data.inviteStatusLine
import com.redfluent.vpn.data.parseCustomDays
import com.redfluent.vpn.data.protectedKind
import com.redfluent.vpn.data.regionChoices
import com.redfluent.vpn.ui.InviteSaveState
import com.redfluent.vpn.ui.UsersUiState
import com.redfluent.vpn.ui.theme.Brand
import com.redfluent.vpn.ui.theme.Radius
import com.redfluent.vpn.ui.theme.Spacing
import com.redfluent.vpn.data.InviteUpdate

/**
 * Owner 的 Users 页：每个邀请码（= 用户）一行，显示区域和使用时长状态；点一行 → 底部面板改区域 / 时长。
 * 返回 = 系统返回手势 / 返回键（MainActivity 的 BackHandler），或左上角箭头；面板打开时返回先关面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    state: UsersUiState,
    ownerRegions: List<RegionOption>,
    ownerCode: String?,
    ownerLabel: String?,
    saveState: InviteSaveState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSave: (InviteUpdate) -> Unit,
    onSaveHandled: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    var editing by remember { mutableStateOf<OwnerInvite?>(null) }

    // 保存成功：关闭面板
    LaunchedEffect(saveState) {
        if (saveState is InviteSaveState.Done) {
            editing = null
            onSaveHandled()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Primary.copy(alpha = 0.10f), bg))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .widthIn(max = 640.dp)
                .fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Users", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }

            when (state) {
                UsersUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Primary)
                }

                is UsersUiState.Error -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.message, color = Brand.Danger, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Primary),
                    ) { Text("Retry") }
                }

                is UsersUiState.Loaded -> {
                    if (state.invites.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("No users", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Spacing.md,
                                end = Spacing.md,
                                top = Spacing.sm,
                                bottom = Spacing.xl,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (state.refreshFailed) {
                                item(key = "refresh-failed") {
                                    Text("Refresh failed", color = Brand.Danger, fontSize = 12.sp)
                                }
                            }
                            // 可折叠的列表：默认收起，标题显示 "Users · N"
                            item(key = "users-header") {
                                SectionHeader(
                                    title = "Users · ${state.invites.size}",
                                    expanded = expanded,
                                    onClick = onToggleExpanded,
                                )
                            }
                            if (expanded) {
                                items(state.invites, key = { it.code.ifEmpty { it.label } }) { invite ->
                                    UserRow(invite = invite, onClick = { editing = invite })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { invite ->
        EditSheet(
            invite = invite,
            kind = protectedKind(invite, ownerCode, ownerLabel),
            ownerRegions = ownerRegions,
            saveState = saveState,
            onDismiss = {
                editing = null
                onSaveHandled()
            },
            onSave = onSave,
        )
    }
}

/** 折叠段的标题行：整行可点，右边是展开 / 收起箭头。 */
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserRow(invite: OwnerInvite, onClick: () -> Unit) {
    val status = inviteStatusLine(invite)
    val statusColor = when {
        invite.expired -> Brand.Danger
        (invite.daysLeft ?: Int.MAX_VALUE) <= DAYS_LEFT_WARN && invite.effectiveExpiresAt != null -> Brand.Warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .alpha(if (invite.enabled) 1f else 0.55f)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                invite.label.ifBlank { "—" },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (invite.enabled) status else "$status · Disabled",
                fontSize = 13.sp,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            RegionOption(invite.regionId).label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Brand.Primary,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .background(Brand.Primary.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

/** Access 里"自定义"之外的选项类型。 */
private sealed interface AccessPick {
    data object NoLimit : AccessPick
    data class Preset(val days: Int) : AccessPick
    data object Custom : AccessPick
}

private fun initialPick(invite: OwnerInvite): AccessPick = when (val c = accessChoiceOf(invite)) {
    AccessChoice.NoLimit -> AccessPick.NoLimit
    is AccessChoice.Days -> if (c.days in ACCESS_PRESETS) AccessPick.Preset(c.days) else AccessPick.Custom
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditSheet(
    invite: OwnerInvite,
    kind: ProtectedKind?,
    ownerRegions: List<RegionOption>,
    saveState: InviteSaveState,
    onDismiss: () -> Unit,
    onSave: (InviteUpdate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var region by remember(invite) { mutableStateOf(invite.regionId) }
    var pick by remember(invite) { mutableStateOf(initialPick(invite)) }
    var customText by remember(invite) {
        mutableStateOf(invite.durationDays?.takeIf { it !in ACCESS_PRESETS }?.toString().orEmpty())
    }

    val regionsAllowed = kind != ProtectedKind.OWNER
    val accessAllowed = kind == null
    val saving = saveState is InviteSaveState.Saving

    val access: AccessChoice? = when (val p = pick) {
        AccessPick.NoLimit -> AccessChoice.NoLimit
        is AccessPick.Preset -> AccessChoice.Days(p.days)
        AccessPick.Custom -> parseCustomDays(customText)?.let { AccessChoice.Days(it) }
    }
    val update = access?.let { buildInviteUpdate(invite, region, it, kind) }
    val customInvalid = pick == AccessPick.Custom && access == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(invite.label.ifBlank { "—" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

            // ---- Region ----
            Text("Region", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val choices = regionChoices(ownerRegions, invite.regionId)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, r ->
                    SegmentedButton(
                        selected = r.id == region,
                        onClick = { region = r.id },
                        enabled = regionsAllowed && !saving,
                        shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Brand.Primary.copy(alpha = 0.16f),
                            activeContentColor = Brand.Primary,
                            activeBorderColor = Brand.Primary,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        label = { Text(r.label) },
                    )
                }
            }

            // ---- Access ----
            Text("Access", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AccessChip("No limit", pick == AccessPick.NoLimit, accessAllowed && !saving) { pick = AccessPick.NoLimit }
                ACCESS_PRESETS.forEach { d ->
                    AccessChip("$d days", pick == AccessPick.Preset(d), accessAllowed && !saving) { pick = AccessPick.Preset(d) }
                }
                AccessChip("Custom", pick == AccessPick.Custom, accessAllowed && !saving) { pick = AccessPick.Custom }
            }
            if (pick == AccessPick.Custom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v -> customText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Days (1–$MAX_DURATION_DAYS)") },
                    singleLine = true,
                    enabled = accessAllowed && !saving,
                    isError = customInvalid && customText.isNotEmpty(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (saveState is InviteSaveState.Error) {
                Text(saveState.message, color = Brand.Danger, fontSize = 14.sp)
            }

            Button(
                onClick = { update?.let(onSave) },
                enabled = update != null && !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Primary),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text("Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Brand.Primary.copy(alpha = 0.16f),
            selectedLabelColor = Brand.Primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            selectedBorderColor = Brand.Primary,
        ),
    )
}
