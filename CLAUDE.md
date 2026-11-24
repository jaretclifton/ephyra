# CLAUDE.md

@AGENTS.md

## Only for Claude Code

- **Skills** in `.claude/skills/` (loaded by name): `start-work` before reading code for a task (the steps under "Where to start" in `AGENTS.md`, plus the brief for a side session); `translate-strings` for every string change; `release` and `fdroid-update` for releases; `upstream-sync` for Findroid's changes; `test-server` and `test-devices` for a server or an emulator to test on; `overseer` when coordinating sessions and merging.
- A side session asks its questions to the session that started it, not in a dialog in its own session, and opens no browser preview (see the `overseer` skill).
- Facts about one machine or one owner (devices, paths, hosts, preferences) are in your memory notes, not in the repository.
- A session gets a short reminder of the steps above when it starts (`.claude/hooks/start-reminder.txt`, wired in `.claude/settings.json`).
