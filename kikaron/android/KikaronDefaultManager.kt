package com.x8bit.bitwarden.ui.platform.feature.kikaron

// KIKARON: copied into place by kikaron/brand.py - edit kikaron/android/, not this copy.

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.bitwarden.ui.platform.base.util.LifecycleEventEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenTextButton
import com.bitwarden.ui.platform.components.dialog.util.maxDialogHeight
import com.bitwarden.ui.platform.components.dialog.util.maxDialogWidth
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenPushRow
import com.bitwarden.ui.platform.composition.LocalIntentManager
import com.bitwarden.ui.platform.manager.IntentManager
import com.bitwarden.ui.platform.manager.util.startSystemAutofillSettingsActivity
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.LEGACY_CREDENTIAL_SERVICE_NAME
import com.x8bit.bitwarden.R
import kotlinx.coroutines.delay
import timber.log.Timber

/*
 * "Multipass als wachtwoordbeheerder van uw telefoon gebruiken?" (Daniel, 2026-11-09)
 *
 * Right after the first unlock the vault offers, once per launch, to make Multipass the
 * phone's password manager: the system's own one-tap autofill dialog
 * (ACTION_REQUEST_SET_AUTOFILL_SERVICE) and, on Android 14 and later, the credential
 * provider settings for passkeys. It stops asking once both are on, or after it has been
 * put off twice. Settings keeps a permanent "Standaard wachtwoordbeheerder" row with the
 * same steps and the current state.
 */

private const val PREFS = "kikaron_default_manager"
private const val KEY_DONE = "done"
private const val KEY_DISMISSED = "dismissed"
private const val MAX_DISMISSALS = 2
private const val PROMPT_DELAY_MS = 800L
private const val RECHECK_DELAY_MS = 500L

/** Whether this process has already decided about the prompt (it asks once per launch). */
private object KikaronDefaultManagerSession {
    var decided = false
}

/**
 * Whether Multipass fills in passwords (autofill) and passkeys (the credential provider,
 * Android 14+). A part this phone does not have counts as done.
 */
private data class DefaultManagerStatus(
    val autofillSupported: Boolean,
    val autofillOn: Boolean,
    val passkeysSupported: Boolean,
    val passkeysOn: Boolean,
) {
    val autofillMissing: Boolean get() = autofillSupported && !autofillOn
    val passkeysMissing: Boolean get() = passkeysSupported && !passkeysOn
    val allOn: Boolean get() = !autofillMissing && !passkeysMissing
}

private enum class Step { ASK, PASSKEYS, RESULT, STATUS }

private enum class Waiting { NONE, AUTOFILL, PASSKEYS }

@Suppress("TooGenericExceptionCaught")
private fun readDefaultManagerStatus(context: Context): DefaultManagerStatus {
    val autofill = context.getSystemService(AutofillManager::class.java)
    val autofillSupported = try {
        autofill?.isAutofillSupported == true
    } catch (e: RuntimeException) {
        Timber.w(e, "isAutofillSupported failed")
        false
    }
    val autofillOn = try {
        autofill?.hasEnabledAutofillServices() == true
    } catch (e: RuntimeException) {
        Timber.w(e, "hasEnabledAutofillServices failed")
        false
    }
    val passkeysSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        hasCredentialManager(context)
    val passkeysOn = passkeysSupported &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        isCredentialProviderEnabled(context)
    return DefaultManagerStatus(
        autofillSupported = autofillSupported,
        autofillOn = autofillOn,
        passkeysSupported = passkeysSupported,
        passkeysOn = passkeysOn,
    )
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun hasCredentialManager(context: Context): Boolean =
    context.getSystemService(android.credentials.CredentialManager::class.java) != null

@Suppress("TooGenericExceptionCaught")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun isCredentialProviderEnabled(context: Context): Boolean = try {
    context
        .getSystemService(android.credentials.CredentialManager::class.java)
        ?.isEnabledCredentialProviderService(
            ComponentName(context.packageName, LEGACY_CREDENTIAL_SERVICE_NAME),
        ) == true
} catch (e: RuntimeException) {
    Timber.w(e, "isEnabledCredentialProviderService failed")
    false
}

/**
 * Opens the system's "Passwords, passkeys and accounts" settings: androidx's
 * CredentialManager.createSettingsPendingIntent() (upstream's startCredentialManagerSettings),
 * else Settings.ACTION_CREDENTIAL_PROVIDER. False when neither could be opened.
 */
@Suppress("TooGenericExceptionCaught")
private fun startCredentialProviderSettings(
    context: Context,
    intentManager: IntentManager,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    try {
        intentManager.startCredentialManagerSettings()
        return true
    } catch (e: Exception) {
        Timber.w(e, "createSettingsPendingIntent failed")
    }
    return intentManager.startActivity(
        Intent(Settings.ACTION_CREDENTIAL_PROVIDER)
            .setData("package:${context.packageName}".toUri()),
    ) || intentManager.startActivity(Intent(Settings.ACTION_CREDENTIAL_PROVIDER))
}

private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/**
 * The prompt after the first unlock (placed on the vault screen). Asks once per launch,
 * and no more once Multipass is set up or the question was put off twice.
 */
@Composable
fun KikaronDefaultManagerPrompt() {
    val context = LocalContext.current
    var isVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (KikaronDefaultManagerSession.decided) return@LaunchedEffect
        KikaronDefaultManagerSession.decided = true
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_DONE, false)) return@LaunchedEffect
        if (prefs.getInt(KEY_DISMISSED, 0) >= MAX_DISMISSALS) return@LaunchedEffect
        if (readDefaultManagerStatus(context).allOn) {
            prefs.edit { putBoolean(KEY_DONE, true) }
            return@LaunchedEffect
        }
        // let the vault draw first
        delay(PROMPT_DELAY_MS)
        isVisible = true
    }
    if (isVisible) {
        KikaronDefaultManagerDialog(firstStep = Step.ASK) { allOn ->
            isVisible = false
            val prefs = prefs(context)
            if (allOn) {
                prefs.edit { putBoolean(KEY_DONE, true) }
            } else {
                prefs.edit { putInt(KEY_DISMISSED, prefs.getInt(KEY_DISMISSED, 0) + 1) }
            }
        }
    }
}

