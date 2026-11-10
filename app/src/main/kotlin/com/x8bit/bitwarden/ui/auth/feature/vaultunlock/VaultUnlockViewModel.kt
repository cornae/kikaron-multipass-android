package com.x8bit.bitwarden.ui.auth.feature.vaultunlock

import android.os.Parcelable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.base.DeferredBackgroundEvent
import com.bitwarden.ui.platform.base.util.hexToColor
import com.bitwarden.ui.platform.components.account.model.AccountSummary
import com.bitwarden.ui.platform.components.account.util.initials
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.auth.repository.model.VaultUnlockType
import com.x8bit.bitwarden.data.credentials.manager.BitwardenCredentialManager
import com.x8bit.bitwarden.data.credentials.model.CreateCredentialRequest
import com.x8bit.bitwarden.data.credentials.model.Fido2CredentialAssertionRequest
import com.x8bit.bitwarden.data.credentials.model.GetCredentialsRequest
import com.x8bit.bitwarden.data.platform.manager.AppResumeManager
import com.x8bit.bitwarden.data.platform.manager.SpecialCircumstanceManager
import com.x8bit.bitwarden.data.platform.manager.model.SpecialCircumstance
import com.x8bit.bitwarden.data.platform.manager.util.toCreateCredentialRequestOrNull
import com.x8bit.bitwarden.data.platform.manager.util.toFido2AssertionRequestOrNull
import com.x8bit.bitwarden.data.platform.manager.util.toGetCredentialsRequestOrNull
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.x8bit.bitwarden.ui.auth.feature.vaultunlock.model.UnlockType
import com.x8bit.bitwarden.ui.auth.feature.vaultunlock.util.emptyInputDialogMessage
import com.x8bit.bitwarden.ui.auth.feature.vaultunlock.util.unlockScreenErrorMessage
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toAccountSummaries
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toAccountSummary
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toActiveAccountSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import javax.crypto.Cipher
import javax.inject.Inject

private const val KEY_STATE = "state"

