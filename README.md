# LeNews

A FreshRSS client for Android, for one account and a lot of articles.

This README is a placeholder. Ticket 08 of the appropriation map writes the real
one: what LeNews is, how to build it, where it comes from and what it owes
upstream.

Upstream's badges, store links, screenshots and donation section have been
removed; LeNews has none of those relationships. GitHub issues are the only
contact channel: <https://github.com/jmnicolas90/LeNews/issues>.

## Licence

This project is released under the GPLv3 licence.

## Develop

To autofill the login form on a debug build, fill the project's
`local.properties`:

```properties
debug.freshrss.url=https\://<your_instance>
debug.freshrss.login=<login>
debug.freshrss.password=<password>
```
