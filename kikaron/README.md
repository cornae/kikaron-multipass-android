# Kikaron Multipass for Android

**Kikaron Multipass** for Android was developed using Bitwarden® open source
software: it is a modified version, made by Cornae (2026) for
[Kikaron](https://kikaron.com), of the
[Bitwarden® Android application](https://github.com/bitwarden/android). It is a client
for Multipass, Kikaron's password manager, which runs on
[Vaultwarden](https://github.com/dani-garcia/vaultwarden).

Not affiliated with or endorsed by Bitwarden, Inc. Bitwarden is a trademark or
registered trademark of Bitwarden, Inc. in the United States and/or other countries;
this app does not use it as its name or logo.

## Licence

Like the app it is based on, this is free software under the
[GNU General Public License v3.0](../LICENSE.txt), WITHOUT ANY WARRANTY. The
copyright of the original code stays with its authors (© Bitwarden Inc. 2015-2026);
the changes are © Cornae 2026, under the same licence.

The Bitwarden SDK (`com.bitwarden:sdk-android`) is dual-licensed; this app uses it
under its GPL-3.0 option and includes nothing from its `bitwarden_license`
(commercial-only) code.

## What was changed (and the date)

Branch `kikaron`, based on upstream tag `v2026.9.1-bwpm`, changed from 2026-10-05.
Every change is made by `kikaron/brand.py`, so the branded files are its output
and can be reproduced from an upstream checkout:

- identity: application id `com.kikaron.multipass`, name "Kikaron Multipass";
  "Bitwarden" as a product name replaced by "Multipass" in the app's texts (the
  copyright notice and the licence stay as they are); Dutch "hoofdwachtwoord"
  becomes "Multipass-wachtwoord"
- the default server is `https://multipass.kikaron.com`
- a start screen of its own ("Log in with Kikaron", single sign-on through
  Kikaron) and a simpler unlock screen
- Kikaron's launcher icon and splash
- the About screen names this as a modified version and links here
- release signing from environment variables (`kikaron/build.sh`)

## Building

```bash
kikaron/brand.sh          # on an upstream checkout: apply the changes
kikaron/build.sh          # JDK 21, Android SDK, a GitHub token with read:packages
```

## Following upstream

`git fetch upstream --tags`, branch from the newest `v*-bwpm` tag, check out
`kikaron/` from this branch, run `kikaron/brand.sh` and commit its output. When an
upstream file moved, `brand.py` stops with the text it could not find.