/**
 * The permanent row in Settings: "Standaard wachtwoordbeheerder" with the current state;
 * a tap shows that state with the same steps as the prompt.
 */
@Composable
fun KikaronDefaultManagerSettingsRow() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readDefaultManagerStatus(context)) }
    var isOpen by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) status = readDefaultManagerStatus(context)
    }
    val on = stringResource(id = R.string.kikaron_default_manager_on)
    val off = stringResource(id = R.string.kikaron_default_manager_off)
    val autofillLabel = stringResource(id = R.string.kikaron_default_manager_autofill)
    val passkeysLabel = stringResource(id = R.string.kikaron_default_manager_passkeys)
    val summary = buildList {
        if (status.autofillSupported) add("$autofillLabel: ${if (status.autofillOn) on else off}")
        if (status.passkeysSupported) add("$passkeysLabel: ${if (status.passkeysOn) on else off}")
    }.joinToString(separator = " · ")
    Spacer(modifier = Modifier.height(height = 16.dp))
    BitwardenPushRow(
        text = stringResource(id = R.string.kikaron_default_manager_title),
        description = summary.ifEmpty { null },
        onClick = { isOpen = true },
        cardStyle = CardStyle.Full,
        leadingIcon = IconData.Local(iconRes = BitwardenDrawable.ic_key),
        modifier = Modifier
            .standardHorizontalMargin()
            .fillMaxWidth(),
    )
    if (isOpen) {
        KikaronDefaultManagerDialog(firstStep = Step.STATUS) { allOn ->
            isOpen = false
            status = readDefaultManagerStatus(context)
            if (allOn) prefs(context).edit { putBoolean(KEY_DONE, true) }
        }
    }
}

