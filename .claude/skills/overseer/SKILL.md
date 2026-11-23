---
name: overseer
description: >-
  How the overseer session of Ephyra works: hand issues to side sessions, follow them, merge
  their pull requests, and keep the owner informed in plain Dutch. Use this whenever a session is
  asked to "be the overseer", "pak de open issues op", "verdeel het werk", "start sessies voor
  …", "merge wat klaar is", "hoe staat het ervoor", or otherwise coordinates more than one
  session or pull request at a time, and at the start of every overseer session. Also use it
  before merging a pull request someone else wrote, before starting a side session, and when a
  side session reports back.
---

# Overseer

The overseer does not write most of the code. It decides what gets done in which session,
checks what comes back, merges, and tells the owner where things stand. The owner often reads
along on a phone, away from the computer: every rule below about messages, dialogs and devices
comes from that.

## Talking to the owner

- Short, in Dutch, plain words. Each message stands on its own: someone who reads only that one
  message knows what it is about.
- Order: first the result (what is merged, what works), then what is not tested or not done,
  then what is needed from the owner, if anything. One question at a time, with your preference.
- A side session's report is not proof. Look at the PR, the diff, the checks or the device
  yourself before you tell the owner something is done.

## Start of a session

1. Read `CLAUDE.md` and this skill.
2. List the open pull requests and what each one misses (checks, reviews, unanswered comments,
   behind `main`). Check that `main` is green.
3. Look at the other sessions (the ListAgents tool, the linked sessions) and ask the running ones for
   their state before starting anything. Never start a second session on the same work.
4. Check usage (`get_usage`) before deciding how much to start.

## What needs the owner, and what does not

- Picking up bugs: go ahead.
- New features: the owner's choice. Propose, don't start.
- A bug found and confirmed but not fixed in the current work: file an issue yourself.
- An issue that comes from reading code, not from seeing it happen: its first step is
  "reproduce it on an emulator before changing code".
- When you are unsure whether something belongs in the repository or only in your notes, ask
  the owner, with your preference.

## Handing out work

- Large pieces go to a session of their own on the strongest model in use, which gives the
  executing work to agents (a cheaper model where the work is clear). Something that matters (an
  architecture choice, a review of a risky change) gets a second look from a stronger model than
  the one that wrote it.
- The brief to a side session: use `start-work` for the area guide and the brief template; say
  what done looks like, what has to be tested and how, whether it merges itself or reports first
  (default: it does not merge), that it sends one final report, and that it reports what the
  area guide missed. It asks its questions to the overseer, not in its own session.
- A side session never opens a browser preview and never asks for a permission or a choice in a
  dialog: the owner, away from the screen, does not see it, and the session stands still for
  hours. Everything goes through the overseer.

## Merging

Merge a pull request when all of these hold:

1. CodeRabbit has reviewed it (and Sourcery, when it has budget). A review that is still running
   counts as not reviewed.
2. Every inline comment has an answer in its thread. Count the unanswered ones as a step of its
   own, right before the merge, not in the same command: a comment can arrive late.
3. The checks are green.
4. When the branch is behind `main`, test it merged with `main` first: `./gradlew ktfmtCheck
   testDebugUnitTest` (for docs: `check-docs.py` and `./gradlew :architecture:test`). Two pull
   requests can break each other without a text conflict.
5. A change in the player, a Room migration or the setup flow has an independent review on the
   PR. A change to stored data (Room, preferences, files) has an upgrade check: install the old
   build, upgrade, and see the data survive.

Merge with a merge commit (`CLAUDE.md`), and say what you merged in one line to the owner.

## Betas

- A merged `feat/` branch publishes a beta; `refactor/` and `docs/` do not.
- After merging a `fix/` branch in a series, cancel the Beta run of that merge commit by hand and
  check that it really stopped: the owner wants one beta at the end of a series, not one per fix.
- `[skip beta]` in the merge commit for tests, CI, and docs that touch files outside the docs
  paths (a workflow, `.github/`, a test source).
- At the end of a series: one beta, which the owner tests.

## Devices

- Emulators by default (the `test-devices` skill). Three at a time is fine. Each session uses its
  own test user on the test server (the `test-server` skill).
- The owner's phone and TV box only when the owner says so. The box belongs to the household:
  volume low, hand it back at once when asked, and leave the stable app and the beta on it alone.
- Never two sessions on one device.

## Usage

- Check `get_usage` between tasks.
- At most two agents per session at a time; let them wait in the foreground and send one report.
- Start nothing new above about 85% of the 5-hour window unless the owner says it may. The weekly
  window of the strongest model is the scarcest: give executing work to a cheaper model.

## Things that cost time before

- A dialog or permission question in a side session that the owner did not see.
- Parallel pull requests that broke each other without a text conflict (point 4 of Merging).
- An agent that stopped to "wait for CI" in the background and never came back: wait in the
  foreground.
- CRLF line endings in commits written by agents: check before pushing.
- A full disk from `node_modules` and Gradle `build/` folders in agent worktrees: remove them when
  the agent is done.
- The test server in WSL that stops when no WSL process is open.
- A side session's "done" that was not done: check it yourself.
