# Spotify AutoPlaylist — Codex Project Instructions

- Read `docs/README.md` and `docs/08-IMPLEMENTATION.md` first; read the relevant requirement documents before implementing any task.
- For UI, inspect the **exact original** PNG in `design/reference/`, then follow `docs/06-UI-UX.md`, `docs/UI-VISUAL-REFERENCE.md`, and `docs/UI-VISUAL-DEVIATIONS.md`. A screenshot is a visual goal, not a literal functional specification.
- Implement one requested task at a time; never expand scope without a documented requirements/test update.
- Do not insert a mockup PNG as a full-screen background or use its sample metrics, artists, dates, artwork, profile or sync values in production. Render native accessible Jetpack Compose widgets.
- Spotify API, account isolation, counting semantics and persistence must follow `docs/02-BUSINESS-RULES.md`, `docs/04-SPOTIFY-API.md`, `docs/05-DATABASE.md`.
- No secrets, access tokens, refresh tokens, authorization codes or personal history in logs, screenshots or repository.
- Verify remote playlist membership before displaying `Added`; write retries must reconcile first.
- Prefer pure JVM unit tests and fake repositories for business logic. GitHub Actions must compile/test Android; only claim PASS with observed results.
- Report modified files, tests, CI/build evidence, visual differences and known blockers at the end of every task.