/**
 * The steps: ASK (the question) or STATUS (from Settings) -> the system's autofill dialog
 * -> PASSKEYS (one line, then the credential provider settings; Android 14+) -> RESULT
 * ("Klaar", or what is still off). Each system screen is read back when the app resumes.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun KikaronDefaultManagerDialog(
    firstStep: Step,
    onClose: (allOn: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val intentManager = LocalIntentManager.current
    var step by rememberSaveable { mutableStateOf(firstStep) }
    var waiting by rememberSaveable { mutableStateOf(Waiting.NONE) }
    var status by remember { mutableStateOf(readDefaultManagerStatus(context)) }

    fun afterAutofill() {
        status = readDefaultManagerStatus(context)
        step = if (status.passkeysMissing) Step.PASSKEYS else Step.RESULT
    }

    fun setUp() {
        status = readDefaultManagerStatus(context)
        if (status.autofillMissing) {
            waiting = Waiting.AUTOFILL
            if (!intentManager.startSystemAutofillSettingsActivity()) {
                waiting = Waiting.NONE
                afterAutofill()
            }
        } else {
            afterAutofill()
        }
    }

    fun openPasskeySettings() {
        waiting = Waiting.PASSKEYS
        if (!startCredentialProviderSettings(context, intentManager)) {
            waiting = Waiting.NONE
            status = readDefaultManagerStatus(context)
            step = Step.RESULT
        }
    }

    // outside the Dialog: the activity's lifecycle, which pauses while a system screen shows
    LifecycleEventEffect { _, event ->
        if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventEffect
        when (waiting) {
            Waiting.NONE -> status = readDefaultManagerStatus(context)
            Waiting.AUTOFILL -> {
                waiting = Waiting.NONE
                afterAutofill()
            }

            Waiting.PASSKEYS -> {
                waiting = Waiting.NONE
                status = readDefaultManagerStatus(context)
                step = Step.RESULT
            }
        }
    }
    // the system may write its setting a moment after its screen closed: read once more
    LaunchedEffect(step) {
        if (step == Step.RESULT || step == Step.STATUS) {
            delay(RECHECK_DELAY_MS)
            status = readDefaultManagerStatus(context)
        }
    }

    Dialog(
        onDismissRequest = { onClose(status.allOn) },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val configuration = LocalConfiguration.current
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .requiredHeightIn(max = configuration.maxDialogHeight)
                .requiredWidthIn(max = configuration.maxDialogWidth)
                .background(
                    color = BitwardenTheme.colorScheme.background.primary,
                    shape = BitwardenTheme.shapes.dialog,
                )
                .verticalScroll(rememberScrollState())
                .padding(all = 24.dp),
        ) {
            MultipassMark()
            Spacer(modifier = Modifier.height(height = 16.dp))
            when (step) {
                Step.ASK -> {
                    DialogTitle(text = stringResource(id = R.string.kikaron_default_manager_ask))
                    DialogText(
                        text = stringResource(
                            id = if (status.passkeysSupported) {
                                R.string.kikaron_default_manager_why
                            } else {
                                R.string.kikaron_default_manager_why_passwords
                            },
                        ),
                    )
                    Spacer(modifier = Modifier.height(height = 24.dp))
                    BitwardenFilledButton(
                        label = stringResource(id = R.string.kikaron_default_manager_set_up),
                        onClick = { setUp() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BitwardenTextButton(
                        label = stringResource(id = R.string.kikaron_default_manager_later),
                        onClick = { onClose(false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Step.PASSKEYS -> {
                    DialogTitle(
                        text = stringResource(id = R.string.kikaron_default_manager_passkey_title),
                    )
                    DialogText(
                        text = stringResource(id = R.string.kikaron_default_manager_passkey_hint),
                        isStrong = true,
                    )
                    Spacer(modifier = Modifier.height(height = 8.dp))
                    DialogText(
                        text = stringResource(id = R.string.kikaron_default_manager_passkey_back),
                    )
                    Spacer(modifier = Modifier.height(height = 24.dp))
                    BitwardenFilledButton(
                        label = stringResource(id = R.string.kikaron_default_manager_open_settings),
                        onClick = { openPasskeySettings() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BitwardenTextButton(
                        label = stringResource(id = R.string.kikaron_default_manager_skip),
                        onClick = {
                            status = readDefaultManagerStatus(context)
                            step = Step.RESULT
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Step.RESULT, Step.STATUS -> {
                    if (step == Step.RESULT && status.allOn) {
                        DialogTitle(
                            text = stringResource(id = R.string.kikaron_default_manager_done_title),
                        )
                        DialogText(
                            text = stringResource(id = R.string.kikaron_default_manager_done),
                        )
                    } else {
                        DialogTitle(
                            text = stringResource(
                                id = if (step == Step.STATUS) {
                                    R.string.kikaron_default_manager_title
                                } else {
                                    R.string.kikaron_default_manager_not_yet_title
                                },
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.height(height = 16.dp))
                    StatusLine(
                        label = stringResource(id = R.string.kikaron_default_manager_autofill),
                        isSupported = status.autofillSupported,
                        isOn = status.autofillOn,
                    )
                    StatusLine(
                        label = stringResource(id = R.string.kikaron_default_manager_passkeys),
                        isSupported = status.passkeysSupported,
                        isOn = status.passkeysOn,
                    )
                    Spacer(modifier = Modifier.height(height = 24.dp))
                    if (!status.allOn) {
                        BitwardenFilledButton(
                            label = stringResource(
                                id = if (step == Step.STATUS) {
                                    R.string.kikaron_default_manager_set_up
                                } else {
                                    R.string.kikaron_default_manager_retry
                                },
                            ),
                            onClick = { setUp() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BitwardenTextButton(
                        label = stringResource(id = R.string.kikaron_default_manager_close),
                        onClick = { onClose(status.allOn) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The Multipass mark, as on the start screen. */
@Composable
private fun MultipassMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colorResource(id = R.color.ic_launcher_background)),
    ) {
        // the launcher glyph is drawn for the middle 72 of 108 dp
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(84.dp),
        )
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = BitwardenTheme.typography.headlineSmall,
        color = BitwardenTheme.colorScheme.text.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(height = 12.dp))
}

@Composable
private fun DialogText(text: String, isStrong: Boolean = false) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = if (isStrong) {
            BitwardenTheme.typography.bodyLarge
        } else {
            BitwardenTheme.typography.bodyMedium
        },
        color = if (isStrong) {
            BitwardenTheme.colorScheme.text.primary
        } else {
            BitwardenTheme.colorScheme.text.secondary
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusLine(label: String, isSupported: Boolean, isOn: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = BitwardenTheme.typography.bodyLarge,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                id = when {
                    !isSupported -> R.string.kikaron_default_manager_unsupported
                    isOn -> R.string.kikaron_default_manager_on
                    else -> R.string.kikaron_default_manager_off
                },
            ),
            style = BitwardenTheme.typography.labelLarge,
            color = when {
                !isSupported -> BitwardenTheme.colorScheme.text.secondary
                isOn -> BitwardenTheme.colorScheme.status.good
                else -> BitwardenTheme.colorScheme.status.error
            },
        )
    }
}
