# 19 — Trust the user-installed CA for rss.lan only, and nothing in cleartext

Type: task
Status: open
Blocked by: 22

## Question

Review finding (medium), `app/src/main/res/xml/network_security_config.xml:3-12`. The base configuration permits cleartext HTTP and trusts user-installed certificate authorities for every host, in every build. With Basic/token authentication, an `http://` FreshRSS URL sends credentials in the clear; global user-CA trust widens interception exposure for every image the reader loads.

The fact the fix depends on is now known (2026-09-05): the user's FreshRSS is `https://rss.lan`, on the LAN and over VPN only, behind Caddy with its **local certificate authority** — 12-hour leaf certificates issued by "Caddy Local Authority - ECC Intermediate". No public CA will ever sign `.lan`, so the phone trusts it through the Caddy root installed as a **user CA**, and an Android app only honours user CAs for hosts its network security config says so for.

Policy: `network_security_config.xml` keeps the strict default (no cleartext, system CAs only) as the base, and adds one `domain-config` for `rss.lan` that trusts `user` certificates in addition to `system`, in every build type. No cleartext anywhere, including debug. The login screen refuses an `http://` URL outright rather than warning. Do not bundle the Caddy root in the app: the user's CA is theirs to rotate, and a user-CA pin would silently break the day they re-key. The plain image client from ticket 18 stays on the base config, so a feed image from an arbitrary host never gets user-CA trust.

Verify on the emulator against the debug account (ticket 22) with the Caddy root installed as a user CA in the AVD, and negatively: the same build with the root removed fails to log in with a certificate error, and an `http://rss.lan` URL is refused before any request is made.

**Done when** the gate is green, the base config has no cleartext or user-CA exception, the `rss.lan` domain config exists, and both verifications above are recorded in the resolution.
