package com.example.netwatcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netwatcher.model.TrafficLogEntry
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogDetailDialog(
    entry: TrafficLogEntry,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    val formattedTime = dateFormat.format(Date(entry.timestamp))

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Connection Details",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow(label = "Requested Host / Domain", value = entry.domain ?: "Not Available / Direct IP")
                DetailRow(label = "Destination IP", value = entry.destinationIp)
                DetailRow(label = "Remote Port", value = entry.destinationPort.toString())
                DetailRow(label = "Protocol", value = entry.protocol)
                DetailRow(label = "Target Application", value = "${entry.appName} (${entry.packageName})")
                DetailRow(label = "Source IP & Port", value = if (entry.sourceIp.isNotEmpty()) "${entry.sourceIp}:${entry.sourcePort}" else "10.1.10.1")
                DetailRow(label = "Timestamp", value = formattedTime)
                DetailRow(label = "Packet Size", value = "${entry.packetSize} bytes")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    copyToClipboard(context, entry)
                    Toast.makeText(context, "Copied connection details to clipboard", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy Info")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun copyToClipboard(context: Context, entry: TrafficLogEntry) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = """
        Domain: ${entry.domain ?: "N/A"}
        Destination IP: ${entry.destinationIp}
        Destination Port: ${entry.destinationPort}
        Protocol: ${entry.protocol}
        App: ${entry.appName} (${entry.packageName})
        Time: ${Date(entry.timestamp)}
    """.trimIndent()
    val clip = ClipData.newPlainText("NetWatcher Connection", text)
    clipboard.setPrimaryClip(clip)
}
