package com.x8bit.bitwarden.ui.auth.feature.vaultunlock

// KIKARON: copied into place by kikaron/brand.py - edit kikaron/android/, not this copy.

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.x8bit.bitwarden.R
import javax.crypto.Cipher

/*
 * "The Multipass app needs a Kikaron login, which you should only have to do once. After
 * that, a fingerprint should be enough to unlock the vault." (Daniel, 2026-11-10)
 *
 * When the vault is unlocked without a fingerprint while fingerprint unlock is off (or no
 * longer valid: a fingerprint was added or removed), the unlock asks for ONE fingerprint
 * first - "Ontgrendel voortaan met uw vingerafdruk" - and the fingerprint is the consent:
 * the vault opens and the fingerprint key is written right after it
 * (SettingsRepository.setupBiometricsKey, as the Settings toggle does). It is asked on the
 * unlock screen itself, before the vault opens, so it works the same when the unlock came
 * from a passkey (create or sign in) or from autofill: the user lands back in that flow.
 * "Niet nu" stops asking until the app is started again.
 */

/** Accounts that said "Niet nu" since the app started (asked again after a restart). */
internal object KikaronFingerprintOffer {
    private val declined = mutableSetOf<String>()

    fun isDeclined(userId: String): Boolean = synchronized(declined) { userId in declined }

    fun decline(userId: String) {
        synchronized(declined) { declined += userId }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Shows the system fingerprint prompt that switches fingerprint unlock on. [onResult] is
 * called exactly once: with the authenticated [cipher] after a fingerprint, or with null
 * (declined = true for "Niet nu", false for an error or lockout - the unlock then goes on
 * without it).
 */
@Suppress("TooGenericExceptionCaught")
internal fun promptKikaronFingerprint(
    context: Context,
    cipher: Cipher,
    onResult: (cipher: Cipher?, declined: Boolean) -> Unit,
) {
    val activity = context.findFragmentActivity() ?: run {
        onResult(null, false)
        return
    }
    var answered = false
    val answer: (Cipher?, Boolean) -> Unit = { c, declined ->
        if (!answered) {
            answered = true
            onResult(c, declined)
        }
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                answer(result.cryptoObject?.cipher, false)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                answer(
                    null,
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED,
                )

            override fun onAuthenticationFailed() {
                // a finger that did not match: the prompt stays and lets the user try again
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.kikaron_fingerprint_title))
        .setDescription(activity.getString(R.string.kikaron_fingerprint_description))
        .setNegativeButtonText(activity.getString(R.string.kikaron_fingerprint_not_now))
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    try {
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    } catch (e: RuntimeException) {
        timber.log.Timber.w(e, "Kikaron: fingerprint prompt failed")
        answer(null, false)
    }
}