/**
 * Manages application state for the initial vault unlock screen.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class VaultUnlockViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultRepo: VaultRepository,
    private val specialCircumstanceManager: SpecialCircumstanceManager,
    private val bitwardenCredentialManager: BitwardenCredentialManager,
    private val appResumeManager: AppResumeManager,
    private val vaultLockManager: VaultLockManager,
    // KIKARON: to switch fingerprint unlock on right after an unlock
    private val settingsRepository: com.x8bit.bitwarden.data.platform.repository.SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<VaultUnlockState, VaultUnlockEvent, VaultUnlockAction>(
    // We load the state from the savedStateHandle for testing purposes.
    initialState = savedStateHandle[KEY_STATE] ?: run {
        val userState = authRepository.userStateFlow.value
        val activeAccount = userState?.activeAccount ?: run {
            // We use this empty account to avoid a crash that can occur during a race condition.
            // The state-based navigation brought us here but the UserState has now been set to
            // null, we just need to wait here a short while longer and state-based navigation will
            // get us out of here.
            UserState.EMPTY_ACCOUNT
        }
        val accountSummaries = userState?.toAccountSummaries().orEmpty().toImmutableList()
        val activeAccountSummary = activeAccount.toAccountSummary(isActive = true)
        val vaultUnlockType = activeAccount.vaultUnlockType
        val hasNoMasterPassword = !activeAccount.hasMasterPassword
        if (!activeAccount.hasManualUnlockMechanism) {
            // There is no valid way to unlock this app.
            authRepository.logout(
                reason = LogoutReason.InvalidState(source = "VaultUnlockViewModel"),
            )
        }

        val specialCircumstance = specialCircumstanceManager.specialCircumstance
        val showAccountMenu =
            savedStateHandle.toVaultUnlockArgs().unlockType == UnlockType.STANDARD &&
                (specialCircumstance !is SpecialCircumstance.ProviderGetCredentials &&
                    specialCircumstance !is SpecialCircumstance.Fido2Assertion)
        VaultUnlockState(
            accountSummaries = accountSummaries,
            avatarColorString = activeAccountSummary.avatarColorHex,
            hideInput = hasNoMasterPassword && vaultUnlockType == VaultUnlockType.MASTER_PASSWORD,
            initials = activeAccountSummary.initials,
            email = activeAccountSummary.email,
            dialog = null,
            environmentUrl = activeAccount.environment.label,
            input = "",
            isBiometricEnabled = activeAccount.isBiometricsEnabled,
            isBiometricsValid = authRepository.isBiometricIntegrityValid(activeAccount.userId),
            showAccountMenu = showAccountMenu,
            showBiometricInvalidatedMessage = false,
            vaultUnlockType = vaultUnlockType,
            userId = activeAccount.userId,
            getCredentialsRequest = specialCircumstance?.toGetCredentialsRequestOrNull(),
            fido2CredentialAssertionRequest = specialCircumstance?.toFido2AssertionRequestOrNull(),
            createCredentialRequest = specialCircumstance?.toCreateCredentialRequestOrNull(),
            hasMasterPassword = activeAccount.hasMasterPassword,
            isFromLockFlow = vaultLockManager.isFromLockFlow,
        )
    },
) {
    init {
        authRepository
            .userStateFlow
            .onEach {
                sendAction(VaultUnlockAction.Internal.UserStateUpdateReceive(userState = it))
            }
            .launchIn(viewModelScope)

        promptForBiometricsIfAvailable()

        // only when navigating from vault to lock we should not display biometrics
        // subsequent views of the lock screen should display biometrics if available
        vaultLockManager.isFromLockFlow = false
        mutableStateFlow.update { it.copy(isFromLockFlow = false) }
    }

    override fun onCleared() {
        // TODO: This is required because there is an OS-level leak occurring that leaves the
        //   ViewModel in memory. We should remove this when that leak is fixed. (BIT-2287)
        mutableStateFlow.update { it.copy(input = "") }
        super.onCleared()
    }

    override fun handleAction(action: VaultUnlockAction) {
        when (action) {
            VaultUnlockAction.AddAccountClick -> handleAddAccountClick()
            VaultUnlockAction.DismissDialog -> handleDismissDialog()
            VaultUnlockAction.ConfirmLogoutClick -> handleConfirmLogoutClick()
            is VaultUnlockAction.InputChanged -> handleInputChanged(action)
            is VaultUnlockAction.LockAccountClick -> handleLockAccountClick(action)
            is VaultUnlockAction.LogoutAccountClick -> handleLogoutAccountClick(action)
            is VaultUnlockAction.SwitchAccountClick -> handleSwitchAccountClick(action)
            VaultUnlockAction.BiometricsLockOut -> handleBiometricsLockOut()
            VaultUnlockAction.BiometricsUnlockClick -> handleBiometricsUnlockClick()
            is VaultUnlockAction.BiometricsUnlockSuccess -> handleBiometricsUnlockSuccess(action)
            VaultUnlockAction.UnlockClick -> handleUnlockClick()
            // KIKARON: one fingerprint switches fingerprint unlock on
            is VaultUnlockAction.KikaronFingerprintSupport -> {
                kikaronFingerprintSupported = action.isSupported
            }

            is VaultUnlockAction.KikaronFingerprintResult -> {
                handleKikaronFingerprintResult(action)
            }
            is VaultUnlockAction.Internal -> handleInternalAction(action)
            VaultUnlockAction.BiometricsNoLongerSupported -> {
                handleBiometricsNoLongerSupported()
            }

            VaultUnlockAction.DismissBiometricsNoLongerSupportedDialog -> {
                handleDismissBiometricsNoLongerSupportedDialog()
            }
        }
    }

    private fun handleBiometricsNoLongerSupported() {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultUnlockState.VaultUnlockDialog.BiometricsNoLongerSupported,
            )
        }
    }

    private fun handleDismissBiometricsNoLongerSupportedDialog() {
        // KIKARON: never log out - the vault stays locked, opened by password (or PIN)
        mutableStateFlow.update {
            it.copy(dialog = null, isBiometricsValid = false)
        }
    }

    private fun handleAddAccountClick() {
        authRepository.hasPendingAccountAddition = true
    }

    private fun handleDismissDialog() {
        mutableStateFlow.update { it.copy(dialog = null) }
        when {
            state.getCredentialsRequest != null -> {
                sendEvent(
                    VaultUnlockEvent.GetCredentialsError(
                        BitwardenString
                            .credential_operation_failed_because_user_could_not_be_verified
                            .asText(),
                    ),
                )
            }

            state.fido2CredentialAssertionRequest != null -> {
                sendEvent(
                    VaultUnlockEvent.Fido2CredentialAssertionError(
                        BitwardenString.passkey_operation_failed_because_user_could_not_be_verified
                            .asText(),
                    ),
                )
            }

            else -> Unit
        }
    }

    private fun handleConfirmLogoutClick() {
        authRepository.logout(reason = LogoutReason.Click(source = "VaultUnlockViewModel"))
    }

    private fun handleInputChanged(action: VaultUnlockAction.InputChanged) {
        mutableStateFlow.update {
            it.copy(input = action.input)
        }
    }

    private fun handleLockAccountClick(action: VaultUnlockAction.LockAccountClick) {
        vaultRepo.lockVault(userId = action.accountSummary.userId, isUserInitiated = true)
    }

    private fun handleLogoutAccountClick(action: VaultUnlockAction.LogoutAccountClick) {
        authRepository.logout(
            userId = action.accountSummary.userId,
            reason = LogoutReason.Click(source = "VaultUnlockViewModel"),
        )
    }

    private fun handleSwitchAccountClick(action: VaultUnlockAction.SwitchAccountClick) {
        authRepository.switchAccount(userId = action.accountSummary.userId)
    }

    private fun handleBiometricsLockOut() {
        // KIKARON: never log out - say so and leave the vault locked (password or a later try)
        mutableStateFlow.update {
            it.copy(
                dialog = VaultUnlockState.VaultUnlockDialog.Error(
                    title = BitwardenString.biometrics_failed.asText(),
                    message = BitwardenString.too_many_failed_biometric_attempts.asText(),
                ),
            )
        }
    }

    private fun handleBiometricsUnlockClick() {
        val cipher = authRepository.getOrCreateCipher(state.userId)
        if (cipher != null) {
            sendEvent(
                event = VaultUnlockEvent.PromptForBiometrics(
                    cipher = cipher,
                ),
            )
        } else {
            mutableStateFlow.update {
                it.copy(
                    isBiometricsValid = false,
                    showBiometricInvalidatedMessage = !authRepository
                        .isAccountBiometricIntegrityValid(state.userId),
                )
            }
        }
    }

    private fun handleBiometricsUnlockSuccess(action: VaultUnlockAction.BiometricsUnlockSuccess) {
        val activeUserId = authRepository.activeUserId ?: return
        mutableStateFlow.update { it.copy(dialog = VaultUnlockState.VaultUnlockDialog.Loading) }
        viewModelScope.launch {
            val vaultUnlockResult = vaultRepo.unlockVaultWithBiometrics(cipher = action.cipher)
            sendAction(
                VaultUnlockAction.Internal.ReceiveVaultUnlockResult(
                    userId = activeUserId,
                    vaultUnlockResult = vaultUnlockResult,
                    isBiometricLogin = true,
                ),
            )
        }
    }

    private fun handleUnlockClick() {
        val activeUserId = authRepository.activeUserId ?: return

        if (state.input.isEmpty()) {
            mutableStateFlow.update {
                it.copy(
                    dialog = VaultUnlockState.VaultUnlockDialog.Error(
                        title = BitwardenString.an_error_has_occurred.asText(),
                        message = it.vaultUnlockType.emptyInputDialogMessage,
                    ),
                )
            }
            return
        }

        // KIKARON: while fingerprint unlock is off, one fingerprint first switches it on
        if (kikaronOfferFingerprint(userId = activeUserId)) return
        kikaronUnlock(
            activeUserId = activeUserId,
            input = state.input,
            fingerprintCipher = kikaronFingerprintCipher
                ?.takeIf { kikaronFingerprintUserId == activeUserId },
        )
    }

    // ---- KIKARON: fingerprint unlock after the one Kikaron sign-in ------------------------
    // (kikaron/unlock.py, kikaron/android/KikaronFingerprintUnlock.kt)

    /** Whether the phone has a strong biometric with a finger enrolled (from the screen). */
    private var kikaronFingerprintSupported = false

    /** The password typed before the fingerprint prompt, for the unlock that follows it. */
    private var kikaronPendingInput: String? = null

    /**
     * An authenticated cipher from a fingerprint, kept when the password turned out wrong so
     * the next try needs no second fingerprint.
     */
    private var kikaronFingerprintCipher: Cipher? = null
    private var kikaronFingerprintUserId: String? = null

    /**
     * Asks for one fingerprint before a Multipass-password unlock when fingerprint unlock is
     * off, or on but no longer valid. Returns whether it asked: the unlock then follows in
     * [handleKikaronFingerprintResult].
     */
    private fun kikaronOfferFingerprint(userId: String): Boolean {
        if (!kikaronFingerprintSupported) return false
        if (state.vaultUnlockType != VaultUnlockType.MASTER_PASSWORD) return false
        if (kikaronFingerprintCipher != null && kikaronFingerprintUserId == userId) return false
        if (KikaronFingerprintOffer.isDeclined(userId)) return false
        if (state.isBiometricEnabled && authRepository.isBiometricIntegrityValid(userId)) {
            return false
        }
        val input = state.input
        if (state.isBiometricEnabled) {
            // a key that no longer works goes first, with its IV: the new cipher must encrypt
            authRepository.clearBiometrics(userId = userId)
        }
        val cipher = authRepository.createCipherOrNull(userId = userId) ?: return false
        kikaronPendingInput = input
        sendEvent(VaultUnlockEvent.KikaronPromptFingerprint(cipher = cipher))
        return true
    }

    private fun handleKikaronFingerprintResult(
        action: VaultUnlockAction.KikaronFingerprintResult,
    ) {
        val input = kikaronPendingInput ?: return
        kikaronPendingInput = null
        val activeUserId = authRepository.activeUserId ?: return
        if (action.isDeclined) KikaronFingerprintOffer.decline(userId = activeUserId)
        kikaronFingerprintCipher = action.cipher
        kikaronFingerprintUserId = activeUserId.takeIf { action.cipher != null }
        kikaronUnlock(
            activeUserId = activeUserId,
            input = input,
            fingerprintCipher = action.cipher,
        )
    }

    private fun kikaronUnlock(activeUserId: String, input: String, fingerprintCipher: Cipher?) {
        mutableStateFlow.update { it.copy(dialog = VaultUnlockState.VaultUnlockDialog.Loading) }
        val vaultUnlockType = state.vaultUnlockType
        viewModelScope.launch {
            val unlock: suspend () -> VaultUnlockResult = {
                val result = when (vaultUnlockType) {
                    VaultUnlockType.MASTER_PASSWORD -> {
                        vaultRepo.unlockVaultWithMasterPassword(input)
                    }

                    VaultUnlockType.PIN -> {
                        vaultRepo.unlockVaultWithPin(input)
                    }
                }
                if (fingerprintCipher != null && result is VaultUnlockResult.Success) {
                    kikaronSwitchOnFingerprint(userId = activeUserId, cipher = fingerprintCipher)
                }
                result
            }
            // With a fingerprint to set up, NonCancellable: the open vault navigates away from
            // this screen and clears this view model, and the key must still be written.
            val vaultUnlockResult = if (fingerprintCipher != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { unlock() }
            } else {
                unlock()
            }
            sendAction(
                VaultUnlockAction.Internal.ReceiveVaultUnlockResult(
                    userId = activeUserId,
                    vaultUnlockResult = vaultUnlockResult,
                    isBiometricLogin = false,
                ),
            )
        }
    }

    private suspend fun kikaronSwitchOnFingerprint(userId: String, cipher: Cipher) {
        kikaronFingerprintCipher = null
        kikaronFingerprintUserId = null
        if (authRepository.activeUserId != userId) return
        when (val result = settingsRepository.setupBiometricsKey(cipher = cipher)) {
            is com.x8bit.bitwarden.data.platform.repository.model.BiometricsKeyResult.Error -> {
                timber.log.Timber.w(result.error, "Kikaron: fingerprint unlock not switched on")
            }

            com.x8bit.bitwarden.data.platform.repository.model.BiometricsKeyResult.Success -> {
                // a fingerprint unlock is no use when the vault logs out on its timeout
                if (settingsRepository.vaultTimeoutAction ==
                    com.x8bit.bitwarden.data.platform.repository.model.VaultTimeoutAction.LOGOUT
                ) {
                    settingsRepository.vaultTimeoutAction =
                        com.x8bit.bitwarden.data.platform.repository.model.VaultTimeoutAction.LOCK
                }
            }
        }
    }

    private fun handleInternalAction(action: VaultUnlockAction.Internal) {
        when (action) {
            is VaultUnlockAction.Internal.ReceiveVaultUnlockResult -> {
                handleReceiveVaultUnlockResult(action)
            }

            is VaultUnlockAction.Internal.UserStateUpdateReceive -> {
                handleUserStateUpdateReceive(action)
            }
        }
    }

    private fun handleReceiveVaultUnlockResult(
        action: VaultUnlockAction.Internal.ReceiveVaultUnlockResult,
    ) {
        if (action.userId != authRepository.activeUserId) {
            // The active user has automatically switched before receiving the event. Ignore any
            // results and just clear any loading dialog.
            mutableStateFlow.update { it.copy(dialog = null) }
            return
        }

        // Mark the user verified for this session if the unlock result is Success.
        bitwardenCredentialManager.isUserVerified =
            action.vaultUnlockResult is VaultUnlockResult.Success &&
                state.isUnlockingForCredentialManagerRequest

        when (val result = action.vaultUnlockResult) {
            is VaultUnlockResult.AuthenticationError -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = VaultUnlockState.VaultUnlockDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = if (action.isBiometricLogin) {
                                BitwardenString.generic_error_message.asText()
                            } else {
                                state.vaultUnlockType.unlockScreenErrorMessage
                            },
                            throwable = result.error?.takeIf { _ -> action.isBiometricLogin },
                        ),
                    )
                }
            }

            is VaultUnlockResult.BiometricDecodingError -> {
                authRepository.clearBiometrics(userId = state.userId)
                mutableStateFlow.update {
                    it.copy(
                        isBiometricsValid = false,
                        dialog = VaultUnlockState.VaultUnlockDialog.Error(
                            title = BitwardenString.biometrics_failed.asText(),
                            message = BitwardenString.biometrics_decoding_failure.asText(),
                        ),
                    )
                }
            }

            is VaultUnlockResult.GenericError,
            is VaultUnlockResult.InvalidStateError,
                -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = VaultUnlockState.VaultUnlockDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = BitwardenString.generic_error_message.asText(),
                            throwable = (result as? VaultUnlockResult.InvalidStateError)?.error
                                ?: (result as? VaultUnlockResult.GenericError)?.error,
                        ),
                    )
                }
            }

            VaultUnlockResult.Success -> {
                if (specialCircumstanceManager.specialCircumstance == null) {
                    specialCircumstanceManager.specialCircumstance =
                        appResumeManager.getResumeSpecialCircumstance()
                }

                mutableStateFlow.update { it.copy(dialog = null) }
                // Don't do anything, we'll navigate to the right place.
            }
        }
    }

    private fun handleUserStateUpdateReceive(
        action: VaultUnlockAction.Internal.UserStateUpdateReceive,
    ) {
        // Leave the current data alone if there is no UserState; we are in the process of logging
        // out.
        val userState = action.userState ?: return

        // If the Vault is being unlocked for a FIDO 2 request, make sure we're unlocking the
        // correct Vault
        state.credentialManagerRequestUserId
            ?.let { fido2RequestUserId ->
                // If the current Vault is not the selected Vault, switch accounts.
                if (userState.activeUserId != fido2RequestUserId) {
                    authRepository.switchAccount(fido2RequestUserId)
                    return
                }
            }
        // If the Vault is already unlocked, do nothing.
        if (userState.activeAccount.isVaultUnlocked) return
        // If the user state has changed to add a new account, do nothing.
        if (userState.hasPendingAccountAddition) return

        mutableStateFlow.update {
            val accountSummaries = userState.toAccountSummaries()
            val activeAccountSummary = userState.toActiveAccountSummary()
            it.copy(
                userId = userState.activeUserId,
                initials = activeAccountSummary.initials,
                avatarColorString = activeAccountSummary.avatarColorHex,
                accountSummaries = accountSummaries,
                email = activeAccountSummary.email,
                isBiometricEnabled = userState.activeAccount.isBiometricsEnabled,
                vaultUnlockType = userState.activeAccount.vaultUnlockType,
                input = "",
                hasMasterPassword = userState.activeAccount.hasMasterPassword,
            )
        }

        // If the new account has biometrics available, automatically prompt for biometrics.
        promptForBiometricsIfAvailable()
    }

    private fun promptForBiometricsIfAvailable() {
        val cipher = authRepository.getOrCreateCipher(state.userId)
        if (state.showBiometricLogin && cipher != null && !state.isFromLockFlow) {
            sendEvent(VaultUnlockEvent.PromptForBiometrics(cipher = cipher))
        }
    }
}

