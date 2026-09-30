package com.ngocsi.music

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    fun isSignedIn(): Boolean = lastAccount() != null

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
