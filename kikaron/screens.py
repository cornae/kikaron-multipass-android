"""The start screen and the unlock screen of Kikaron Multipass (run by brand.py).

As on iOS and in Kikaron's web app: the app opens on one screen with the Multipass
mark and "Log in with Kikaron" (and starts that by itself on a first launch);
after the sign-in, the unlock screen is plain - a title, one line, the password
field, one button. The start screen is ours (kikaron/android/); the unlock screen
is upstream's with its extras taken off. Every edit is anchored on exact upstream
text and fails loudly when it has moved.
"""
import re

LANDING_NAV = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/landing/LandingNavigation.kt'
AUTH_NAV = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/auth/AuthNavigation.kt'
UNLOCK = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/vaultunlock/VaultUnlockScreen.kt'
LANDING_KT = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/landing/KikaronLandingScreen.kt'

STRINGS = {
    'values': {
        'kikaron_product_name': 'Multipass',
        'kikaron_sign_in': 'Log in with Kikaron',
        'kikaron_sign_in_email': 'Log in with e-mail and Multipass password',
    },
    'values-nl-rNL': {
        'kikaron_product_name': 'Multipass',
        'kikaron_sign_in': 'Log in met Kikaron',
        'kikaron_sign_in_email': 'Log in met e-mail en Multipass-wachtwoord',
    },
}
# upstream strings that say something else on our plain screens
OVERRIDES = {
    'values': {
        'vault_locked_master_password': 'Enter your Multipass password to open your vault.',
    },
    'values-nl-rNL': {
        'vault_locked_master_password': 'Voer uw Multipass-wachtwoord in om uw kluis te openen.',
        'unlock': 'Ontgrendel',
    },
}

LANDING_OLD = """    onNavigateToPreAuthSettings: () -> Unit,
) {
    composableWithStayTransitions<LandingRoute> {
        LandingScreen(
            onNavigateToLogin = onNavigateToLogin,
            onNavigateToEnvironment = onNavigateToEnvironment,
            onNavigateToStartRegistration = onNavigateToStartRegistration,
            onNavigateToPreAuthSettings = onNavigateToPreAuthSettings,
        )
    }
}"""
LANDING_NEW = """    onNavigateToPreAuthSettings: () -> Unit,
    onNavigateToKikaronSignIn: () -> Unit,
) {
    composableWithStayTransitions<LandingRoute> {
        // KIKARON: the start screen is "Log in with Kikaron"; upstream's behind its link
        KikaronLandingScreen(onSignIn = onNavigateToKikaronSignIn) {
            LandingScreen(
                onNavigateToLogin = onNavigateToLogin,
                onNavigateToEnvironment = onNavigateToEnvironment,
                onNavigateToStartRegistration = onNavigateToStartRegistration,
                onNavigateToPreAuthSettings = onNavigateToPreAuthSettings,
            )
        }
    }
}"""

AUTH_OLD = """            onNavigateToPreAuthSettings = { navController.navigateToPreAuthSettings() },
        )
        welcomeDestination("""
AUTH_NEW = """            onNavigateToPreAuthSettings = { navController.navigateToPreAuthSettings() },
            // KIKARON: single sign-on needs no e-mail address; the token carries it
            onNavigateToKikaronSignIn = {
                navController.navigateToEnterpriseSignOn(emailAddress = "")
            },
        )
        welcomeDestination("""

ACTIONS_OLD = """                actions = {
                    if (state.showAccountMenu) {
                        BitwardenAccountActionItem(
                            initials = state.initials,
                            color = state.avatarColor,
                            onClick = {
                                focusManager.clearFocus()
                                accountMenuVisible = !accountMenuVisible
                            },
                        )
                    }
                    BitwardenOverflowActionItem(
                        menuItemDataList = persistentListOf(
                            OverflowMenuItemData(
                                text = stringResource(id = BitwardenString.log_out),
                                onClick = { showLogoutConfirmationDialog = true },
                            ),
                        ),
                    )
                },"""
ACTIONS_NEW = """                // KIKARON: no account switcher, no menu
                actions = {},"""

FOOTER_OLD = """                    Spacer(modifier = Modifier.height(height = 16.dp))
                }
                Text(
                    text = stringResource(
                        id = BitwardenString.logged_in_as_on,
                        formatArgs = arrayOf(state.email, state.environmentUrl),
                    ),
                    style = BitwardenTheme.typography.bodySmall,
                    color = BitwardenTheme.colorScheme.text.secondary,
                    modifier = Modifier
                        .testTag(tag = "UserAndEnvironmentDataLabel")
                        .fillMaxWidth(),
                )
            }"""
FOOTER_NEW = """                }
                // KIKARON: no "logged in as ... on ..." line
            }"""

END_OLD = """            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}"""
END_NEW = """            // KIKARON: the one way out while locked (the menu is gone)
            Spacer(modifier = Modifier.height(8.dp))
            com.bitwarden.ui.platform.components.button.BitwardenTextButton(
                label = stringResource(id = BitwardenString.log_out),
                onClick = { showLogoutConfirmationDialog = true },
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}"""


def brand_screens(root, here, edit, fail):
    (root / LANDING_KT).write_text(
        (here / 'android' / 'KikaronLandingScreen.kt').read_text(encoding='utf-8'), encoding='utf-8')
    for folder, strings in STRINGS.items():
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- KIKARON: generated by kikaron/brand.py - do not edit -->', '<resources>']
        out += ['    <string name="%s">%s</string>' % (k, v) for k, v in strings.items()]
        out += ['</resources>', '']
        res = root / 'app/src/main/res' / folder
        res.mkdir(parents=True, exist_ok=True)
        (res / 'strings_kikaron.xml').write_text('\n'.join(out), encoding='utf-8')
    for folder, strings in OVERRIDES.items():
        path = root / 'ui/src/main/res' / folder / 'strings.xml'
        text = path.read_text(encoding='utf-8')
        for key, value in strings.items():
            new = re.sub(r'(<string name="%s"[^>]*>)[^<]*(</string>)' % key,
                         lambda m: m.group(1) + value + m.group(2), text)
            if new == text and value not in text:
                fail('string %s not found in %s' % (key, path))
            text = new
        path.write_text(text, encoding='utf-8')
    edit(LANDING_NAV, LANDING_OLD, LANDING_NEW)
    edit(AUTH_NAV, AUTH_OLD, AUTH_NEW)
    edit(UNLOCK, 'title = state.vaultUnlockType.unlockScreenTitle(),', 'title = "Multipass",')
    edit(UNLOCK, ACTIONS_OLD, ACTIONS_NEW)
    edit(UNLOCK, FOOTER_OLD, FOOTER_NEW)
    edit(UNLOCK, END_OLD, END_NEW)
