package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.redfluent.vpn.ui.components.Rule
import com.redfluent.vpn.ui.components.ThemedField
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfType

/** 确认框（系统对话框保留自己的按压效果；颜色、字体用 Soft Landing）。 */
@Composable
fun ConfirmDialog(title: String, message: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rf.colors.paper,
        title = { Text(title, style = RfType.text(18f, FontWeight.SemiBold), color = Rf.colors.ink) },
        text = message?.let { { Text(it, style = RfType.text(14.5f), color = Rf.colors.stone) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, style = RfType.text(15f, FontWeight.SemiBold), color = Rf.colors.red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink) }
        },
    )
}

/** 点一台设备：Rename / Log out / Cancel。 */
@Composable
fun DeviceActionsDialog(title: String, canLogOut: Boolean, onRename: () -> Unit, onLogOut: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rf.colors.paper,
        title = { Text(title, style = RfType.text(18f, FontWeight.SemiBold), color = Rf.colors.ink) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Text("Rename", style = RfType.text(15.5f, FontWeight.Medium), color = Rf.colors.ink, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                }
                if (canLogOut) {
                    Rule()
                    TextButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) {
                        Text("Log out", style = RfType.text(15.5f, FontWeight.SemiBold), color = Rf.colors.red, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink) }
        },
    )
}

/** 改设备名。 */
@Composable
fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rf.colors.paper,
        title = { Text("Rename device", style = RfType.text(18f, FontWeight.SemiBold), color = Rf.colors.ink) },
        text = {
            Column {
                ThemedField(name, { name = it.take(80) }, placeholder = "Name", align = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                Rule()
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("Save", style = RfType.text(15f, FontWeight.SemiBold), color = if (name.isNotBlank()) Rf.colors.red else Rf.colors.stone)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink) }
        },
    )
}
