# ADR 0002: Further towards hexagonal, with DDD only where there are rules

**Status:** Proposed (2026-11-22). Amends ADR 0001, decisions 1, D1 and D2.

## Context

ADR 0001 put the Jellyfin server behind ports and rejected tactical DDD because the domain was
thought thin. On 2026-11-22 the owner asked to go as far towards hexagonal architecture with DDD as
is sensible, without waiting for Emby, and to do #773 (opaque ids) and #774 (the repository's name)
now. [5-hexagonal-ddd.md](../5-hexagonal-ddd.md) measures the code:

- Every feature module depends on `data`, the Room adapter, because the repository interface lives
  there. Twenty files outside `data` use the DAO.
- The catalog is thin, as ADR 0001 said. User data and downloads are not: their rules are spread
  over `domain`, `data` and `core`, and "a download is finished" is decided by a file name in eight
  files.
- Ids are `UUID` in 173 files; the server type and the session are switched in two steps.

## Decision

1. **The application's ports leave the Room adapter.** The repository interface and the stores
   that features use go to their own place (Q3), with no Room. Only the apps depend on `data`, as
   only they depend on `server/jellyfin`. The build enforces it (rule R9).
2. **Room goes behind three store ports**, `ServerStore`, `DownloadStore`, `PendingUserDataStore`,
   keyed by an `AccountKey(serverId, userId)`. Rule R4's baseline goes to zero. Switching servers
   is one call that sets the type, address, token and account together.
3. **DDD where there are rules, nowhere else.** Two domain models, `PlayState` (marks, stops, the
   sync's conflict rules) and `DownloadState`; value objects for ids and `AccountKey`; one `Ticks`
   helper. No aggregates for the catalog, no new domain events, no use case per call.
4. **Opaque ids now (replaces D1).** One `ItemId` for every kind of item, plus `UserId`, `ServerId`
   and `MediaSourceId`, as value classes over `String`. For Jellyfin the string is the dashed form
   stored today, so Room needs no migration. `java.util.UUID` stays inside `server/jellyfin`
   (rule R10). Done first as a textual pass over the aliases, then as one flip.
5. **`JellyfinRepository` becomes `MediaRepository` now (replaces D2)**, with the model names per
   Q2. Packages keep `dev.jdtech.jellyfin`.
6. The order and the costs are in 5.3. Each step is one PR that leaves the app working, and each
   gain gets a rule that fails the build when it is undone.

## Questions for the owner

Answers to be recorded here.

| | Question | Recommendation | Answer |
|---|---|---|---|
| Q1 | Do T1 to T8 and T10; file T9 (a `downloads` module) for later | yes | |
| Q2 | Names: (a) the repository only, (b) also the `Findroid*` models, (c) also the packages | (b) | |
| Q3 | The repository interface in a new `application` module, or in `domain` | `application` | |
| Q4 | One `ItemId`, or one id type per kind | one `ItemId` | |
| Q5 | Device tests on emulators with throwaway users only, no check by the owner | yes | |

## Consequences

- A feature module can no longer reach Room or an adapter; a fake of one store replaces a fake of
  the whole repository in the download and sync tests.
- An Emby adapter brings its own id strings and needs no mapping table.
- The rules about played, favourite and resume positions are tested in `domain`, without Android.
- Most upstream Kotlin changes are re-applied by hand after T1, T2 and T4 (accepted, PR 844).
- About 25 more PRs, none visible to a user, apart from the fix to the server switch.
