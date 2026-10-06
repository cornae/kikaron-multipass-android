#!/usr/bin/env python3
"""Turn upstream bitwarden/android into Kikaron Multipass for Android.

    python3 kikaron/brand.py            # from the repo root, after a fresh upstream checkout

Idempotent, and loud: every edit is anchored on exact upstream text and the script
exits non-zero when an anchor has moved, so an upstream release that changed one
is a signal to look, never a half-branded app. Run by kikaron/brand.sh; never
hand-edit the files it writes. The iOS app (kikaron-multipass-ios, branch kikaron)
is the model: same names, same server, same identifier, same icon.

What it changes
  identity   applicationId com.kikaron.multipass (the Kotlin namespace stays
             upstream's: it is code, not identity); launcher name "Kikaron
             Multipass", "Multipass" inside the app
  strings    "Bitwarden" -> "Multipass" in the VALUES of every language's
             strings.xml (never in names, URLs or e-mail addresses);
             nl: hoofdwachtwoord -> Multipass-wachtwoord
  sso        the single sign-on screen fills in Vaultwarden's fixed identifier
             and starts the Kikaron sign-in by itself
  server     a fresh install is set to the self-hosted
             https://multipass.kikaron.com instead of bitwarden.com (US)
  icon       the adaptive launcher icon from kikaron/icon.svg (Kikaron's
             multipass/brand/icon-square.svg): its tile colour as the background,
             its white glyph as a vector foreground and monochrome layer
  signing    a "kikaron" release signing config, read from the environment
             (MULTIPASS_KEYSTORE, MULTIPASS_KEYSTORE_PASSWORD, MULTIPASS_KEY_ALIAS,
             MULTIPASS_KEY_PASSWORD) - the keystore never lives in the repo
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import screens  # noqa: E402  (the start and unlock screens)

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent

APP_ID = 'com.kikaron.multipass'
STORE_NAME = 'Kikaron Multipass'
PRODUCT = 'Multipass'
SERVER = 'https://multipass.kikaron.com'


def fail(msg):
    sys.exit('brand.py: ' + msg)


def edit(path, old, new, count=1):
    """Replace exact text; already-branded text counts as done."""
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    # done already: the branded text is there (it may itself contain the anchor,
    # so this is checked first - otherwise every run would add another copy)
    if new in text:
        return
    if old not in text:
        fail('anchor moved in %s:\n  %s' % (path, old))
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# ---- identity -----------------------------------------------------------------------
def brand_identity():
    edit('app/build.gradle.kts', 'applicationId = "com.x8bit.bitwarden"', 'applicationId = "%s"' % APP_ID)
    # the version code rises with every build (kikaron/build.sh sets it from the
    # date): Play and a side-loaded copy must never go backwards
    edit('app/build.gradle.kts', 'versionCode = libs.versions.appVersionCode.get().toInt()',
         'versionCode = (System.getenv("MULTIPASS_VERSION_CODE") ?: libs.versions.appVersionCode.get()).toInt()')
    for flavour, name in [('release', STORE_NAME), ('beta', STORE_NAME + ' Beta'), ('main', STORE_NAME + ' Dev')]:
        path = 'app/src/%s/res/values/strings_non_localized.xml' % flavour
        text = (ROOT / path).read_text(encoding='utf-8')
        new = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)', r'\g<1>%s\g<2>' % name, text)
        if new == text and name not in text:
            fail('app_name not found in ' + path)
        (ROOT / path).write_text(new, encoding='utf-8')
    edit('app/src/main/AndroidManifest.xml', 'android:label="Bitwarden Bridge"', 'android:label="%s Bridge"' % PRODUCT)


# ---- strings --------------------------------------------------------------------------
# Strings in which "Bitwarden" is not this app but something of Bitwarden's: another
# Bitwarden app (Authenticator), its file format, its website / help centre, its
# newsletter or its subscriptions. Renaming those would present Bitwarden's things as
# ours, so they keep the name (a factual mention, which the trademark allows).
KEEP_BITWARDEN = [
    'bitwarden_help_center',
    'learn_more_about_how_to_use_bitwarden_on_the_help_center',
    'learn_more_about_using_passkeys_with_bitwarden',
    'learn_about_organizations_description_long',
    'go_to_bitwarden_com_download_to_integrate_bitwarden_into_browser',
    'get_emails_from_bitwarden_for_announcements_advices_and_research_opportunities_unsubscribe_any_time',
    'manage_your_subscription_plan_in_the_bitwarden_web_app',
    'import_bitwarden_unsupported_format',
    'import_from_bitwarden',
    'secure_your_accounts_with_bitwarden_authenticator',
    'learn_more_about_how_to_use_bitwarden_authenticator_on_the_help_center',
    'data_backup_message',
    'download_bitwarden_card_title',
    'sync_with_bitwarden_app',
    'sync_with_the_bitwarden_app',
    'shared_codes_error',
    'account_synced_from_bitwarden_app',
    'copy_to_bitwarden_vault',
    'save_to_bitwarden',
    'choose_save_location_message',
    'bitwarden_tools',
    'manage_your_logins_from_anywhere_with_bitwarden_tools',
]
WORD = re.compile(r'(?<![/.@\w])Bitwarden(?!\.com|\.net|\.eu|\w)')
VALUE = re.compile(r'(<(string|item)\b[^>]*>)(.*?)(</\2>)', re.S)
NL = re.compile(r'[Hh]oofdwachtwoord')


def brand_strings():
    changed = 0
    for path in ROOT.glob('**/src/*/res/values*/strings*.xml'):
        if '/build/' in str(path):
            continue
        text = path.read_text(encoding='utf-8')
        nl = path.parent.name in ('values-nl', 'values-nl-rNL', 'values-nl-rBE')

        def value(m):
            if re.search(r'name="(%s)"' % '|'.join(KEEP_BITWARDEN), m.group(1)):
                return m.group(0)
            body = WORD.sub(PRODUCT, m.group(3))
            if nl:
                body = NL.sub('Multipass-wachtwoord', body)
            return m.group(1) + body + m.group(4)
        new = VALUE.sub(value, text)
        if new != text:
            path.write_text(new, encoding='utf-8')
            changed += 1
    if not changed and not any('Multipass' in p.read_text(encoding='utf-8')
                               for p in ROOT.glob('ui/src/main/res/values/strings.xml')):
        fail('no strings were branded')
    return changed


# ---- server ---------------------------------------------------------------------------
def brand_server():
    path = 'data/src/main/kotlin/com/bitwarden/data/repository/util/EnvironmentUrlDataJsonExtensions.kt'
    edit(path,
         'this?.toEnvironmentUrls() ?: Environment.Prod.Us',
         'this?.toEnvironmentUrls() ?: Environment.SelfHosted(\n'
         '        // KIKARON: a fresh install talks to Multipass, not bitwarden.com\n'
         '        environmentUrlData = EnvironmentUrlDataJson(base = "%s"),\n'
         '    )' % SERVER)


# ---- single sign-on ----------------------------------------------------------------------
# Vaultwarden has no organisations for SSO and expects its fixed identifier (the
# same one iOS and the web use). The sign-on screen fills it in and starts the
# Kikaron sign-in by itself, once, as soon as it has looked for a verified domain.
SSO_IDENTIFIER = '00000000-01DC-01DC-01DC-000000000000'
SSO_VM = 'app/src/main/kotlin/com/x8bit/bitwarden/ui/auth/feature/enterprisesignon/EnterpriseSignOnViewModel.kt'


def brand_sso():
    edit(SSO_VM,
         '                        orgIdentifierInput = authRepository.rememberedOrgIdentifier.orEmpty(),\n'
         '                    )\n'
         '                }\n',
         '                        orgIdentifierInput = authRepository.rememberedOrgIdentifier\n'
         '                            ?: KIKARON_SSO_IDENTIFIER,\n'
         '                    )\n'
         '                }\n'
         '                // KIKARON: straight on to the Kikaron sign-in\n'
         '                handleLogInClicked()\n')
    edit(SSO_VM,
         '                    orgIdentifierInput = authRepository.rememberedOrgIdentifier.orEmpty(),\n'
         '                )\n'
         '            }\n'
         '            return\n',
         '                    orgIdentifierInput = authRepository.rememberedOrgIdentifier\n'
         '                        ?: KIKARON_SSO_IDENTIFIER,\n'
         '                )\n'
         '            }\n'
         '            // KIKARON: straight on to the Kikaron sign-in\n'
         '            handleLogInClicked()\n'
         '            return\n')
    p = ROOT / SSO_VM
    text = p.read_text(encoding='utf-8')
    if 'KIKARON_SSO_IDENTIFIER =' not in text:
        p.write_text(text.rstrip('\n') + "\n\n// KIKARON: Vaultwarden's fixed single sign-on identifier\n"
                     'private const val KIKARON_SSO_IDENTIFIER = "%s"\n' % SSO_IDENTIFIER, encoding='utf-8')


# ---- icon -----------------------------------------------------------------------------
def brand_icon():
    svg = (HERE / 'icon.svg').read_text(encoding='utf-8')
    tile = re.search(r'<rect[^>]*fill="(#[0-9A-Fa-f]{6})"', svg)
    group = re.search(r'<g transform="translate\(([\d.\-]+) ([\d.\-]+)\) scale\(([\d.]+)\) '
                      r'translate\(([\d.\-]+) ([\d.\-]+)\)"([^>]*)>(.*?)</g>', svg, re.S)
    if not tile or not group:
        fail('kikaron/icon.svg is not the shape make_icon.py writes')
    cx, cy, scale, tx, ty, attrs, body = group.groups()
    paths = re.findall(r'<path d="([^"]+)"', body)
    def attr(name, default):
        m = re.search(r'%s="([^"]+)"' % name, attrs)
        return m.group(1) if m else default
    # the tile's glyph fills an iOS square; an adaptive icon shows only the middle
    # 72 of its 108 dp, so the glyph is scaled to keep the same share of what shows
    s = float(scale) * 72.0 / 108.0
    fill_opacity = attr('fill-opacity', '1')
    stroke_width = attr('stroke-width', '1.7')

    def vector(colour):
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- KIKARON: generated by kikaron/brand.py from kikaron/icon.svg - do not edit -->',
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               '    android:width="108dp" android:height="108dp"',
               '    android:viewportWidth="120" android:viewportHeight="120">',
               '    <group android:translateX="%s" android:translateY="%s">' % (cx, cy),
               '        <group android:scaleX="%.4f" android:scaleY="%.4f">' % (s, s),
               '            <group android:translateX="%s" android:translateY="%s">' % (tx, ty)]
        for d in paths:
            out.append('                <path android:pathData="%s" android:fillColor="%s" '
                       'android:fillAlpha="%s" android:strokeColor="%s" android:strokeWidth="%s" '
                       'android:strokeLineCap="round" android:strokeLineJoin="round"/>'
                       % (d, colour, fill_opacity, colour, stroke_width))
        out += ['            </group>', '        </group>', '    </group>', '</vector>', '']
        return '\n'.join(out)

    for flavour in ('main', 'beta', 'release'):
        res = ROOT / 'app/src' / flavour / 'res/drawable'
        if not res.exists():
            continue
        (res / 'ic_launcher_foreground.xml').write_text(vector('#FFFFFF'), encoding='utf-8')
        (res / 'ic_launcher_monochrome.xml').write_text(vector('#FFFFFF'), encoding='utf-8')
    path = ROOT / 'app/src/main/res/values/ic_launcher_background.xml'
    text = path.read_text(encoding='utf-8')
    new = re.sub(r'(<color name="ic_launcher_background">)#[0-9A-Fa-f]{6,8}(</color>)',
                 r'\g<1>#FF%s\g<2>' % tile.group(1)[1:].upper(), text)
    if new == text and tile.group(1)[1:].upper() not in text.upper():
        fail('ic_launcher_background not found')
    path.write_text(new, encoding='utf-8')


# ---- signing --------------------------------------------------------------------------
SIGNING = '''
        // KIKARON: the release key, from the environment (kikaron/build.sh) - never
        // a file in the repo
        create("kikaron") {
            System.getenv("MULTIPASS_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("MULTIPASS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MULTIPASS_KEY_ALIAS")
            keyPassword = System.getenv("MULTIPASS_KEY_PASSWORD")
        }
'''


def brand_signing():
    edit('app/build.gradle.kts',
         '            storePassword = "android"\n        }\n    }',
         '            storePassword = "android"\n        }' + SIGNING.rstrip('\n') + '\n    }')
    edit('app/build.gradle.kts',
         '        release {\n            isDebuggable = false',
         '        release {\n'
         '            if (System.getenv("MULTIPASS_KEYSTORE") != null) {\n'
         '                signingConfig = signingConfigs.getByName("kikaron")\n'
         '            }\n'
         '            isDebuggable = false')


def main():
    brand_identity()
    n = brand_strings()
    brand_server()
    brand_sso()
    screens.brand_screens(ROOT, HERE, edit, fail)
    screens.brand_splash(ROOT, HERE, edit, fail)
    screens.brand_notices(ROOT, HERE, edit, fail)
    screens.brand_about(ROOT, HERE, edit, fail)
    brand_icon()
    brand_signing()
    print('branded: identity, %d string files, server, icon, signing' % n)


if __name__ == '__main__':
    main()