/**
 * Models state of the vault unlock screen.
 */
@Parcelize
data class VaultUnlockState(
    val accountSummaries: ImmutableList<AccountSummary>,
    private val avatarColorString: String,
    val hideInput: Boolean,
    val initials: String,
    val email: String,
    val environmentUrl: String,
    val dialog: VaultUnlockDialog?,
    // We never want this saved since the input is sensitive data.
    @IgnoredOnParcel val input: String = "",
    val isBiometricsValid: Boolean,
    val isBiometricEnabled: Boolean,
    val showAccountMenu: Boolean,
    val showBiometricInvalidatedMessage: Boolean,
    val vaultUnlockType: VaultUnlockType,
    val userId: String,
    val getCredentialsRequest: GetCredentialsRequest? = null,
    val fido2CredentialAssertionRequest: Fido2CredentialAssertionRequest? = null,
    val createCredentialRequest: CreateCredentialRequest? = null,
    private val hasMasterPassword: Boolean,
    val isFromLockFlow: Boolean,
) : Parcelable {

    /**
     * The [Color] of the avatar.
     */
    val avatarColor: Color get() = avatarColorString.hexToColor()

    /**
     * Indicates if we should display the button login with biometrics.
     */
    val showBiometricLogin: Boolean get() = isBiometricEnabled && isBiometricsValid

    /**
     * Indicates if we want force focus on Master Password \ PIN input field and show keyboard.
     */
    val showKeyboard: Boolean get() = !showBiometricLogin && !hideInput

    /**
     * Returns the user ID present in the current CredentialManager request, or null when no request
     * is present.
     */
    val credentialManagerRequestUserId: String?
        get() = getCredentialsRequest?.userId ?: fido2CredentialAssertionRequest?.userId

    /**
     * Indicates if the Vault is being unlocked for a CredentialManager request.
     */
    val isUnlockingForCredentialManagerRequest: Boolean
        get() = getCredentialsRequest != null ||
            fido2CredentialAssertionRequest != null ||
            createCredentialRequest != null

    /**
     * If the user requires biometrics to be able to unlock the account.
     */
    val requiresBiometricsLogin: Boolean
        get() = when (vaultUnlockType) {
            VaultUnlockType.MASTER_PASSWORD -> !hasMasterPassword && isBiometricEnabled
            VaultUnlockType.PIN -> false
        }

    /**
     * Represents the various dialogs the vault unlock screen can display.
     */
    sealed class VaultUnlockDialog : Parcelable {
        /**
         * Represents an error dialog.
         */
        @Parcelize
        data class Error(
            val title: Text,
            val message: Text,
            val throwable: Throwable? = null,
        ) : VaultUnlockDialog()

        /**
         * Represents a loading state dialog.
         */
        @Parcelize
        data object Loading : VaultUnlockDialog()

        /**
         * Show dialog for when biometrics the user has is no longer supported.
         */
        @Parcelize
        data object BiometricsNoLongerSupported : VaultUnlockDialog()
    }
}

