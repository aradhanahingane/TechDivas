package com.techdivas.stressanalyzer.data

import com.google.gson.annotations.SerializedName
import com.techdivas.stressanalyzer.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class ApiProfile(
    val id: String,
    val email: String?,
    val role: String,
    val goals: List<String> = emptyList(),
    @SerializedName("cloud_sync_enabled") val cloudSyncEnabled: Boolean = false,
    @SerializedName("ai_consent") val aiConsent: Boolean = false
)
data class ProfileConsent(
    @SerializedName("cloud_sync_enabled") val cloudSyncEnabled: Boolean,
    @SerializedName("ai_consent") val aiConsent: Boolean
)
data class ApiActivity(
    @SerializedName("local_date") val localDate: String,
    @SerializedName("screen_minutes") val screenMinutes: Int?,
    val source: String = "native"
)
data class ApiUsage(
    @SerializedName("local_date") val localDate: String,
    @SerializedName("app_label") val appLabel: String,
    @SerializedName("duration_minutes") val durationMinutes: Int,
    val purpose: String = "unknown",
    @SerializedName("purpose_confirmed") val purposeConfirmed: Boolean = false,
    val source: String = "native"
)
data class ApiCheckIn(
    @SerializedName("local_date") val localDate: String,
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val fatigue: Int,
    @SerializedName("context_tags") val contextTags: List<String>,
    val notes: String? = null
)
data class ApiFocusSession(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("session_type") val sessionType: String,
    @SerializedName("planned_seconds") val plannedSeconds: Int,
    @SerializedName("actual_seconds") val actualSeconds: Int,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("ended_at") val endedAt: String?,
    val status: String,
    val completed: Boolean,
    @SerializedName("cycle_number") val cycleNumber: Int,
    @SerializedName("local_date") val localDate: String,
    @SerializedName("timezone_name") val timeZoneName: String
)

interface RhythmApi {
    @GET("api/v1/auth/me") suspend fun me(@Header("Authorization") token: String): ApiProfile
    @GET("api/v1/export") suspend fun export(@Header("Authorization") token: String): Map<String, Any?>
    @DELETE("api/v1/account") suspend fun deleteAccount(@Header("Authorization") token: String)
    @PATCH("api/v1/profile") suspend fun updateConsent(@Header("Authorization") token: String, @Body consent: ProfileConsent): ApiProfile
    @GET("api/v1/activity") suspend fun activity(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/activity") suspend fun saveActivity(@Header("Authorization") token: String, @Body activity: ApiActivity): Map<String, Any>
    @PUT("api/v1/activity/{id}") suspend fun updateActivity(@Header("Authorization") token: String, @Path("id") id: String, @Body activity: ApiActivity): Map<String, Any>
    @GET("api/v1/app-usage") suspend fun appUsage(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/app-usage") suspend fun saveUsage(@Header("Authorization") token: String, @Body usage: ApiUsage): Map<String, Any>
    @PUT("api/v1/app-usage/{id}") suspend fun updateUsage(@Header("Authorization") token: String, @Path("id") id: String, @Body usage: ApiUsage): Map<String, Any>
    @GET("api/v1/check-ins") suspend fun checkIns(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/check-ins") suspend fun saveCheckIn(@Header("Authorization") token: String, @Body checkIn: ApiCheckIn): Map<String, Any>
    @PUT("api/v1/check-ins/{id}") suspend fun updateCheckIn(@Header("Authorization") token: String, @Path("id") id: String, @Body checkIn: ApiCheckIn): Map<String, Any>
    @GET("api/v1/focus-sessions") suspend fun focusSessions(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/focus-sessions") suspend fun saveFocusSession(@Header("Authorization") token: String, @Body session: ApiFocusSession): Map<String, Any>
    @PATCH("api/v1/focus-sessions/{id}") suspend fun updateFocusSession(@Header("Authorization") token: String, @Path("id") id: String, @Body session: Map<String, Any?>): Map<String, Any>
    @GET("api/v1/insights") suspend fun insights(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/analysis/recompute") suspend fun recompute(@Header("Authorization") token: String): List<Map<String, Any?>>
    @GET("api/v1/recommendations") suspend fun recommendations(@Header("Authorization") token: String): List<Map<String, Any?>>
    @POST("api/v1/recommendations/{id}/feedback") suspend fun feedback(@Header("Authorization") token: String, @Path("id") id: String, @Body payload: Map<String, String>): Map<String, Any?>
}

object ApiClient {
    val service: RhythmApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        Retrofit.Builder().baseUrl(BuildConfig.BACKEND_BASE_URL).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(RhythmApi::class.java)
    }
}
