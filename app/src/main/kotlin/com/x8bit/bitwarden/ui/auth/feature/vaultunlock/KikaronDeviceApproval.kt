package com.x8bit.bitwarden.ui.auth.feature.vaultunlock

// KIKARON: copied into place by kikaron/brand.py - edit kikaron/android/, not this copy.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenTextButton
import com.bitwarden.ui.platform.components.indicator.BitwardenCircularProgressIndicator
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequestType
import com.x8bit.bitwarden.data.auth.manager.model.CreateAuthRequestResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.VaultUnlockType
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.model.BiometricsKeyResult
import com.x8bit.bitwarden.data.platform.repository.model.VaultTimeoutAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject

/*
 * "After first-time install of the Multipass app it should ask me to log in with my Kikaron
 * account as normal, but after that it should not ask for a password anymore to lock or unlock
 * my vault. That should be fingerprint only." (Daniel, 2026-11-10)
 *
 * The locked screen has three ways in (kikaron-app-android docs/multipass-one-login.md, (4)):
 *  - FINGERPRINT: a working fingerprint key - one button, the prompt also comes by itself;
 *  - DEVICE_APPROVAL: no working fingerprint key yet (a fresh install after the Kikaron
 *    sign-in, or a finger added since): "Log in with device" from a device whose vault is
 *    open - the Kikaron app's Multipass or any Bitwarden client. Vaultwarden 1.37.3 relays it
 *    (POST /auth-requests, PUT /auth-requests/{id}, GET /auth-requests/{id}); the approver
 *    wraps the user key in this phone's RSA key, the server only sees ciphertext. When the
 *    approval comes, ONE fingerprint first ("Ontgrendel voortaan met uw vingerafdruk"), then
 *    the vault opens and the fingerprint key is written, so this happens once;
 *  - PASSWORD: the small link "Toch Multipass-wachtwoord of herstelcode gebruiken" (the
 *    recovery code is the vault's master password, typed loosely), or a phone without a
 *    strong biometric.
 */

internal enum class KikaronUnlockMode { FINGERPRINT, DEVICE_APPROVAL, PASSWORD }

internal fun kikaronUnlockMode(
    state: VaultUnlockState,
    isBiometricsSupported: Boolean,
    usePassword: Boolean,
): KikaronUnlockMode = when {
    usePassword || !isBiometricsSupported || state.hideInput ||
        state.vaultUnlockType != VaultUnlockType.MASTER_PASSWORD -> KikaronUnlockMode.PASSWORD

    state.showBiometricLogin -> KikaronUnlockMode.FINGERPRINT
    else -> KikaronUnlockMode.DEVICE_APPROVAL
}

private const val RECOVERY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val RECOVERY_GROUPS = 8
private const val RECOVERY_GROUP = 5

/**
 * A recovery code as typed (any case, spaces, missing or extra dashes, O for 0, I/L for 1) in
 * its one form "XXXXX-XXXXX-…" - the vault's master password - or null when it is not one
 * (then it is a typed Multipass password). As kikaron-multipass-core's canonical_recovery_code.
 */
internal fun kikaronCanonicalRecoveryCode(typed: String): String? {
    val out = StringBuilder()
    for (raw in typed) {
        val c = when (val u = raw.uppercaseChar()) {
            '-', ' ', '\t', '\n' -> continue
            'O' -> '0'
            'I', 'L' -> '1'
            else -> u
        }
        if (c !in RECOVERY_ALPHABET) return null
        out.append(c)
    }
    if (out.length != RECOVERY_GROUPS * RECOVERY_GROUP) return null
    return out.chunked(RECOVERY_GROUP).joinToString("-")
}

/** Writes the fingerprint key after an unlock; a timeout that logs out becomes a lock. */
internal suspend fun kikaronSwitchOnFingerprint(
    settingsRepository: SettingsRepository,
    cipher: Cipher,
) {
    when (val result = settingsRepository.setupBiometricsKey(cipher = cipher)) {
        is BiometricsKeyResult.Error -> {
            Timber.w(result.error, "Kikaron: fingerprint unlock not switched on")
        }

        BiometricsKeyResult.Success -> {
            if (settingsRepository.vaultTimeoutAction == VaultTimeoutAction.LOGOUT) {
                settingsRepository.vaultTimeoutAction = VaultTimeoutAction.LOCK
            }
        }
    }
}