/**
 * Models events for the vault unlock screen.
 */
sealed class VaultUnlockEvent {
    /**
     * Prompts the user for biometrics unlock.
     */
    data class PromptForBiometrics(val cipher: Cipher) : VaultUnlockEvent(), DeferredBackgroundEvent

    /**
     * KIKARON: asks for the one fingerprint that switches fingerprint unlock on.
     */
    data class KikaronPromptFingerprint(
        val cipher: Cipher,
    ) : VaultUnlockEvent(), DeferredBackgroundEvent

    /**
     * Completes the get credentials request with an error response.
     */
    data class GetCredentialsError(val message: Text) : VaultUnlockEvent()

    /**
     * Completes the FIDO2 credential assertion request with an error response.
     */
    data class Fido2CredentialAssertionError(val message: Text) : VaultUnlockEvent()
}

/**
 * Models actions for the vault unlock screen.
 */
sealed class VaultUnlockAction {
    /**
     * The user has clicked the add account button.
     */
    data object AddAccountClick : VaultUnlockAction()

    /**
     * The user dismissed the currently displayed dialog.
     */
    data object DismissDialog : VaultUnlockAction()

    /**
     * The user has dismissed the biometrics not supported dialog
     */
    data object DismissBiometricsNoLongerSupportedDialog : VaultUnlockAction()

