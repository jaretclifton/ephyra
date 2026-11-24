# Security policy

## Which versions get fixes

| Version | Fixes |
|---|---|
| The [latest release](https://github.com/midasvo/ephyra/releases/latest) | Yes |
| The latest [beta](https://midasvo.nl/ephyra/guide/beta/) | Yes |
| Older releases | No: update to the latest release |

A fix is published as a new release. There are no patches for older versions.

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately:
[Security → Report a vulnerability](https://github.com/midasvo/ephyra/security/advisories/new).
Only the maintainer sees the report.

It helps to include:

- the version of the app (About shows it), the variant (libre, proprietary or TV) and the Android
  version;
- what an attacker needs (the unlocked device, the same network, an account on the server) and
  what they get;
- the steps to reproduce it.

This is a project of one maintainer, who aims to answer within a week. When the report holds, the fix goes into the next release and
the advisory is published with it, with your name if you want that.

## What counts

Ephyra stores the address of your Jellyfin servers and an access token per user on the device,
and can lock a profile with a PIN. Examples of what to report: a way to read a token or a
download from another app, a way around the profile lock, a request that goes to a host other
than the servers you added (or MDBList, when you switched on external ratings), or a way for a
server or a media file to run code in the app.

A problem in the Jellyfin server itself belongs with
[Jellyfin](https://github.com/jellyfin/jellyfin/security/policy). A problem that Findroid has too
is welcome here; it is passed on to
[Findroid](https://github.com/jarnedemeulemeester/findroid).
