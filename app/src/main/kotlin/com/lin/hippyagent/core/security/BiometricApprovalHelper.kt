package com.lin.hippyagent.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import com.lin.hippyagent.R
import timber.log.Timber
import kotlin.coroutines.resume

class BiometricApprovalHelper(private val activity: FragmentActivity) {

    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun requestApproval(title: String, description: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(description)
                .setNegativeButtonText(activity.getString(R.string.biometric_reject))
                .setConfirmationRequired(true)
                .build()

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!continuation.isActive) return
                        continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        Timber.w("Biometric approval error: $errString")
                        continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                    }
                }
            )

            prompt.authenticate(promptInfo)
        }
    }
}

