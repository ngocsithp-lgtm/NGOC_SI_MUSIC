package com.ngocsi.music

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern Google Drive authorization helper.
 *
 * AuthorizationClient handles Drive OAuth scopes without the deprecated
 * GoogleSignIn/GoogleAuthUtil APIs.
 */
class DriveOAuthManager(private val context: Context) {
    companion object {
        const val DRIVE_READ_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val PREFS = "ngoc_si_music"
        private const val KEY_AUTHORIZED = "drive_authorized"
        private const val KEY_APPDATA_AUTHORIZED = "drive_appdata_authorized"
    }

    private val requestedScopes = listOf(
        Scope(DRIVE_READ_SCOPE),
        Scope(DRIVE_APPDATA_SCOPE)
    )

    private val authorizationClient by lazy {
        Identity.getAuthorizationClient(context)
    }

    private fun authorizationRequest() =
        AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

    fun authorize(
        activity: Activity,
        onSuccess: (AuthorizationResult) -> Unit,
        onResolution: (PendingIntent) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let(onResolution)
                        ?: onFailure(
                            IllegalStateException(
                                "Google Drive yêu cầu cấp quyền nhưng không trả về PendingIntent."
                            )
                        )
                } else {
                    markAuthorized(result)
                    onSuccess(result)
                }
            }
            .addOnFailureListener { error ->
                onFailure(error)
            }
    }

    fun handleAuthorizationResult(data: Intent?): Result<AuthorizationResult> =
        runCatching {
            authorizationClient.getAuthorizationResultFromIntent(data)
        }.onSuccess { markAuthorized(it) }

    fun isSignedIn(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTHORIZED, false)

    fun hasDriveScope(result: AuthorizationResult? = null): Boolean {
        if (result != null) {
            return DRIVE_READ_SCOPE in result.grantedScopes
        }
        return isSignedIn()
    }

    fun hasAppDataScope(result: AuthorizationResult? = null): Boolean {
        if (result != null) {
            return DRIVE_APPDATA_SCOPE in result.grantedScopes
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_APPDATA_AUTHORIZED, false)
    }

    private fun markAuthorized(result: AuthorizationResult) {
        val grantedRead = DRIVE_READ_SCOPE in result.grantedScopes
        val grantedAppData = DRIVE_APPDATA_SCOPE in result.grantedScopes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTHORIZED, grantedRead)
            .putBoolean(KEY_APPDATA_AUTHORIZED, grantedAppData)
            .apply()
    }

    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val result = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(authorizationRequest())
            )
            if (result.hasResolution()) {
                null
            } else {
                markAuthorized(result)
                result.accessToken
            }
        }.getOrNull()
    }

    suspend fun clearAccessToken(token: String?) = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank()) return@withContext
        runCatching {
            Tasks.await(
                authorizationClient.clearToken(
                    ClearTokenRequest.builder().setToken(token).build()
                )
            )
        }
    }

    fun signInErrorMessage(error: Throwable): String {
        val api = error as? ApiException
        val code = api?.statusCode
        return when (code) {
            12501 -> "Đã hủy cấp quyền Google Drive."
            12500 -> "Google Drive không thể hoàn tất xác thực. Kiểm tra kết nối mạng và Google Play services."
            10 -> "Google OAuth chưa khớp với ứng dụng com.ngocsi.music. Package com.ngocsi.music; SHA-1 hiện tại: " +
                signingCertificateSha1()
            7 -> "Không kết nối được dịch vụ Google. Kiểm tra mạng và Google Play services."
            8 -> "Google Drive gặp lỗi nội bộ. Hãy thử lại."
            else ->
                "Google Drive lỗi" + (code?.let { " (mã $it)" } ?: "") +
                    ": " + (error.message ?: "không rõ nguyên nhân")
        }
    }

    fun signingCertificateSha1(): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures ?: emptyArray()
            }
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(signatures.first().toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrElse { "không đọc được SHA-1" }
    }
}
