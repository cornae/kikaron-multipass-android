"""Fingerprint unlock after the one Kikaron sign-in (run by brand.py).

"The Multipass app needs a Kikaron login, which you should only have to do once. After
that, a fingerprint should be enough to unlock the vault." (Daniel, 2026-11-10)

  enrol     an unlock without a fingerprint, while fingerprint unlock is off or no longer
            valid and the phone has a strong biometric, first asks for ONE fingerprint
            ("Ontgrendel voortaan met uw vingerafdruk"); the vault opens and the fingerprint
            key is written right after it (setupBiometricsKey, as Settings does). On the
            unlock screen itself, so passkeys and autofill land back in their flow.
  no logout a biometric lockout, or biometrics this phone no longer supports, leave the
            vault locked (the password field) instead of logging out
  approval  while there is no working fingerprint key (a fresh install after the Kikaron
            sign-in), the vault is opened by "Log in with device" from another device whose
            vault is open, then one fingerprint; the Multipass password (or the recovery
            code) only behind a small link - kikaron-app-android docs/multipass-one-login.md
  lock      the session timeout locks, never logs out, whenever the vault can be unlocked
            again; switching fingerprint unlock on turns an old "log out" into "lock"

Every edit is anchored on exact upstream text and fails loudly when it has moved.
"""

VM = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/VaultUnlockViewModel.kt'
SCREEN = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/VaultUnlockScreen.kt'
VM_TEST = 'app/src/test/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/VaultUnlockViewModelTest.kt'
SETTINGS_REPO = 'app/src/main/kotlin/com/x8bit/bitwarden/data/platform/repository/SettingsRepositoryImpl.kt'
FINGERPRINT_KT = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/KikaronFingerprintUnlock.kt'

STRINGS = {
    'values': {
        'kikaron_fingerprint_title': 'Unlock with your fingerprint from now on',
        'kikaron_fingerprint_description': 'Then you no longer need to enter your Multipass password.',
        'kikaron_fingerprint_not_now': 'Not now',
    },
    'values-nl-rNL': {
        'kikaron_fingerprint_title': 'Ontgrendel voortaan met uw vingerafdruk',
        'kikaron_fingerprint_description': 'Dan hoeft u uw Multipass-wachtwoord niet meer in te voeren.',
        'kikaron_fingerprint_not_now': 'Niet nu',
    },
}

# ---- the view model -------------------------------------------------------------------
CTOR_OLD = """    private val vaultLockManager: VaultLockManager,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel"""
CTOR_NEW = """    private val vaultLockManager: VaultLockManager,
    // KIKARON: to switch fingerprint unlock on right after an unlock
    private val settingsRepository: com.x8bit.bitwarden.data.platform.repository.SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel"""

ACTIONS_OLD = """            VaultUnlockAction.UnlockClick -> handleUnlockClick()
"""
ACTIONS_NEW = """            VaultUnlockAction.UnlockClick -> handleUnlockClick()
            // KIKARON: one fingerprint switches fingerprint unlock on
            is VaultUnlockAction.KikaronFingerprintSupport -> {
                kikaronFingerprintSupported = action.isSupported
            }

            is VaultUnlockAction.KikaronFingerprintResult -> {
                handleKikaronFingerprintResult(action)
            }
"""

NO_LONGER_OLD = """        mutableStateFlow.update {
            it.copy(dialog = null)
        }
        authRepository.logout(reason = LogoutReason.Biometrics.NoLongerSupported)
        authRepository.hasPendingAccountAddition = true
    }"""
NO_LONGER_NEW = """        // KIKARON: never log out - the vault stays locked, opened by password (or PIN)
        mutableStateFlow.update {
            it.copy(dialog = null, isBiometricsValid = false)
        }
    }"""

LOCKOUT_OLD = """    private fun handleBiometricsLockOut() {
        authRepository.logout(reason = LogoutReason.Biometrics.Lockout)
    }"""
LOCKOUT_NEW = """    private fun handleBiometricsLockOut() {
        // KIKARON: never log out - say so and leave the vault locked (password or a later try)
        mutableStateFlow.update {
            it.copy(
                dialog = VaultUnlockState.VaultUnlockDialog.Error(
                    title = BitwardenString.biometrics_failed.asText(),
                    message = BitwardenString.too_many_failed_biometric_attempts.asText(),
                ),
            )
        }
    }"""

UNLOCK_OLD = """        mutableStateFlow.update { it.copy(dialog = VaultUnlockState.VaultUnlockDialog.Loading) }
        viewModelScope.launch {
            val vaultUnlockResult = when (state.vaultUnlockType) {
                VaultUnlockType.MASTER_PASSWORD -> {
                    vaultRepo.unlockVaultWithMasterPassword(
                        state.input,
                    )
                }

                VaultUnlockType.PIN -> {
                    vaultRepo.unlockVaultWithPin(
                        state.input,
                    )
                }
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
"""
UNLOCK_NEW = """        // KIKARON: while fingerprint unlock is off, one fingerprint first switches it on
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
                        // KIKARON: a recovery code (the vault's master password) typed loosely
                        // is tried in its one form first (KikaronDeviceApproval.kt)
                        kikaronCanonicalRecoveryCode(input)
                            ?.takeIf { it != input }
                            ?.let { vaultRepo.unlockVaultWithMasterPassword(it) }
                            ?.takeIf { it !is VaultUnlockResult.AuthenticationError }
                            ?: vaultRepo.unlockVaultWithMasterPassword(input)
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
"""

