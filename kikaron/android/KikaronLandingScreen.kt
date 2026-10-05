package com.x8bit.bitwarden.ui.auth.feature.landing

// KIKARON: copied into place by kikaron/brand.py - edit kikaron/android/, not this copy.

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenTextButton
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.R

/**
 * Whether the app has already started the Kikaron sign-in by itself in this
 * process. It does so once, on the first launch without an account; after a
 * cancel or a log-out the start screen waits for the button.
 */
private object KikaronAutoSignIn {
    var started = false
}

/**
 * The start screen of Kikaron Multipass: the Multipass mark, one button that signs
 * in through Kikaron (the vault's single sign-on), and a small link to upstream's
 * e-mail log-in for an account without Kikaron.
 */
@Composable
fun KikaronLandingScreen(
    onSignIn: () -> Unit,
    upstream: @Composable () -> Unit,
) {
    var showUpstream by rememberSaveable { mutableStateOf(false) }
    if (showUpstream) {
        upstream()
        return
    }
    LaunchedEffect(Unit) {
        if (!KikaronAutoSignIn.started) {
            KikaronAutoSignIn.started = true
            onSignIn()
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(BitwardenTheme.colorScheme.background.primary)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colorResource(id = R.color.ic_launcher_background)),
        ) {
            // the launcher glyph is drawn for the middle 72 of 108 dp
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(144.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(id = R.string.kikaron_product_name),
            style = BitwardenTheme.typography.headlineMedium,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Spacer(modifier = Modifier.height(36.dp))
        BitwardenFilledButton(
            label = stringResource(id = R.string.kikaron_sign_in),
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        BitwardenTextButton(
            label = stringResource(id = R.string.kikaron_sign_in_email),
            onClick = { showUpstream = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
