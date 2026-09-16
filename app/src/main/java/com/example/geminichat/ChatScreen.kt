package com.example.geminichat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.memory.MemoryRoutingDecision
import com.example.geminichat.agent.memory.MemorySnapshot
import com.example.geminichat.agent.profile.ExpertiseLevel
import com.example.geminichat.agent.profile.PreferenceSuggestion
import com.example.geminichat.agent.profile.ProfileField
import com.example.geminichat.agent.profile.UserProfile
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * The single screen of the app: a scrollable message list plus a text input row that sends
 * the user's message to the trainer agent and appends its reply. The app bar exposes the
 * branch switcher (bottom sheet) and the settings screen (model + profile) via icon buttons;
 * it no longer offers an agent picker since the app is trainer-only by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    if (showSettings) {
        SettingsScreen(uiState = uiState, viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    if (showBranchSheet) {
        BranchBottomSheet(
            branches = uiState.branches,
            currentBranchId = uiState.currentBranchId,
            hasCheckpoint = uiState.hasCheckpoint,
            enabled = !uiState.isLoading,
            onBranchSelected = viewModel::onBranchSelected,
            onSaveCheckpoint = viewModel::onSaveCheckpoint,
            onCreateBranch = viewModel::onCreateBranchFromCheckpoint,
            onDismiss = { showBranchSheet = false }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(uiState.agentName) },
                    actions = {
                        IconButton(onClick = { showBranchSheet = true }) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_branch),
                                contentDescription = "Branch"
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(

                                Icons.Filled.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
                if (uiState.dialogTokenTotal > 0) {
                    Text(
                        text = "Tokens in dialog: ${uiState.dialogTokenTotal} (estimated)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                MemoryPanel(
                    longTermMemory = uiState.longTermMemory,
                    workingMemory = uiState.workingMemory,
                    memoryRoutingTokensTotal = uiState.memoryRoutingTokensTotal,
                    lastMemoryDecisions = uiState.lastMemoryDecisions,
                    enabled = !uiState.isLoading,
                    onPromote = viewModel::onPromoteToLongTerm,
                    onDeleteWorking = viewModel::onDeleteWorkingItem,
                    onDeleteLongTerm = viewModel::onDeleteLongTermItem,
                    onAddLongTerm = viewModel::onAddLongTermItem,
                    onEndTask = viewModel::onEndTask,
                    onClearDialog = viewModel::onClearDialog
                )
                uiState.pendingPreferenceSuggestion?.let { suggestion ->
                    SuggestionBanner(
                        suggestion = suggestion,
                        onApply = viewModel::onApplySuggestion,
                        onDismiss = viewModel::onDismissSuggestion
                    )
                }
            }
        }
    ) { padding ->
        // imePadding() lets this column shrink above the keyboard instead of letting
        // messages scroll underneath it; combined with windowSoftInputMode="adjustResize"
        // (see AndroidManifest) the whole layout resizes cleanly when the keyboard opens.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { message ->
                    MessageBubble(message)
                }
                if (uiState.isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Gemini...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.size(8.dp))
                IconButton(onClick = { viewModel.sendMessage() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Day 11 memory inspector. Lists every item in
 * [com.example.geminichat.agent.memory.MemoryLayer.WORKING] and
 * [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] *separately* — so "what data
 * landed in which layer" is directly checkable, not just inferred from the model's answers —
 * plus manual overrides (promote/delete/add — see [ChatViewModel]) and "End task"/"Clear
 * dialog", which demonstrate the three layers are independent by only ever clearing one of
 * them at a time.
 */
@Composable
private fun MemoryPanel(
    longTermMemory: MemorySnapshot,
    workingMemory: MemorySnapshot,
    memoryRoutingTokensTotal: Int,
    lastMemoryDecisions: List<MemoryRoutingDecision>,
    enabled: Boolean,
    onPromote: (String) -> Unit,
    onDeleteWorking: (String) -> Unit,
    onDeleteLongTerm: (String) -> Unit,
    onAddLongTerm: (String, String) -> Unit,
    onEndTask: () -> Unit,
    onClearDialog: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Memory: ${workingMemory.items.size} working · " +
                    "${longTermMemory.items.size} long-term · routing cost " +
                    "$memoryRoutingTokensTotal tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Long-term (profile, decisions, knowledge)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (longTermMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        longTermMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}" + if (item.pinned) " (pinned)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDeleteLongTerm(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                placeholder = { Text("key") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(4.dp))
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                placeholder = { Text("value") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddLongTerm(newKey, newValue)
                    newKey = ""
                    newValue = ""
                },
                enabled = enabled && newKey.isNotBlank() && newValue.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Text(
            text = "Working (current task)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (workingMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        workingMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onPromote(item.key) }, enabled = enabled) {
                    Text("Promote")
                }
                TextButton(onClick = { onDeleteWorking(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }

        if (lastMemoryDecisions.isNotEmpty()) {
            Text(
                text = "Last turn routed: " + lastMemoryDecisions.joinToString("; ") { decision ->
                    "${decision.layer} ${decision.key} (${decision.reason})"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onEndTask, enabled = enabled) {
                Text("End task")
            }
            TextButton(onClick = onClearDialog, enabled = enabled) {
                Text("Clear dialog")
            }
        }
    }
}

/**
 * Day 12 personalization panel: edits the single, global [UserProfile] in place — there is no
 * per-profile selector, since there is only ever one profile (see [UserProfile]). The preset
 * row at the top is a shortcut that overwrites every field at once with one of
 * [UserProfile.PRESETS], so maximally different profiles can be tried back to back; regular
 * text fields still commit on every keystroke via [onFieldChange] (mirrors [MemoryPanel]'s
 * immediate-apply style), and constraints are a separate add/remove list since they're a set,
 * not a single overwritable value.
 */
@Composable
private fun ProfilePanel(
    profile: UserProfile,
    personalizationTokensTotal: Int,
    enabled: Boolean,
    onFieldChange: (ProfileField, String) -> Unit,
    onAddConstraint: (String) -> Unit,
    onRemoveConstraint: (String) -> Unit,
    onApplyPreset: (UserProfile) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var newConstraint by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (profile.isEmpty()) {
                    "Profile: not set up · personalization cost $personalizationTokensTotal tokens"
                } else {
                    "Profile: ${profile.displayName.ifBlank { "(unnamed)" }} · " +
                        "personalization cost $personalizationTokensTotal tokens"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Presets (test with very different profiles)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            UserProfile.PRESETS.forEach { preset ->
                TextButton(onClick = { onApplyPreset(preset.profile) }, enabled = enabled) {
                    Text(preset.label)
                }
            }
        }

        OutlinedTextField(
            value = profile.displayName,
            onValueChange = { onFieldChange(ProfileField.DISPLAY_NAME, it) },
            label = { Text("Name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.about,
            onValueChange = { onFieldChange(ProfileField.ABOUT, it) },
            label = { Text("About") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.language,
            onValueChange = { onFieldChange(ProfileField.LANGUAGE, it) },
            label = { Text("Language") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        ExpertiseSelector(
            selected = profile.expertise,
            enabled = enabled,
            onSelected = { level -> onFieldChange(ProfileField.EXPERTISE, level.name) }
        )
        OutlinedTextField(
            value = profile.tone,
            onValueChange = { onFieldChange(ProfileField.TONE, it) },
            label = { Text("Tone") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.format,
            onValueChange = { onFieldChange(ProfileField.FORMAT, it) },
            label = { Text("Preferred format") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.maxAnswerSentences?.toString() ?: "",
            onValueChange = { onFieldChange(ProfileField.MAX_ANSWER_SENTENCES, it) },
            label = { Text("Max answer sentences") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.notes,
            onValueChange = { onFieldChange(ProfileField.NOTES, it) },
            label = { Text("Notes") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Text(
            text = "Constraints",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (profile.constraints.isEmpty()) {
            Text(
                "(none)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        profile.constraints.forEach { constraint ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = constraint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemoveConstraint(constraint) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newConstraint,
                onValueChange = { newConstraint = it },
                placeholder = { Text("e.g. no jumping") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddConstraint(newConstraint)
                    newConstraint = ""
                },
                enabled = enabled && newConstraint.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset profile")
            }
        }
    }
}

@Composable
private fun ExpertiseSelector(
    selected: ExpertiseLevel?,
    enabled: Boolean,
    onSelected: (ExpertiseLevel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = "Expertise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                Text(selected?.name?.lowercase() ?: "not set")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select expertise")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ExpertiseLevel.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.name.lowercase()) },
                        onClick = {
                            onSelected(level)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Day 12's hybrid update path made visible: a [com.example.geminichat.agent.profile.PreferenceAdvisor]
 * suggestion is never applied to [UserProfile] automatically — it's shown here with the
 * inferred reason, and only [onApply] (not the advisor call itself) ever changes the profile.
 */
@Composable
private fun SuggestionBanner(
    suggestion: PreferenceSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Assistant suggests updating your profile: ${suggestion.field.name.lowercase()} → " +
                    "\"${suggestion.value}\"",
                style = MaterialTheme.typography.bodySmall
            )
            if (suggestion.reason.isNotBlank()) {
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onApply) { Text("Apply") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Day 10 branching controls, now surfaced as a bottom sheet from the app bar's branch button
 * instead of an inline dropdown. Each branch is a toggle-style switch: only the active branch's
 * switch is on, and flipping another branch's switch on selects it (mirrors single-select radio
 * semantics while satisfying the "toggle" look). "Save checkpoint" / "Branch from checkpoint"
 * still work the same as before — see [ChatViewModel.onSaveCheckpoint] /
 * [ChatViewModel.onCreateBranchFromCheckpoint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchBottomSheet(
    branches: List<BranchOption>,
    currentBranchId: String,
    hasCheckpoint: Boolean,
    enabled: Boolean,
    onBranchSelected: (String) -> Unit,
    onSaveCheckpoint: () -> Unit,
    onCreateBranch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = "Branches", style = MaterialTheme.typography.titleMedium)
            branches.forEach { branch ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = branch.name, modifier = Modifier.weight(1f))
                    Switch(
                        checked = branch.id == currentBranchId,
                        onCheckedChange = { isOn -> if (isOn) onBranchSelected(branch.id) },
                        enabled = enabled
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = onSaveCheckpoint, enabled = enabled) {
                    Text("Save checkpoint")
                }
                TextButton(onClick = onCreateBranch, enabled = enabled && hasCheckpoint) {
                    Text("New branch")
                }
            }
        }
    }
}

/**
 * Day-12-cleanup settings screen: model choice and the personalization profile used to live
 * inline on the chat screen's top bar; both now live here, reached via the app bar's settings
 * button, keeping the chat screen focused on the conversation itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(uiState: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                ModelSelector(
                    selectedModel = uiState.selectedModel,
                    availableModels = uiState.availableModels,
                    enabled = !uiState.isLoading,
                    onModelSelected = viewModel::onModelSelected
                )
            }
            ProfilePanel(
                profile = uiState.userProfile,
                personalizationTokensTotal = uiState.personalizationTokensTotal,
                enabled = !uiState.isLoading,
                onFieldChange = viewModel::onProfileFieldChange,
                onAddConstraint = viewModel::onAddConstraint,
                onRemoveConstraint = viewModel::onRemoveConstraint,
                onApplyPreset = viewModel::onApplyPreset,
                onReset = viewModel::onResetProfile
            )
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    availableModels: List<String>,
    enabled: Boolean,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
            Text(selectedModel)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select model")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            if (message.isFromUser) {
                // The user's own input is shown as-is; only the model's replies are
                // rendered as Markdown (bold, lists, code blocks, links, etc.).
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(modifier = Modifier.padding(12.dp)) {
                    MarkdownText(
                        markdown = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        isTextSelectable = true
                    )
                    message.tokenUsage?.let { usage ->
                        Text(
                            text = "prompt ${usage.promptTokens} (history ${usage.historyTokens}, " +
                                "lt ${usage.longTermMemoryTokens}, wm ${usage.workingMemoryTokens}, " +
                                "profile ${usage.profileTokens}) · " +
                                "reply ${usage.completionTokens} · total ${usage.totalTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
