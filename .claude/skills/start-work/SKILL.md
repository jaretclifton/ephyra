---
name: start-work
description: >-
  Find where and how to work on a part of Ephyra before changing it: the area guide, the files,
  the ports, the architecture rules, the tests and checks to run, known pitfalls, and a brief for a
  side session that is ready to send. Use this at the start of any task in this repo that names a
  feature, a bug or a part of the app ("I want to work on subtitles", "subtitles jump on mpv", "a
  download doesn't resume", "add a setting for X", "ik wil aan downloads werken", "waar zit de
  login?"), before reading code, and whenever someone asks where something is, which rules apply
  to it or how to test it. Also use it to write the brief when handing an issue to another session
  or agent.
---

# Start work

`docs/areas.yaml` is the map of the app: one entry per area with its paths, ports, docs,
checklist, test data, rules, tests, pitfalls and decisions. Areas with `status: guide` also have
a guide in `docs/areas/<id>.md` (one or two screens). This skill turns a request into the right
entry and guide, so you read one guide instead of the tree. The same files are the development
site (https://midasvo.nl/ephyra/dev/), so what you find here is what a person reads there.

## Steps

1. **Find the area.** Read `docs/areas.yaml` and match the request against each entry's
   `keywords`, `title` and `summary` (keywords are English and Dutch). A request can touch two
   areas, such as "subtitles of a download" (subtitles-and-tracks and downloads): take the area
   of the behaviour that is wrong first, and name the second. Match on the thing more than the
   verb: "een download hervat niet" is downloads, though "hervatten" also means resuming playback.
   Read the whole entry; if a tool cuts the output short, read the file itself. If nothing matches, say so and use
   the module list in `CLAUDE.md` and `docs/architecture/modules.md` instead.
2. **Read the guide**, if the area has one: `docs/areas/<id>.md`. For a `row` area there is no
   guide yet: use its `paths` and `docs`, and say that the area has no guide.
3. **Check the guide against `main`.** The code is right where they differ. Each guide says at
   which commit it was written. Run `git fetch -q origin` and compare the area's paths since then
   (`git diff --stat <commit> origin/main -- <paths> docs/areas`): a change there may have moved
   what the guide describes. Confirm with
   `ls` that each path you are about to name exists. A stale path in a guide is a finding: note
   it for the report.
4. **Answer the request** in the shape below. For a bug, look in the area's `pitfalls` and
   `decisions` first: many bugs here have been seen before, and an open issue there is often the
   bug itself. Then search the issues in English words, whatever the request's language
   (`gh issue list --repo midasvo/ephyra --search "<words>" --state all`); the search is loose,
   so read what it returns. When an open issue matches, lead with it: "This is #912." When the
   request is vague ("ondertitels verspringen": in time, on screen, or the track itself?), say
   which readings there are and make reproducing it the first step of the brief.
5. **Write the brief** if the work should go to a side session or agent (see below).

## The answer

Keep it short; link, don't copy, what the guide already says.

```
Area: <title> (<id>) — guide: docs/areas/<id>.md   [or: no guide yet]
Also touches: <other area, if any>

Where: <3–6 paths, most likely first, each with one line on why>
Ports: <the ports and stores this goes through>
Rules: <the Konsist rules that apply, R-number and one line each>; plus the project rules that
       apply (mpv checklist for player PRs, translations for strings, independent review for
       the setup flow, …)
Test: <the unit test command(s) from the guide>; on a device: <test items and users from the
      guide>
Before the PR: <the commands: ktfmtFormat, the module tests, :architecture:test, the checklist>
Seen before: <matching pitfalls and issues, with numbers>
```

## The brief for a side session

Write it so a session with no context can start. It names the issue, the area guide, the files
to start from, what done looks like, and the checks. Use this template:

```
<Issue title> (midasvo/ephyra#<n>, or: no issue yet).

Read first: CLAUDE.md, docs/areas/<id>.md<, docs/... from the guide>.
   [For an area without a guide: CLAUDE.md, the `<id>` entry of docs/areas.yaml and its docs.]
The problem: <one paragraph: what happens, what should happen, how to reproduce it>.
Start in: <paths>. It goes through <ports>; rules <R-numbers> apply.
Seen before: <pitfalls and issues>.
Done when: <behaviour>, with a unit test that fails before the fix<, and the device check>.
Checks before the PR: <commands>. Branch fix/<n>-<slug> (or feat/, refactor/), conventional
commits, PR title names the issue. <Anything else from CLAUDE.md that applies: the mpv
checklist on both backends, translations, an independent review.>
Report: the PR number, what you checked yourself, and what is still open.
```

## When the map is wrong

If an entry or guide names a path that does not exist, misses the file where the work really
is, or gives a command that fails, fix `docs/areas.yaml` or the guide in the same PR as the work
(or say it in the report). The map is only useful while it is true.

`python3 .claude/skills/start-work/scripts/check-docs.py` finds the stale paths, names, rule numbers,
Gradle tasks and test names for you (a second, no build); CI runs it on every pull request. Run it
after moving or renaming code that a doc names, and after editing the map or a guide.
