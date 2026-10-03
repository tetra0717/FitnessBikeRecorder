package com.fbreco.pclink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
fun PcLinkDialog(server: PcLinkServer, onDismiss: () -> Unit) {
    val status by server.status.collectAsState()
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC連携") },
        text = {
            Column {
                Text("同じWi-FiのPCで、トレイの「設定」からこのスマホを登録してください。接続コードは初回だけ必要です。")
                Spacer(Modifier.height(16.dp))
                Text("接続コード", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(server.pairingCode.uppercase().chunked(4).joinToString("-")) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(server.pairingCode)) }) { Text("接続コードをコピー") }
                Spacer(Modifier.height(8.dp))
                Text(if (status.clients > 0) "PC接続中：${status.clients}台" else status.message)
                Spacer(Modifier.height(8.dp))
                Text("自動検出できない場合の手動接続情報", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text("端末ID：${server.deviceId}\n接続先：${status.endpoint.ifEmpty { "Wi-Fi接続後に表示されます" }}") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
