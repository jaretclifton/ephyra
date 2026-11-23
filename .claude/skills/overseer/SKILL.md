---
name: overseer
description: >-
  How an overseer session works in this repository: hand issues to side sessions, follow them,
  check what they deliver, merge their pull requests, and keep the owner informed. Use this
  whenever a session is asked to be the overseer, to work through the open issues, to split work
  over sessions or agents, to merge what is ready, or to report where things stand, and at the
  start of every overseer session. Also use it before merging a pull request someone else wrote,
  before starting a side session, and when a side session reports back.
---

# Overseer

The overseer does not write most of the code. It decides what gets done in which session, checks
what comes back, merges, and tells the owner where things stand. Follow the owner's own
preferences (language, tone, how often to report, how much may run at once) from their
instructions or memory; this skill holds the project's rules only.

## Start of a session

1. Read `CLAUDE.md` (or `AGENTS.md`) and this skill.
2. List the open pull requests and what each one misses (checks, reviews, unanswered comments,
   behind `main`). Check that `main` is green.
3. Look at the other sessions (the ListAgents tool, the linked sessions) and ask the running ones
   for their state before starting anything. Never start a second session on the same work.

## What needs the owner, and what does not

- Picking up bugs: go ahead.
- New features: the owner's choice. Propose, don't start.
- A bug found and confirmed but not fixed in the current work: file an issue.
- An issue that comes from reading code, not from seeing it happen: its first step is
  "reproduce it before changing code", where the code runs (an emulator for the app, a workflow
  run for CI).
- When you are unsure whether something belongs in the repository or only in the owner's notes,
  ask the owner, with your preference.

## Handing out work

- Give a large piece its own session, which hands the executing work to agents. Something that
  matters (an architecture choice, a review of a risky change) gets a second look from a
  different, preferably stronger, model than the one that wrote it.
- The brief to a side session: use `start-work` for the area guide and the brief template; say
  what done looks like, what has to be tested and how, whether it merges itself or reports first
  (default: it reports), that it sends one final report, and that it reports what the area guide
  missed.
- A side session asks its questions to the overseer, never in a dialog or permission prompt in its
  own session, and opens no browser preview: an owner who is not at that screen does not see it,
  and the session stands still until someone does.
- A side session's report is not proof. Look at the PR, the diff, the checks or the device
  yourself before you report something as done.

## Merging

Merge a pull request when all of these hold:

1. The review bots have reviewed it (`CLAUDE.md` says which and how). A review that is still
   running counts as not reviewed.
2. Every inline comment has an answer in its thread. Count the unanswered ones as a step of its
   own, right before the merge, not in the same command: a comment can arrive late.
3. The checks are green. A test that fails now and then (it has an issue) may be rerun once.
4. When the branch is behind `main`, test it merged with `main` first: `./gradlew ktfmtCheck
   testDebugUnitTest` (for docs: `check-docs.py` and `./gradlew :architecture:test`). Two pull
   requests can break each other without a text conflict.
5. A change in the player, a Room migration or the setup flow has an independent review on the
   PR. A change to stored data (Room, preferences, files) has an upgrade check: install the old
   build, upgrade, and see the data survive.

Merge with a merge commit (`CLAUDE.md`).

## Betas

- What the project does (`beta.yml`): a merge to `main` publishes a beta, except a merge of a
  `refactor/` branch, a merge commit with `[skip beta]`, and a change only to the docs paths. Use
  `[skip beta]` for tests, CI, and docs that touch files outside those paths (a workflow,
  `.github/`).
- Follow the owner's beta policy (for example one beta at the end of a series of fixes). To hold
  back a beta, cancel the Beta run of that merge commit (`gh run cancel <run>`) and check with
  `gh run view <run>` that it really stopped.

## Devices

- Emulators by default (the `test-devices` skill), each session with its own test user on the
  test server (the `test-server` skill).
- Physical devices only when the owner says so; leave them as you found them.
- Never two sessions on one device.

## Limits

Keep the account's usage limits in mind between tasks; when work would run past them, ask the
owner before starting it. Agents wait in the foreground for what they started (CI, a review) and
send one report.

## Things that cost time before

- A dialog or permission question in a side session that nobody saw.
- Parallel pull requests that broke each other without a text conflict (point 4 of Merging).
- An agent that stopped to "wait for CI" in the background and never came back.
- CRLF line endings in commits written by agents: check before pushing.
- A full disk from `node_modules` and Gradle `build/` folders in agent worktrees: remove them when
  the agent is done.
- A side session's "done" that was not done.