// ---- device approval ----------------------------------------------------------------------

internal enum class KikaronApprovalStatus {
    SENDING, WAITING, FINGERPRINT, OPENING, DECLINED, EXPIRED, ERROR, FAILED,
}

internal data class KikaronApprovalState(
    val status: KikaronApprovalStatus = KikaronApprovalStatus.SENDING,
    val phrase: String? = null,
)

/**
 * The requester side of "Log in with device" after the Kikaron sign-in (upstream's
 * LoginWithDeviceType.SSO_OTHER_DEVICE: the account is signed in, only the key is missing),
 * on the unlock screen itself so passkeys and autofill land back in their flow.
 */
@HiltViewModel
class KikaronDeviceApprovalViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KikaronApprovalState())
    internal val state: StateFlow<KikaronApprovalState> = mutableState.asStateFlow()

    private val fingerprintChannel = Channel<Cipher>(Channel.CONFLATED)

    /** A cipher to authenticate with one fingerprint before the vault opens. */
    internal val fingerprintRequests: Flow<Cipher> = fingerprintChannel.receiveAsFlow()

    private var job: Job? = null
    private var email: String = ""
    private var isBiometricsSupported = false
    private var approval: Pair<String, String>? = null

    internal fun start(email: String, isBiometricsSupported: Boolean) {
        this.isBiometricsSupported = isBiometricsSupported
        if (approval != null) return
        if (job?.isActive == true && this.email == email) return
        this.email = email
        ask()
    }

    internal fun retry() {
        approval = null
        ask()
    }

    private fun ask() {
        job?.cancel()
        mutableState.value = KikaronApprovalState(status = KikaronApprovalStatus.SENDING)
        job = authRepository
            .createAuthRequestWithUpdates(
                email = email,
                authRequestType = AuthRequestType.SSO_OTHER_DEVICE,
            )
            .onEach(::receive)
            .launchIn(viewModelScope)
    }

    private fun receive(result: CreateAuthRequestResult) {
        when (result) {
            is CreateAuthRequestResult.Update -> mutableState.update {
                it.copy(
                    status = KikaronApprovalStatus.WAITING,
                    phrase = result.authRequest.fingerprint,
                )
            }

            is CreateAuthRequestResult.Success -> {
                val key = result.authRequest.key ?: run {
                    mutableState.update { it.copy(status = KikaronApprovalStatus.FAILED) }
                    return
                }
                approval = result.privateKey to key
                val cipher = fingerprintCipherOrNull()
                if (cipher != null) {
                    mutableState.update { it.copy(status = KikaronApprovalStatus.FINGERPRINT) }
                    fingerprintChannel.trySend(cipher)
                } else {
                    open(cipher = null)
                }
            }

            CreateAuthRequestResult.Declined -> mutableState.update {
                it.copy(status = KikaronApprovalStatus.DECLINED)
            }

            CreateAuthRequestResult.Expired -> mutableState.update {
                it.copy(status = KikaronApprovalStatus.EXPIRED)
            }

            is CreateAuthRequestResult.Error -> {
                Timber.w(result.error, "Kikaron: device approval request failed")
                mutableState.update { it.copy(status = KikaronApprovalStatus.ERROR) }
            }
        }
    }

    private fun fingerprintCipherOrNull(): Cipher? {
        if (!isBiometricsSupported) return null
        val account = authRepository.userStateFlow.value?.activeAccount ?: return null
        if (KikaronFingerprintOffer.isDeclined(account.userId)) return null
        // a key that no longer works goes first, with its IV: the new cipher must encrypt
        if (account.isBiometricsEnabled) authRepository.clearBiometrics(userId = account.userId)
        return authRepository.createCipherOrNull(userId = account.userId)
    }

    /** The answer to the fingerprint prompt; the vault opens either way. */
    internal fun onFingerprint(cipher: Cipher?, declined: Boolean) {
        if (declined) authRepository.activeUserId?.let { KikaronFingerprintOffer.decline(it) }
        open(cipher = cipher)
    }

    private fun open(cipher: Cipher?) {
        val (privateKey, key) = approval ?: return
        mutableState.update { it.copy(status = KikaronApprovalStatus.OPENING) }
        viewModelScope.launch {
            // NonCancellable: the open vault navigates away and clears this view model, and
            // the fingerprint key must still be written after it
            val isOpen = withContext(NonCancellable) {
                val result = authRepository.completeTdeLogin(
                    requestPrivateKey = privateKey,
                    asymmetricalKey = key,
                )
                if (result is LoginResult.Success && cipher != null) {
                    kikaronSwitchOnFingerprint(settingsRepository, cipher)
                }
                result is LoginResult.Success
            }
            if (!isOpen) {
                approval = null
                mutableState.update { it.copy(status = KikaronApprovalStatus.FAILED) }
            }
        }
    }
}

