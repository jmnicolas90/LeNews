# 22 — A FreshRSS debug account on rss.lan for the agent to test against

Type: task
Status: open
Blocked by: —

## Question

The user's personal FreshRSS account and their phone's Readrops database are off limits, full stop. What the agent may use instead is a debug account on the user's own server, `https://rss.lan` (192.168.101.2, reachable from this machine on the VLAN and from the phone over VPN; the Google Reader endpoint `/api/greader.php` answers). This ticket sets it up so tickets 14, 15 and 16 can verify against a real FreshRSS.

**Human part (checklist for the user):**
1. In FreshRSS on `rss.lan`, create a second user for LeNews development, and set its API password (Profile → API management). Do not reuse the personal account's credentials.
2. Subscribe it to a set of feeds that together produce a high daily volume, so the store grows like the real one does; a few hundred articles a day is the target, the exact list is the user's choice. A handful of busy feeds (news wires, Hacker News firehose, a few Reddit RSS feeds) does it.
3. Confirm the Caddy local-authority **root** certificate is installed as a user CA on the phone (Settings → Security → Encryption & credentials), which is what lets any app that opts into user-CA trust reach `rss.lan`.
4. Put the credentials in `local.properties` (gitignored) using the keys the build already reads for the debug build's login autofill: `debug.freshrss.url=https\://rss.lan`, `debug.freshrss.login=<user>`, `debug.freshrss.password=<api password>`. Never paste them anywhere else.

**Agent part:** verify from this machine that the account logs in over the Google Reader API (`ClientLogin`, then `user-info`), record the user name and the subscription count, and check that `local.properties` is indeed ignored (`git check-ignore local.properties`). Do not print the password anywhere, including this ticket.

**Done when** `local.properties` holds the three keys, a curl login against `rss.lan` with them returns an `Auth` token, and the debug build autofills the login screen with them. The answer records the account name and the number of feeds, nothing more.