    /**
     * The user has clicked on the logout confirmation button.
     */
    data object ConfirmLogoutClick : VaultUnlockAction()

    /**
     * The user has modified the input.
     */
    data class InputChanged(
        val input: String,
    ) : VaultUnlockAction()

    /**
     * Indicates the user has clicked on the given [accountSummary] information in order to lock
     * the associated account's vault.
     */
    data class LockAccountClick(
        val accountSummary: AccountSummary,
    ) : VaultUnlockAction()

    /**
     * Indicates the user has clicked on the given [accountSummary] information in order to log out
     * of that account.
     */
    data class LogoutAccountClick(
        val accountSummary: AccountSummary,
    ) : VaultUnlockAction()

    /**
     * The user has clicked the an account to switch too.
     */
    data class SwitchAccountClick(
        val accountSummary: AccountSummary,
    ) : VaultUnlockAction()

    /**
     * The user has clicked the biometrics button.
     */
    data object BiometricsUnlockClick : VaultUnlockAction()

    /**
     * The user has received a successful response from the biometrics call.
     */
    data class BiometricsUnlockSuccess(
        val cipher: Cipher,
    ) : VaultUnlockAction()

    /**
     * The user has attempted to login with biometrics too many times and has been locked out.
     */
    data object BiometricsLockOut : VaultUnlockAction()