/** "Goedkeuren op een ander apparaat", with the phrase to compare. */
@Composable
internal fun KikaronDeviceApprovalContent(
    email: String,
    isBiometricsSupported: Boolean,
    onUsePassword: () -> Unit,
    viewModel: KikaronDeviceApprovalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(email) { viewModel.start(email, isBiometricsSupported) }
    LaunchedEffect(viewModel) {
        viewModel.fingerprintRequests.collect { cipher ->
            promptKikaronFingerprint(context = context, cipher = cipher) { c, declined ->
                viewModel.onFingerprint(cipher = c, declined = declined)
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .standardHorizontalMargin()
            .fillMaxWidth(),
    ) {
        Text(
            text = stringResource(id = R.string.kikaron_approval_title),
            style = BitwardenTheme.typography.titleMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.kikaron_approval_message),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        state.phrase?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = it,
                style = BitwardenTheme.typography.sensitiveInfoSmall,
                color = BitwardenTheme.colorScheme.text.codePink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        val message = when (state.status) {
            KikaronApprovalStatus.SENDING,
            KikaronApprovalStatus.WAITING,
                -> R.string.kikaron_approval_waiting

            KikaronApprovalStatus.FINGERPRINT,
            KikaronApprovalStatus.OPENING,
                -> R.string.kikaron_approval_opening

            KikaronApprovalStatus.DECLINED -> R.string.kikaron_approval_declined
            KikaronApprovalStatus.EXPIRED -> R.string.kikaron_approval_expired
            KikaronApprovalStatus.ERROR -> R.string.kikaron_approval_error
            KikaronApprovalStatus.FAILED -> R.string.kikaron_approval_failed
        }
        val isBusy = state.status in setOf(
            KikaronApprovalStatus.SENDING,
            KikaronApprovalStatus.WAITING,
            KikaronApprovalStatus.FINGERPRINT,
            KikaronApprovalStatus.OPENING,
        )
        if (isBusy) {
            BitwardenCircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = stringResource(id = message),
            style = BitwardenTheme.typography.bodyMedium,
            color = if (isBusy) {
                BitwardenTheme.colorScheme.text.secondary
            } else {
                BitwardenTheme.colorScheme.status.error
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isBusy) {
            Spacer(modifier = Modifier.height(16.dp))
            BitwardenFilledButton(
                label = stringResource(id = R.string.kikaron_approval_retry),
                onClick = viewModel::retry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        KikaronUsePasswordLink(onClick = onUsePassword)
    }
}

/** A working fingerprint key: one button (the prompt also comes by itself). */
@Composable
internal fun KikaronFingerprintContent(
    onUnlock: () -> Unit,
    onUsePassword: () -> Unit,
) {
    Column(
        modifier = Modifier
            .standardHorizontalMargin()
            .fillMaxWidth(),
    ) {
        Text(
            text = stringResource(id = R.string.kikaron_unlock_fingerprint_message),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        BitwardenFilledButton(
            label = stringResource(id = R.string.kikaron_unlock_fingerprint_button),
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        KikaronUsePasswordLink(onClick = onUsePassword)
    }
}

@Composable
private fun KikaronUsePasswordLink(onClick: () -> Unit) {
    BitwardenTextButton(
        label = stringResource(id = R.string.kikaron_use_password),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}
