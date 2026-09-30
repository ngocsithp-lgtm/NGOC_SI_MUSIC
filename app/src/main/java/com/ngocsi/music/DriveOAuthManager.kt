package com.ngocsi.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OAuth helper for Google Drive private/shared-with-me access.
 *
 * API keys can read public/link-shared resources, but they cannot authorize
 * access to files shared privately with the signed-in Google account.
 */
class DriveOAuthManager(private val context: Context) {
    companion object {
        const val DRIVE_READ_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val REQUEST_CODE = 7401
    }

    fun signInIntent(): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_READ_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    fun lastAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun hasDriveScope(): Boolean {
        val account = lastAccount() ?: return false
        return account.grantedScopes?.any { it.scopeUri == DRIVE_READ_SCOPE } == true
    }

    fun isSignedIn(): Boolean = lastAccount() != null && hasDriveScope()

    fun signingCertificateSha1(): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(signatures.first().toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrElse { "không đọc được SHA-1" }
    }

    fun signInErrorMessage(error: Throwable): String {
        val api = error as? ApiException
        val code = api?.statusCode
        return when (code) {
            12501 -> "Đã hủy đăng nhập Google Drive."
            12500 -> "Google Sign-In thất bại. Kiểm tra kết nối mạng và tài khoản Google."
            10 -> "Google OAuth chưa khớp với ứng dụng com.ngocsi.music. Package com.ngocsi.music; SHA-1 hiện tại: ${signingCertificateSha1()}"
            7 -> "Không kết nối được dịch vụ Google. Kiểm tra mạng và Google Play services."
            8 -> "Google Sign-In gặp lỗi nội bộ. Hãy thử đăng nhập lại."
            else ->
                "Google Sign-In lỗi" + (code?.let { " (mã " + it + ")" } ?: "") +
                    ": " + (error.message ?: "không rõ nguyên nhân")
        }
    }

    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val account = lastAccount() ?: return@withContext null
        val googleAccount = account.account ?: return@withContext null
        runCatching {
            GoogleAuthUtil.getToken(
                context,
                googleAccount,
                "oauth2:$DRIVE_READ_SCOPE"
            )
        }.getOrNull()
    }

    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
        }
    }

    fun clearAccount() {
        val client = GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        )
        client.signOut()
    }
}
