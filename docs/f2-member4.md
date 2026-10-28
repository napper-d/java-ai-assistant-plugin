# F2 Member 4 local implementation

Status: fixed local samples only. No LLM connected. Shared contracts and platform versions unchanged.

## Try in the development IDEA

1. Restart runIde after rebuilding the plugin. Open a disposable Java project, and copy examples/f2/SumDemo.java and GreetingDemo.java into it.
2. Open SumDemo.java, right-click inside the editor and choose **F2: Review Fixed Sample Change (No AI)**.
3. Enter `Fix sum to add both numbers`. The instruction is displayed for the demo; it does not drive a model.
4. Inspect the built-in read-only Diff: subtraction becomes addition.
5. Cancel, Escape, or close the review: original editor text and disk content must stay unchanged.
6. Repeat and choose **Approve and Apply**: addition is saved. Focus editor and Ctrl+Z: subtraction returns. Save after undo.
7. Repeat with GreetingDemo.java and `Extract name normalization into a method`. The fixed sample extracts normalizeName without changing behavior.
8. Change the target while waiting/reviewing: applying must fail rather than overwrite it. Repeat with a read-only target.
9. An arbitrary source file is rejected by the sample generator. No arbitrary-code transformations are claimed.

## Implementation and handoff

SampleModifyAction captures a full Document snapshot only after explicit input. ValidatedModificationService invokes CodeGenerationService off EDT, validates proposal/request/target, and shows DiffReviewDialog on EDT. Close/Cancel causes no write. The target must resolve to a real file inside the project; model paths do not authorize a different file. The apply command checks the captured stamp before review, and rechecks original text, file identity and writability inside the apply command, preserves IDE encoding and supports Undo. Disk changes during review are checked after refreshing the VFS.

Member 3 can inject a real CodeGenerationService into ValidatedModificationService(Project, CodeGenerationService). Member 1 should coordinate the production entry/lifecycle; this separate sample editor action does not change ordinary chat. Generation deadline is 60 seconds. The service returns APPLIED only after save succeeds, CANCELLED after closing/cancelling; exceptional failures use ServiceFailure. The initial implementation is single-file/full-text only.

## Checks and limitations

`tools/F2ContractCheck.java` exercises production validation and both fixed generators without starting an IDE. Compile it with JDK 21 and the plugin SDK classpath together with production sources. It does not prove dialog, disk save, Undo or lifecycle behavior in a running IDE. Manual acceptance above is required before declaring F2 complete. Real AI generation and final UI integration remain pending.

No report or presentation draft is included.

## Verification on 2026-10-28

- JDK 21 compilation against cached IDEA 2025.2.4 SDK: passed.
- tools/check-f2.ps1: 11 contract checks passed.
- Gradle buildPlugin, verifyPluginProjectConfiguration, verifyPluginStructure: passed (2m 1s).
- Platform emitted searchable-settings/network warnings; the build exited successfully.
- IDE 2025.2.4 manual checks passed: sample action opens, built-in side-by-side read-only Diff renders, Escape returns CANCELLED and preserves disk text, approval saves addition, Ctrl+Z and save restore subtraction. Read-only/changed-file rejection and project-disposal edge cases still need manual acceptance.
- Changes are local and not committed/pushed.

Generated Bug fix and refactor outputs also compile with JDK 21; 3 behavior checks passed. No report or talk script created.
