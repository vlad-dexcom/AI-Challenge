package com.example.geminichat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.BranchOption

/**
 * Day 10 branching controls, now surfaced as a bottom sheet from the app bar's branch button
 * instead of an inline dropdown. Each branch is a toggle-style switch: only the active branch's
 * switch is on, and flipping another branch's switch on selects it (mirrors single-select radio
 * semantics while satisfying the "toggle" look). "Save checkpoint" / "Branch from checkpoint"
 * still work the same as before — see [com.example.geminichat.ChatController.onSaveCheckpoint] /
 * [com.example.geminichat.ChatController.onCreateBranchFromCheckpoint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchBottomSheet(
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