EVENT_OLD = """    data class PromptForBiometrics(val cipher: Cipher) : VaultUnlockEvent(), DeferredBackgroundEvent
"""
EVENT_NEW = """    data class PromptForBiometrics(val cipher: Cipher) : VaultUnlockEvent(), DeferredBackgroundEvent

    /**
     * KIKARON: asks for the one fingerprint that switches fingerprint unlock on.
     */
    data class KikaronPromptFingerprint(
        val cipher: Cipher,
    ) : VaultUnlockEvent(), DeferredBackgroundEvent
"""

ACTION_OLD = """    data object UnlockClick : VaultUnlockAction()
"""
ACTION_NEW = """    data object UnlockClick : VaultUnlockAction()

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
"""

# ---- the screen -----------------------------------------------------------------------
SCREEN_TOP_OLD = """    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(state.requiresBiometricsLogin) {"""
SCREEN_TOP_NEW = """    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // KIKARON: fingerprint unlock is offered on a phone with a strong biometric
    val kikaronContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.trySendAction(
            VaultUnlockAction.KikaronFingerprintSupport(
                isSupported = biometricsManager.isBiometricsSupported,
            ),
        )
    }
    LaunchedEffect(state.requiresBiometricsLogin) {"""

SCREEN_EVENT_OLD = """            is VaultUnlockEvent.Fido2CredentialAssertionError -> {"""
SCREEN_EVENT_NEW = """            // KIKARON: the one fingerprint that switches fingerprint unlock on
            is VaultUnlockEvent.KikaronPromptFingerprint -> {
                promptKikaronFingerprint(
                    context = kikaronContext,
                    cipher = event.cipher,
                ) { cipher, declined ->
                    viewModel.trySendAction(
                        VaultUnlockAction.KikaronFingerprintResult(
                            cipher = cipher,
                            isDeclined = declined,
                        ),
                    )
                }
            }

            is VaultUnlockEvent.Fido2CredentialAssertionError -> {"""

TEST_OLD = """        vaultLockManager = lockManager,
"""
TEST_NEW = """        vaultLockManager = lockManager,
        // KIKARON: fingerprint unlock after a password unlock
        settingsRepository = mockk(relaxed = true),
"""

# ---- the timeout: lock, never log out -------------------------------------------------
DEFAULTS_OLD = """                vaultTimeoutAction = if (!hasMasterPassword) {
                    // Always logout by default when there is no master password
                    VaultTimeoutAction.LOGOUT
                } else {
                    VaultTimeoutAction.LOCK
                },"""
DEFAULTS_NEW = """                // KIKARON: lock, never log out, whenever the vault can be opened again (a
                // Multipass password, a PIN or a fingerprint): the Kikaron sign-in is done once
                vaultTimeoutAction = if (!hasMasterPassword && !hasPin && !hasBiometrics) {
                    VaultTimeoutAction.LOGOUT
                } else {
                    VaultTimeoutAction.LOCK
                },"""


def brand_fingerprint_unlock(root, here, edit, fail):
    (root / FINGERPRINT_KT).write_text(
        (here / 'android' / 'KikaronFingerprintUnlock.kt').read_text(encoding='utf-8'), encoding='utf-8')
    for folder, strings in STRINGS.items():
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- KIKARON: generated by kikaron/brand.py - do not edit -->', '<resources>']
        out += ['    <string name="%s">%s</string>' % (k, v) for k, v in strings.items()]
        out += ['</resources>', '']
        res = root / 'app/src/main/res' / folder
        res.mkdir(parents=True, exist_ok=True)
        (res / 'strings_kikaron_fingerprint.xml').write_text('\n'.join(out), encoding='utf-8')
    edit(VM, CTOR_OLD, CTOR_NEW)
    edit(VM, ACTIONS_OLD, ACTIONS_NEW)
    edit(VM, NO_LONGER_OLD, NO_LONGER_NEW)
    edit(VM, LOCKOUT_OLD, LOCKOUT_NEW)
    edit(VM, UNLOCK_OLD, UNLOCK_NEW)
    edit(VM, EVENT_OLD, EVENT_NEW)
    edit(VM, ACTION_OLD, ACTION_NEW)
    edit(SCREEN, SCREEN_TOP_OLD, SCREEN_TOP_NEW)
    edit(SCREEN, SCREEN_EVENT_OLD, SCREEN_EVENT_NEW)
    if (root / VM_TEST).exists():
        edit(VM_TEST, TEST_OLD, TEST_NEW)
    edit(SETTINGS_REPO, DEFAULTS_OLD, DEFAULTS_NEW)


