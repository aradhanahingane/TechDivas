package com.techdivas.stressanalyzer

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.techdivas.stressanalyzer.data.AuthErrorMapper
import com.techdivas.stressanalyzer.data.ApiActivity
import com.techdivas.stressanalyzer.data.ApiCheckIn
import com.techdivas.stressanalyzer.data.ApiFocusSession
import com.techdivas.stressanalyzer.data.ApiUsage
import com.techdivas.stressanalyzer.data.FirebaseAuthRepository
import com.techdivas.stressanalyzer.data.LocalCheckIn
import com.techdivas.stressanalyzer.data.LocalFocusSession
import com.techdivas.stressanalyzer.data.LocalRecordRepository
import com.techdivas.stressanalyzer.data.RhythmRepository
import com.techdivas.stressanalyzer.data.UsageDayObservation
import com.techdivas.stressanalyzer.data.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class RhythmAccount(val uid: String, val email: String)

class RhythmViewModel(application: Application) : AndroidViewModel(application) {
    private val authRepository = FirebaseAuthRepository()
    private val usageRepository = UsageStatsRepository(application)
    private val localRecords = LocalRecordRepository(application)
    private val apiRepository = RhythmRepository()
    private val preferences = application.getSharedPreferences("rhythm_preferences", 0)

    var account: RhythmAccount? by mutableStateOf(authRepository.currentUser()?.toRhythmAccount())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var message: String by mutableStateOf("Demo mode is available without an account.")
        private set
    var usageAccessGranted: Boolean by mutableStateOf(usageRepository.hasUsageAccess())
        private set
    var usageDays: List<UsageDayObservation> by mutableStateOf(usageRepository.read())
        private set
    var usageLoading: Boolean by mutableStateOf(false)
        private set
    var usageMessage: String by mutableStateOf("")
        private set
    var checkInHistory: List<LocalCheckIn> by mutableStateOf(emptyList())
        private set
    var todayCheckIn: LocalCheckIn? by mutableStateOf(null)
        private set
    var focusHistory: List<LocalFocusSession> by mutableStateOf(emptyList())
        private set
    var cloudSyncEnabled: Boolean by mutableStateOf(false)
        private set
    var aiConsent: Boolean by mutableStateOf(false)
        private set
    var syncBusy: Boolean by mutableStateOf(false)
        private set
    var remoteInsights: List<Map<String, Any?>> by mutableStateOf(emptyList())
        private set

