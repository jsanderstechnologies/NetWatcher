package com.example.netwatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netwatcher.model.AppInfo
import com.example.netwatcher.model.TrafficLogEntry
import com.example.netwatcher.repository.TrafficRepository
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    installedApps: List<AppInfo>,
    onRequestVpnStart: (targetPackage: String?, targetAppName: String) -> Unit,
    onRequestVpnStop: () -> Unit
) {
    val logs by TrafficRepository.logs.collectAsState()
    val isMonitoring by TrafficRepository.isMonitoring.collectAsState()
    val selectedPackageName by TrafficRepository.selectedPackageName.collectAsState()
    val selectedAppName by TrafficRepository.selectedAppName.collectAsState()

    var showAppSelector by remember { mutableStateOf(false) }
    var selectedLogEntry by remember { mutableStateOf<TrafficLogEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, searchQuery) {
        if (searchQuery.isBlank()) {
            logs
        } else {
            logs.filter {
                (it.domain?.contains(searchQuery, ignoreCase = true) == true) ||
                        it.destinationIp.contains(searchQuery, ignoreCase = true) ||
                        it.destinationPort.toString().contains(searchQuery) ||
                        it.protocol.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NetWatcher",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(isMonitoring = isMonitoring)
                    }
                },
                actions = {
                    IconButton(onClick = { TrafficRepository.clearLogs() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // App Selector & Monitor Control Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Target Application",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedAppName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedPackageName ?: "global_capture",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(onClick = { showAppSelector = true }) {
                            Text("Select App")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (isMonitoring) {
                                onRequestVpnStop()
                            } else {
                                onRequestVpnStart(selectedPackageName, selectedAppName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isMonitoring) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMonitoring) "Stop Traffic Monitor" else "Start Traffic Monitor",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar & Stats
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter by domain, IP, port, protocol...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Live Traffic Log (${filteredLogs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (filteredLogs.isNotEmpty()) {
                    Text(
                        text = "Tap entry for full details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredLogs.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = if (isMonitoring) "Waiting for network activity..." else "Tap 'Start Traffic Monitor' to begin capturing traffic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredLogs, key = { it.id }) { item ->
                        TrafficLogCard(entry = item, onClick = { selectedLogEntry = item })
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showAppSelector) {
        AppSelectorDialog(
            installedApps = installedApps,
            onAppSelected = { app ->
                if (app == null) {
                    TrafficRepository.setSelectedApp(null, "All Applications")
                } else {
                    TrafficRepository.setSelectedApp(app.packageName, app.appName)
                }
            },
            onDismissRequest = { showAppSelector = false }
        )
    }

    selectedLogEntry?.let { entry ->
        LogDetailDialog(
            entry = entry,
            onDismissRequest = { selectedLogEntry = null }
        )
    }
}

@Composable
private fun StatusBadge(isMonitoring: Boolean) {
    val bgColor = if (isMonitoring) Color(0xFF2E7D32) else Color(0xFFC62828)
    val text = if (isMonitoring) "ACTIVE" else "STOPPED"

    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TrafficLogCard(
    entry: TrafficLogEntry,
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeString = timeFormat.format(Date(entry.timestamp))

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Requested Domain / Host
                Text(
                    text = entry.domain ?: "Direct IP Request",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (entry.domain != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Destination IP and Port
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${entry.destinationIp} : ${entry.destinationPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                // Protocol Tag
                ProtocolTag(protocol = entry.protocol)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProtocolTag(protocol: String) {
    val color = when (protocol) {
        "TCP" -> Color(0xFF1565C0)
        "UDP" -> Color(0xFF2E7D32)
        else -> Color(0xFF6A1B9A)
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = protocol,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
