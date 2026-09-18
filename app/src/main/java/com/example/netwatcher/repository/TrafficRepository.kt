package com.example.netwatcher.repository

import com.example.netwatcher.model.TrafficLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

object TrafficRepository {

    private const val MAX_LOG_ENTRIES = 500

    private val _logs = MutableStateFlow<List<TrafficLogEntry>>(emptyList())
    val logs: StateFlow<List<TrafficLogEntry>> = _logs.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _selectedPackageName = MutableStateFlow<String?>(null)
    val selectedPackageName: StateFlow<String?> = _selectedPackageName.asStateFlow()

    private val _selectedAppName = MutableStateFlow<String>("All Applications")
    val selectedAppName: StateFlow<String> = _selectedAppName.asStateFlow()

    private val logQueue = ConcurrentLinkedQueue<TrafficLogEntry>()

    fun addLog(entry: TrafficLogEntry) {
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
        _logs.value = logQueue.toList().reversed()
    }

    fun clearLogs() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    fun setMonitoring(active: Boolean) {
        _isMonitoring.value = active
    }

    fun setSelectedApp(packageName: String?, appName: String) {
        _selectedPackageName.value = packageName
        _selectedAppName.value = appName
    }
}
