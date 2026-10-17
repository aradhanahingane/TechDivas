package com.techdivas.stressanalyzer.data

class RhythmRepository(private val api: RhythmApi = ApiClient.service) {
    suspend fun profile(token: String) = api.me("Bearer $token")
    suspend fun export(token: String) = api.export("Bearer $token")
    suspend fun deleteBackendAccount(token: String) = api.deleteAccount("Bearer $token")
    suspend fun updateConsent(token: String, cloudSync: Boolean, ai: Boolean) =
        api.updateConsent("Bearer $token", ProfileConsent(cloudSync, ai))
    suspend fun syncActivity(token: String, activity: ApiActivity) = api.saveActivity("Bearer $token", activity)
    suspend fun getActivity(token: String) = api.activity("Bearer $token")
    suspend fun updateActivity(token: String, id: String, activity: ApiActivity) = api.updateActivity("Bearer $token", id, activity)
    suspend fun syncUsage(token: String, usage: ApiUsage) = api.saveUsage("Bearer $token", usage)
    suspend fun getUsage(token: String) = api.appUsage("Bearer $token")
    suspend fun updateUsage(token: String, id: String, usage: ApiUsage) = api.updateUsage("Bearer $token", id, usage)
    suspend fun syncCheckIn(token: String, checkIn: ApiCheckIn) = api.saveCheckIn("Bearer $token", checkIn)
    suspend fun getCheckIns(token: String) = api.checkIns("Bearer $token")
    suspend fun updateCheckIn(token: String, id: String, checkIn: ApiCheckIn) = api.updateCheckIn("Bearer $token", id, checkIn)
    suspend fun syncSession(token: String, session: ApiFocusSession) = api.saveFocusSession("Bearer $token", session)
    suspend fun getSessions(token: String) = api.focusSessions("Bearer $token")
    suspend fun updateSession(token: String, id: String, patch: Map<String, Any?>) = api.updateFocusSession("Bearer $token", id, patch)
    suspend fun recompute(token: String) = api.recompute("Bearer $token")
    suspend fun insights(token: String) = api.insights("Bearer $token")
    suspend fun recommendations(token: String) = api.recommendations("Bearer $token")
    suspend fun sendFeedback(token: String, id: String, feedback: String) = api.feedback("Bearer $token", id, mapOf("feedback" to feedback))
}