    /**
     * The user has biometric unlock setup that is no longer valid.
     */
    data object BiometricsNoLongerSupported : VaultUnlockAction()

    /**
     * The user has clicked the unlock button.
     */
    data object UnlockClick : VaultUnlockAction()

    /**
     * KIKARON: whether the phone has a strong biometric with a finger enrolled.
     */
    data class KikaronFingerprintSupport(
        val isSupported: Boolean,
    ) : VaultUnlockAction()

    /**
     * KIKARON: the answer to the fingerprint prompt: an authenticated [cipher], or null
     * ([isDeclined] for "Niet nu").
     */
    data class KikaronFingerprintResult(
        val cipher: Cipher?,
        val isDeclined: Boolean,
    ) : VaultUnlockAction()

    /**
     * Models actions that the [VaultUnlockViewModel] itself might send.
     */
    sealed class Internal : VaultUnlockAction() {
        /**
         * Indicates a vault unlock result has been received.
         */
        data class ReceiveVaultUnlockResult(
            val userId: String,
            val vaultUnlockResult: VaultUnlockResult,
            val isBiometricLogin: Boolean,
        ) : Internal()

        /**
         * Indicates a change in user state has been received.
         */
        data class UserStateUpdateReceive(
            val userState: UserState?,
        ) : Internal()
    }
}