# ---- device approval: no password after the Kikaron sign-in --------------------------------
APPROVAL_KT = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/KikaronDeviceApproval.kt'

APPROVAL_STRINGS = {
    'values': {
        'kikaron_unlock_fingerprint_message': 'Unlock your vault with your fingerprint.',
        'kikaron_unlock_fingerprint_button': 'Unlock with fingerprint',
        'kikaron_use_password': 'Use your Multipass password or recovery code instead',
        'kikaron_approval_title': 'Approve on another device',
        'kikaron_approval_message': 'Open Multipass in the Kikaron app, or on another device where your vault is open, and approve this request. Check that the phrase there is the same as this one:',
        'kikaron_approval_waiting': 'Waiting for approval…',
        'kikaron_approval_opening': 'Opening your vault…',
        'kikaron_approval_declined': 'The request was declined.',
        'kikaron_approval_expired': 'The request has expired.',
        'kikaron_approval_error': 'The request could not be sent. Check your connection.',
        'kikaron_approval_failed': 'Your vault could not be opened with this approval.',
        'kikaron_approval_retry': 'Ask again',
    },
    'values-nl-rNL': {
        'kikaron_unlock_fingerprint_message': 'Ontgrendel uw kluis met uw vingerafdruk.',
        'kikaron_unlock_fingerprint_button': 'Ontgrendel met vingerafdruk',
        'kikaron_use_password': 'Toch uw Multipass-wachtwoord of herstelcode gebruiken',
        'kikaron_approval_title': 'Goedkeuren op een ander apparaat',
        'kikaron_approval_message': 'Open Multipass in de Kikaron-app, of op een ander apparaat waar uw kluis open is, en keur dit verzoek goed. Controleer dat de woordgroep daar dezelfde is als deze:',
        'kikaron_approval_waiting': 'Wachten op goedkeuring…',
        'kikaron_approval_opening': 'Uw kluis wordt geopend…',
        'kikaron_approval_declined': 'Het verzoek is afgewezen.',
        'kikaron_approval_expired': 'Het verzoek is verlopen.',
        'kikaron_approval_error': 'Het verzoek kon niet worden verstuurd. Controleer uw verbinding.',
        'kikaron_approval_failed': 'Uw kluis kon met deze goedkeuring niet worden geopend.',
        'kikaron_approval_retry': 'Opnieuw vragen',
    },
}

MODES_OLD = """                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            if (!state.hideInput) {
"""
MODES_NEW = """                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            // KIKARON: after the one Kikaron sign-in a fingerprint - or, while there is no
            // working fingerprint key yet, approval from another device; the Multipass
            // password only behind a small link (KikaronDeviceApproval.kt)
            var kikaronUsePassword by rememberSaveable { mutableStateOf(false) }
            val kikaronMode = kikaronUnlockMode(
                state = state,
                isBiometricsSupported = biometricsManager.isBiometricsSupported,
                usePassword = kikaronUsePassword,
            )
            if (kikaronMode == KikaronUnlockMode.FINGERPRINT) {
                KikaronFingerprintContent(
                    onUnlock = { viewModel.trySendAction(VaultUnlockAction.BiometricsUnlockClick) },
                    onUsePassword = { kikaronUsePassword = true },
                )
            } else if (kikaronMode == KikaronUnlockMode.DEVICE_APPROVAL) {
                KikaronDeviceApprovalContent(
                    email = state.email,
                    isBiometricsSupported = biometricsManager.isBiometricsSupported,
                    onUsePassword = { kikaronUsePassword = true },
                )
            } else {
            if (!state.hideInput) {
"""
MODES_END_OLD = """            // KIKARON: the one way out while locked (the menu is gone)
"""
MODES_END_NEW = """            }
            // KIKARON: end of the password part (kikaronMode PASSWORD)
            // KIKARON: the one way out while locked (the menu is gone)
"""


def brand_device_approval(root, here, edit, fail):
    (root / APPROVAL_KT).write_text(
        (here / 'android' / 'KikaronDeviceApproval.kt').read_text(encoding='utf-8'), encoding='utf-8')
    for folder, strings in APPROVAL_STRINGS.items():
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- KIKARON: generated by kikaron/brand.py - do not edit -->', '<resources>']
        out += ['    <string name="%s">%s</string>' % (k, v) for k, v in strings.items()]
        out += ['</resources>', '']
        res = root / 'app/src/main/res' / folder
        res.mkdir(parents=True, exist_ok=True)
        (res / 'strings_kikaron_approval.xml').write_text('\n'.join(out), encoding='utf-8')
    edit(SCREEN, MODES_OLD, MODES_NEW)
    edit(SCREEN, MODES_END_OLD, MODES_END_NEW)
