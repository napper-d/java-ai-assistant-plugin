# Team collaboration

This repository contains the Group C IntelliJ plugin (F0 + F2). The Gradle project
is the repository root. The parent directory in the original author's workspace
contains personal stage plans and the assignment PDF; those files are outside this
repository and are not included when cloning it.

## Start here

1. Read [README](README.md) for JDK 21, the IDEA 2025.2.4 target, build and run steps.
2. Read [architecture and ownership](docs/architecture.md) and
   [service contracts](docs/interfaces.md) before implementing an adapter.
3. Build the current baseline, then run the sample chat window. The current answers
   are fixed examples. The RAG adapter can be exercised independently using
   [the RAG guide](docs/rag-search.md); real chat integration and modification services are pending.
4. Confirm the shared contracts with the affected members before changing them.

Use the checked-in Gradle Wrapper and preserve the target versions. On Windows use
`gradlew.bat`; on macOS/Linux, `bash ./gradlew` also works when the cloned wrapper
has no executable permission. The headless UI/lifecycle helper is currently a
PowerShell script; other systems can still run the Gradle build and manual IDE checks.

## Ownership and first integration tasks

| Member | Area | Next deliverable |
| --- | --- | --- |
| 1 | `ui`, `action`, `context`, `application`; shared `model` contracts | IDE acceptance of Stage 4; integrate real adapters and preserve request lifecycle behavior |
| 2 | `rag` package; `LocalKnowledgeSearch` implementation | Validate a real provider/corpus and thresholds; hand off ingestion/search APIs to Members 1/3 |
| 3 | Future `llm` package; `ChatService` and `CodeGenerationService` implementations | Cited answers, general-knowledge disclosure, safe failures and cancellable transport; generation returns proposals only |
| 4 | Future `modification` package; `ModificationService` implementation | Confirm the single-file/full-text handoff; built-in Diff review, explicit approval, stale-file checks and apply/cancel results |

The shared values contain no Swing or IntelliJ editor objects. CodeContext uses
inclusive one-based line numbers and exact selected text. Ordinary chat captures
only the editor selection. Full-file snapshots belong to the future explicitly
invoked modification flow. The F2 contracts remain a handoff proposal awaiting
agreement with Members 3–4.

## Branches and review

The existing integration branch is `master`. Keep it as the shared baseline unless
the team explicitly agrees to rename it. Use one short-lived branch per change,
for example `feature/rag-search`, `feature/llm-chat`, `feature/diff-review`, or
`fix/request-lifecycle`. These are suggested names, not branches already created.

1. Start from an up-to-date integration branch with a clean working tree.
2. Make a focused change in your branch. Avoid unrelated formatting or build upgrades.
3. Verify the affected behavior and describe what is still mocked or untested.
4. Open a pull request against the integration branch. Request review from the
   relevant module owner; changes to shared contracts need the affected members.
5. Merge after review and verification, then update your local integration branch.

Use GitHub issues to record module tasks and unresolved contract decisions. A task
should name its owner, expected input/output and acceptance behavior. Preserve
meaningful stage history; agree before rewriting a shared branch or changing its
merge strategy. Do not mark a stage accepted on behalf of another member.

## Verification before a pull request

Run the applicable checks and record the actual result in the PR:

```powershell
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
.\tools\check-chat-ui.ps1
```

Use `--offline` only after the necessary dependencies have been cached. The
PowerShell helper needs a local JDK 21 and the extracted target SDK; see README for
`-IdeHome`. The checks cover Swing behavior and request lifecycle but do not start
IntelliJ. Changes to editor actions, Tool Window wiring, disposal or Diff/apply
behavior also need a manual IDE check. Do not claim manual verification from a
headless test alone.

## Repository contents and local settings

Keep source, the Wrapper, shared run configurations, documentation and tests in
Git. Generated `build/` output, IDE sandboxes, caches and per-user settings are
ignored. Distribute a plugin ZIP separately when a teammate needs installation;
the ZIP is not a substitute for the repository or assignment submission package.

Keep model keys and local configuration outside version control. The configuration
mechanism for the real provider is still to be agreed; no API-key setting is
implemented by these preparation files. Share placeholder examples only. Course
materials and generated indexes are not bundled. The default local data directory
is `.course-ai/` under the explicitly selected project root, ignored by this
repository. See [the RAG guide](docs/rag-search.md) for import/rebuild commands,
configuration and integration limits. Other user projects should ignore their own data directory.

## Initial GitHub publication

The repository owner creates the GitHub repository manually. For importing this
existing history, create an empty repository without an initial README, gitignore
or license, then use its actual URL to connect the existing local repository.
Commit the reviewed local changes before the first push so teammates receive the
Stage 4 fixes as well as the earlier stage commits. No reinitialization or copying
files into a new local repository is needed.

Invite the other members as collaborators after publishing. GitHub's official
instructions cover [importing an existing repository](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)
and [inviting collaborators](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/inviting-collaborators-to-a-personal-repository).