    // Firebase ID tokens stay in memory and are not sent to FastAPI until it verifies them.
    private var firebaseIdToken: String? = null
    private var observedUid: String? = account?.uid
    private var currentLocalOwnerId = account?.uid ?: LOCAL_DEMO_OWNER
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        val uid = user?.uid
        if (uid != observedUid) {
            observedUid = uid
            clearUserScopedPreferences()
            firebaseIdToken = null
            cloudSyncEnabled = false
            aiConsent = false
            remoteInsights = emptyList()
        }
        account = user?.toRhythmAccount()
        loadLocalRecords(uid ?: LOCAL_DEMO_OWNER)
        if (user == null) {
            firebaseIdToken = null
        } else {
            viewModelScope.launch {
                runCatching {
                    val token = authRepository.getIdToken(user)
                    val profile = apiRepository.profile(token)
                    token to profile
                }.onSuccess { (token, profile) ->
                    if (observedUid == uid) {
                        firebaseIdToken = token
                        cloudSyncEnabled = profile.cloudSyncEnabled
                        aiConsent = profile.aiConsent
                        remoteInsights = if (profile.cloudSyncEnabled) apiRepository.insights(token) else emptyList()
                    }
                }.onFailure {
                    if (observedUid == uid) message = "Signed in. RHYTHM API is currently unavailable; local records remain on this device."
                }
            }
        }
    }

    init {
        clearUserScopedPreferences()
        loadLocalRecords(account?.uid ?: LOCAL_DEMO_OWNER)
        authRepository.addAuthStateListener(authListener)
    }

    private fun loadLocalRecords(ownerId: String) {
        if (ownerId == currentLocalOwnerId && checkInHistory.isNotEmpty()) return
        checkInHistory = emptyList()
        todayCheckIn = null
        focusHistory = emptyList()
        currentLocalOwnerId = ownerId
        viewModelScope.launch {
            val (checkIns, sessions) = withContext(Dispatchers.IO) {
                localRecords.getCheckIns(ownerId) to localRecords.getFocusSessions(ownerId)
            }
            if (currentLocalOwnerId == ownerId) {
                remoteInsights = emptyList()
                checkInHistory = checkIns
                todayCheckIn = checkIns.firstOrNull { it.localDate == LocalDate.now() }
                focusHistory = sessions
            }
        }
    }

    fun saveCheckIn(mood: Int, stress: Int, energy: Int, fatigue: Int, tags: List<String>, note: String) = viewModelScope.launch {
        val ownerId = currentLocalOwnerId
        val record = withContext(Dispatchers.IO) { localRecords.saveCheckIn(ownerId, mood, stress, energy, fatigue, tags, note) }
        if (currentLocalOwnerId == ownerId) {
            todayCheckIn = record
            checkInHistory = withContext(Dispatchers.IO) { localRecords.getCheckIns(ownerId) }
        }
    }

    fun deleteCheckIn(id: String) = viewModelScope.launch {
        val ownerId = currentLocalOwnerId
        withContext(Dispatchers.IO) { localRecords.deleteCheckIn(ownerId, id) }
        if (currentLocalOwnerId == ownerId) {
            checkInHistory = withContext(Dispatchers.IO) { localRecords.getCheckIns(ownerId) }
            todayCheckIn = checkInHistory.firstOrNull { it.localDate == LocalDate.now() }
        }
    }

    fun saveFocusSession(session: LocalFocusSession) = viewModelScope.launch(Dispatchers.IO) {
        localRecords.saveFocusSession(session)
        val sessions = localRecords.getFocusSessions(session.ownerId)
        withContext(Dispatchers.Main) {
            if (currentLocalOwnerId == session.ownerId) focusHistory = sessions
        }
    }

    fun refreshUsageAccessState() {
        usageAccessGranted = usageRepository.hasUsageAccess()
        if (usageAccessGranted) refreshUsage()
    }

    fun refreshUsage() = viewModelScope.launch {
        usageAccessGranted = usageRepository.hasUsageAccess()
        if (!usageAccessGranted) {
            usageMessage = "Usage Access is off. Enable it in Android Settings to read app-usage totals."
            return@launch
        }
        usageLoading = true
        usageMessage = ""
        try {
            usageDays = withContext(Dispatchers.IO) { usageRepository.refresh() }
            usageMessage = if (usageDays.isEmpty()) "Android has not returned app-usage records for this period yet." else "Updated from Android Usage Stats at ${java.time.LocalTime.now().withSecond(0).withNano(0)}."
        } catch (error: Exception) {
            usageMessage = error.message ?: "Usage data could not be refreshed."
        } finally {
            usageLoading = false
        }
    }

    fun signIn(email: String, password: String, createAccount: Boolean) = viewModelScope.launch {
        busy = true
        message = if (createAccount) "Creating your account…" else "Signing in…"
        try {
            val session = if (createAccount) {
                authRepository.register(email.trim(), password)
            } else {
                authRepository.login(email.trim(), password)
            }
            // Keep the token only in this state holder. It is not logged, persisted, or sent to FastAPI.
            firebaseIdToken = session.idToken
            account = RhythmAccount(session.uid, session.email)
            observedUid = session.uid
            clearUserScopedPreferences()
            message = "Signed in as ${session.email}. Records remain on this device until you enable cloud sync and choose Sync now."
        } catch (error: Exception) {
            message = AuthErrorMapper.userMessage(error)
        } finally {
            busy = false
        }
    }

    fun signOut() {
        busy = true
        try {
            firebaseIdToken = null
            account = null
            cloudSyncEnabled = false
            aiConsent = false
            remoteInsights = emptyList()
            observedUid = null
            loadLocalRecords(LOCAL_DEMO_OWNER)
            clearUserScopedPreferences()
            authRepository.signOut()
            message = "You are signed out. Demo mode is ready."
        } finally {
            busy = false
        }
    }

    fun updateProcessingConsent(sync: Boolean, ai: Boolean) = viewModelScope.launch {
        val user = authRepository.currentUser() ?: run {
            message = "Sign in before enabling cloud or AI processing."
            return@launch
        }
        busy = true
        message = "Updating your privacy preferences…"
        try {
            val token = authRepository.getIdToken(user)
            val profile = apiRepository.updateConsent(token, sync, ai)
            cloudSyncEnabled = profile.cloudSyncEnabled
            aiConsent = profile.aiConsent
            message = if (ai) "Cloud sync and AI processing consent are enabled. AI receives minimized, aggregated metrics only." else if (sync) "Cloud sync consent is enabled. No local records upload until you choose Sync now." else "Cloud sync and AI processing are disabled."
        } catch (error: Exception) {
            message = "Privacy settings were not changed on the server. ${error.message ?: "Check the connection and try again."}"
        } finally {
            busy = false
        }
    }

    fun syncLocalRecords() = viewModelScope.launch {
        val user = authRepository.currentUser()
        if (user == null || !cloudSyncEnabled) {
            message = "Sign in and enable cloud-sync consent before uploading records."
            return@launch
        }
        syncBusy = true
        message = "Syncing your chosen records…"
        try {
            val token = authRepository.getIdToken(user)
            val currentActivity = apiRepository.getActivity(token)
            val currentUsage = apiRepository.getUsage(token)
            val currentCheckIns = apiRepository.getCheckIns(token)
            val currentSessions = apiRepository.getSessions(token)
            usageDays.forEach { day ->
                val dayMinutes = java.time.Duration.between(day.rangeStart, day.rangeEnd).toMinutes()
                val usageMinutes = day.screenTimeMillis / 60_000L
                val request = ApiActivity(day.localDate.toString(), usageMinutes.takeIf { it in 0..dayMinutes }?.toInt())
                val match = currentActivity.firstOrNull { it["local_date"] == day.localDate.toString() && it["source"] == "native" }
                if (match?.get("id") is String) apiRepository.updateActivity(token, match["id"] as String, request)
                else apiRepository.syncActivity(token, request)
                day.apps.groupBy { it.appLabel }.forEach { (label, apps) ->
                    val appMinutes = apps.sumOf { it.foregroundMillis } / 60_000L
                    if (appMinutes > 1440) return@forEach
                    val usage = ApiUsage(day.localDate.toString(), label.take(120), appMinutes.toInt())
                    val existing = currentUsage.firstOrNull { it["local_date"] == day.localDate.toString() && it["app_label"] == label && it["source"] == "native" }
                    if (existing?.get("id") is String) apiRepository.updateUsage(token, existing["id"] as String, usage)
                    else apiRepository.syncUsage(token, usage)
                }
            }
            checkInHistory.forEach { item ->
                val request = ApiCheckIn(item.localDate.toString(), item.mood, item.stress, item.energy, item.fatigue, item.tags, item.note.ifBlank { null })
                val existing = currentCheckIns.firstOrNull { it["local_date"] == item.localDate.toString() }
                if (existing?.get("id") is String) apiRepository.updateCheckIn(token, existing["id"] as String, request)
                else apiRepository.syncCheckIn(token, request)
            }
            focusHistory.forEach { item ->
                val request = ApiFocusSession(item.id, item.sessionType, item.plannedSeconds, item.actualSeconds,
                    item.startedAt.toString(), item.endedAt?.toString(), item.status, item.status == "completed",
                    item.cycle, item.localDate.toString(), item.timeZone)
                val existing = currentSessions.firstOrNull { it["client_id"] == item.id }
                if (existing?.get("id") is String) apiRepository.updateSession(token, existing["id"] as String,
                    mapOf("actual_seconds" to item.actualSeconds, "ended_at" to item.endedAt?.toString(), "status" to item.status, "completed" to (item.status == "completed")))
                else apiRepository.syncSession(token, request)
            }
            remoteInsights = apiRepository.recompute(token)
            message = "The backend acknowledged your local records."
        } catch (error: Exception) {
            message = "Sync did not finish. Some records may have been acknowledged; retry is safe. ${error.message ?: "Check the connection."}"
        } finally {
            syncBusy = false
        }
    }

    fun sendInsightFeedback(id: String, feedback: String) = viewModelScope.launch {
        val user = authRepository.currentUser() ?: return@launch
        try {
            val token = authRepository.getIdToken(user)
            apiRepository.sendFeedback(token, id, feedback)
            message = "Thanks. Your feedback was saved."
        } catch (error: Exception) {
            message = "Feedback was not saved. ${error.message ?: "Check the connection."}"
        }
    }

    fun exportLocalData(): String {
        val root = JSONObject()
        root.put("usage_observations", JSONArray().apply {
            usageDays.forEach { day ->
                put(JSONObject().put("local_date", day.localDate.toString()).put("source", day.source)
                    .put("time_zone", day.timeZone).put("collected_at", day.collectedAt.toString())
                    .put("foreground_millis", day.screenTimeMillis).put("data_quality", day.dataQuality)
                    .put("apps", JSONArray().apply { day.apps.forEach { app -> put(JSONObject().put("app_label", app.appLabel).put("foreground_millis", app.foregroundMillis)) } }))
            }
        })
        root.put("check_ins", JSONArray().apply {
            checkInHistory.forEach { item ->
                put(JSONObject().put("local_date", item.localDate.toString()).put("recorded_at", item.recordedAt.toString())
                    .put("mood", item.mood).put("stress", item.stress).put("energy", item.energy).put("fatigue", item.fatigue)
                    .put("tags", JSONArray(item.tags)).put("note", item.note))
            }
        })
        root.put("focus_sessions", JSONArray().apply {
            focusHistory.forEach { item ->
                put(JSONObject().put("id", item.id).put("type", item.sessionType).put("planned_seconds", item.plannedSeconds)
                    .put("actual_seconds", item.actualSeconds).put("started_at", item.startedAt.toString())
                    .put("ended_at", item.endedAt?.toString()).put("status", item.status).put("local_date", item.localDate.toString()))
            }
        })
        return root.toString(2)
    }

    fun exportCloudData(onExported: (String?) -> Unit) = viewModelScope.launch {
        val user = authRepository.currentUser() ?: run { message = "Sign in to export backend records."; onExported(null); return@launch }
        try {
            val json = JSONObject(apiRepository.export(authRepository.getIdToken(user))).toString(2)
            onExported(json)
        } catch (error: Exception) {
            message = "Backend export failed. ${error.message ?: "Check the connection."}"
            onExported(null)
        }
    }

    fun deleteLocalData() = viewModelScope.launch {
        val ownerId = currentLocalOwnerId
        withContext(Dispatchers.IO) {
            localRecords.deleteOwnerData(ownerId)
            usageRepository.deleteAll()
        }
        checkInHistory = emptyList()
        todayCheckIn = null
        focusHistory = emptyList()
        usageDays = emptyList()
        usageMessage = "Local records were deleted from this device."
    }

    fun deleteAccount() = viewModelScope.launch {
        val deletingOwner = account?.uid
        busy = true
        message = "Deleting your Firebase account…"
        try {
            val token = authRepository.getIdToken(authRepository.currentUser() ?: error("Your Firebase session has ended."))
            apiRepository.deleteBackendAccount(token)
            authRepository.deleteCurrentAccount()
            if (deletingOwner != null) withContext(Dispatchers.IO) { localRecords.deleteOwnerData(deletingOwner) }
            firebaseIdToken = null
            account = null
            observedUid = null
            clearUserScopedPreferences()
            remoteInsights = emptyList()
            cloudSyncEnabled = false
            aiConsent = false
            message = "Your Firebase account and acknowledged backend records were deleted."
        } catch (error: Exception) {
            message = AuthErrorMapper.userMessage(error)
        } finally {
            busy = false
        }
    }

    private fun clearUserScopedPreferences() {
        preferences.edit().remove("sync_enabled").apply()
    }

    override fun onCleared() {
        authRepository.removeAuthStateListener(authListener)
        localRecords.close()
        super.onCleared()
    }

    companion object {
        private const val LOCAL_DEMO_OWNER = "local-demo"
    }
}

private fun FirebaseUser.toRhythmAccount() = RhythmAccount(uid = uid, email = email.orEmpty())
