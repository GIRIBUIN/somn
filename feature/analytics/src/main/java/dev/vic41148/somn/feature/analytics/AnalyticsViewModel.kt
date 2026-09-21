package dev.vic41148.somn.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vic41148.somn.core.data.repository.SleepRepository
import dev.vic41148.somn.core.domain.model.SleepSession
import dev.vic41148.somn.core.domain.model.AudioEvent
import dev.vic41148.somn.core.domain.usecase.ExportCsvUseCase
import dev.vic41148.somn.core.domain.usecase.ReportSummary
import dev.vic41148.somn.core.domain.usecase.WeeklyConsistency
import dev.vic41148.somn.core.domain.usecase.computeConsistency
import dev.vic41148.somn.core.domain.usecase.spentDatesForMonth
import dev.vic41148.somn.core.domain.usecase.spentDatesToCsv
import dev.vic41148.somn.core.domain.usecase.summarizeSessions
import dev.vic41148.somn.core.domain.usecase.toGraceMonthKey
import dev.vic41148.somn.core.domain.usecase.weeklyConsistency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val sleepRepository: SleepRepository,
    private val tagRepository: dev.vic41148.somn.core.data.repository.TagRepository,
    private val exportCsv: ExportCsvUseCase,
    private val audioClipStore: dev.vic41148.somn.core.data.audio.AudioClipStore,
    private val preferencesRepository: dev.vic41148.somn.core.data.repository.SomnPreferencesRepository
) : ViewModel() {

    val sessions = sleepRepository.observeCompletedSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** R2 Rest Mode boundary, null when off, sick nights leave the summary math. */
    val restModeSince: StateFlow<Long?> = preferencesRepository.restModeSince
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Report range in days (null = all history). Defaults to 30 so the header reads as "recent". */
    private val _rangeDays = MutableStateFlow<Int?>(30)
    val rangeDays: StateFlow<Int?> = _rangeDays.asStateFlow()

    fun selectRange(days: Int?) {
        _rangeDays.value = days
    }

    /** Sessions inside the selected range, newest first, drives both the header stats and the list. */
    val rangedSessions: StateFlow<List<SleepSession>> = combine(sessions, rangeDays) { list, days ->
        if (days == null) list
        else {
            val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
            list.filter { it.startTimeMillis >= cutoff }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Spec §6.6 gamification kill-switch, hides streaks/rings/badges everywhere downstream. */
    val showStreaksScores: StateFlow<Boolean> = preferencesRepository.showStreaksScores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val graceMonth: StateFlow<String?> = preferencesRepository.graceMonth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val graceDatesCsv: StateFlow<String?> = preferencesRepository.graceDatesCsv
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Aggregate stats for the selected range. A null value means an empty range, and the caller shows the empty state. */
    val summary: StateFlow<ReportSummary?> =
        combine(rangedSessions, restModeSince, graceMonth, graceDatesCsv) { list, since, month, csv ->
            summarizeSessions(
                list,
                since,
                java.time.LocalDate.now(),
                spentDatesForMonth(month, csv, java.time.YearMonth.now())
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Spec §6: global Consistency Streak over full history (the range summary above
     * stays range-relative). The ledger records forgiven *dates*, so the write-back
     * converges after one union instead of oscillating the number.
     */
    private data class ConsistencyLedger(
        val result: dev.vic41148.somn.core.domain.usecase.ConsistencyResult,
        val monthKey: String,
        val spent: Set<java.time.LocalDate>
    )

    private val ledger: StateFlow<ConsistencyLedger?> =
        combine(sessions, restModeSince, graceMonth, graceDatesCsv) { list, since, month, csv ->
            if (list.isEmpty()) {
                null
            } else {
                val zone = java.time.ZoneId.systemDefault()
                val today = java.time.LocalDate.now()
                val logged = list.filter { it.isCompleted }
                    .map {
                        java.time.Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate()
                    }.toSet()
                val frozen = if (since == null) emptySet()
                else generateSequence(
                    java.time.Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
                ) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toSet()
                val now = java.time.YearMonth.now()
                val spent = spentDatesForMonth(month, csv, now)
                val result = computeConsistency(logged, today, spent, frozen)
                ConsistencyLedger(result, now.toGraceMonthKey(), spent)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val consistency: StateFlow<dev.vic41148.somn.core.domain.usecase.ConsistencyResult?> =
        ledger.map { it?.result }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Spec §6.4: rolling 7-day ring data, oldest → newest ending today. */
    val weekly: StateFlow<WeeklyConsistency> = sessions.map { list ->
        val zone = java.time.ZoneId.systemDefault()
        weeklyConsistency(
            list.filter { it.isCompleted }
                .map {
                    java.time.Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate()
                }.toSet()
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        WeeklyConsistency(0, List(7) { false })
    )

    init {
        viewModelScope.launch {
            ledger.collect { entry ->
                if (entry != null && entry.result.newlySpent.isNotEmpty()) {
                    preferencesRepository.recordGraceDates(
                        entry.monthKey,
                        spentDatesToCsv(entry.spent + entry.result.newlySpent)
                    )
                }
            }
        }
    }

    /**
     * R2 vitals dashboard: latest snapshot plus 14-day history, which the code loads off the main
     * thread. Null while loading. An empty list means no wearable data, and the screen explains it.
     */
    val vitalFlags: StateFlow<List<dev.vic41148.somn.core.domain.usecase.VitalFlag>?> =
        sessions.mapLatest { list ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val cutoff = System.currentTimeMillis() - 14 * 24 * 60 * 60 * 1000L
                val window = list.filter { it.isCompleted && it.startTimeMillis >= cutoff }
                    .sortedBy { it.startTimeMillis }
                if (window.isEmpty()) return@withContext emptyList()
                val snaps = window.mapNotNull { session ->
                    runCatching { sleepRepository.getExternalVitals(session.id) }.getOrNull()
                }.filter { it.hasAnyData }
                if (snaps.isEmpty()) return@withContext emptyList()
                dev.vic41148.somn.core.domain.usecase.flagVitals(snaps.last(), snaps)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedSession = MutableStateFlow<SleepSession?>(null)
    val selectedSession: StateFlow<SleepSession?> = _selectedSession.asStateFlow()

    private val _csvExport = MutableStateFlow<String?>(null)
    val csvExport: StateFlow<String?> = _csvExport.asStateFlow()

    private val _selectedSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSessionIds: StateFlow<Set<Long>> = _selectedSessionIds.asStateFlow()

    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    fun toggleSelection(sessionId: Long) {
        val current = _selectedSessionIds.value
        _selectedSessionIds.value = if (current.contains(sessionId)) {
            current - sessionId
        } else {
            current + sessionId
        }
    }

    fun clearBulkSelection() {
        _selectedSessionIds.value = emptySet()
    }

    fun deleteSelectedSessions() {
        val ids = _selectedSessionIds.value
        viewModelScope.launch {
            ids.forEach { id ->
                val session = sessions.value.find { it.id == id }
                if (session != null) {
                    sleepRepository.deleteSession(session)
                }
            }
            clearBulkSelection()
        }
    }

    fun exportSelectedSessions(context: android.content.Context, folderUri: android.net.Uri) {
        val sessionIds = _selectedSessionIds.value
        if (sessionIds.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _exportProgress.value = 0f
            try {
                val total = sessionIds.size
                var count = 0
                
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                
                sessionIds.forEach { sessionId ->
                    val events = sleepRepository.getAudioEventsSynchronous(sessionId)
                    events.forEach { event ->
                        event.clipPath?.let { path ->
                            val file = java.io.File(path)
                            if (file.exists()) {
                                // Create the file in SAF
                                val newFile = rootDoc?.createFile("audio/wav", "somn_audio_${sessionId}_${event.id}.wav")
                                newFile?.uri?.let { destUri ->
                                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                                        // Explicit user export: sealed clips are decrypted here.
                                        out.write(audioClipStore.readClipBytes(path))
                                    }
                                }
                            }
                        }
                    }
                    count++
                    _exportProgress.value = count.toFloat() / total
                }
                _exportStatus.value = "Exported $count sessions!"
            } catch (e: Exception) {
                _exportStatus.value = "Export failed: ${e.message}"
            } finally {
                _exportProgress.value = null
            }
        }
    }

    fun selectSession(session: SleepSession) {
        _selectedSession.value = session
    }

    fun clearSelection() {
        _selectedSession.value = null
    }

    fun observeAudioEvents(sessionId: Long) = sleepRepository.observeAudioEvents(sessionId)

    /**
     * Plaintext temp copy of a clip for MediaPlayer. The caller deletes it when done.
     * Legacy plaintext clips return the original file (no copy, nothing to delete).
     */
    fun playableClip(path: String): java.io.File = audioClipStore.playableCopy(path)

    /** Single-clip share: decrypts the sealed clip straight into the SAF file. */
    fun exportClipTo(
        context: android.content.Context,
        event: dev.vic41148.somn.core.domain.model.AudioEvent,
        uri: android.net.Uri
    ) {
        val path = event.clipPath ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(audioClipStore.readClipBytes(path))
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("AnalyticsViewModel", "Clip export failed", e)
                false
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Clip saved" else "Could not save clip",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** HEALTH-01: one-shot fetch. An external sync writes external vitals once per sync, so the screen needs no live-updating Flow. */
    suspend fun getExternalVitals(sessionId: Long) = sleepRepository.getExternalVitals(sessionId)

    fun deleteSession(session: SleepSession) {
        viewModelScope.launch {
            sleepRepository.deleteSession(session)
            _selectedSession.value = null
        }
    }

    /** R3 Reports: distinct tag names across sessions, for the report + PDF tag list. */
    suspend fun tagNamesFor(sessionIds: List<Long>): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sessionIds.flatMap { id ->
                runCatching { tagRepository.getTagsForSession(id) }.getOrDefault(emptyList())
            }.map { it.name }.distinct()
        }

    /** R4: all tags for the Session Detail picker, seeded with the default taxonomy. */
    val allTags = tagRepository.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun observeSessionTags(sessionId: Long) = tagRepository.observeTagsForSession(sessionId)

    fun toggleSessionTag(sessionId: Long, tagId: Long, attached: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { tagRepository.ensureDefaultTags() }
            if (attached) tagRepository.removeTagFromSession(sessionId, tagId)
            else tagRepository.addTagToSession(sessionId, tagId)
        }
    }

    fun exportAllSessions() {
        viewModelScope.launch {
            val allSessions = sessions.value
            val csv = exportCsv(allSessions)
            _csvExport.value = csv
        }
    }

    fun clearExport() {
        _exportStatus.value = null
        _csvExport.value = null
    }
}
