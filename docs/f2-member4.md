# F2 Member 4 local implementation

Status: member-three LLM branch merged locally. A separate real-model editor action is connected to F2; fixed offline samples remain available. Shared contracts and platform versions are unchanged. Real-provider acceptance is pending.

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

LiveModifyAction injects member-three LlmCodeGenerationService into ValidatedModificationService(Project, CodeGenerationService). Live generation uses the configured member-three deadline. Cancellation propagates to generation and closes the review; client and generator close on completion or project closure. Ordinary chat is unchanged. The service returns APPLIED only after save succeeds, CANCELLED after closing/cancelling; exceptional failures use ServiceFailure. The implementation is single-file/full-text only.

## Real model entry

1. Rebuild and restart runIde. Open a Java file inside the test project.
2. Right-click the editor, choose **Modify Java Code with LLM...**, and enter a bug-fix or refactoring instruction.
3. Select the directory containing your model `.env`. Follow docs/llm-services.md: LLM_PROVIDER=openai-compatible, LLM_BASE_URL, LLM_MODEL and LLM_API_KEY are required. The complete file and instruction are sent to this endpoint. Never commit credentials.
4. Inspect the read-only Diff. Cancel keeps the original; **Approve and Apply** saves the proposal. Ctrl+Z in the editor supports undo.
5. The background task is cancellable. Changed or read-only targets must not be overwritten.

This explicit development configuration does not reuse the RAG embedding key. No real `.env` was found in the repository during integration, and no real-provider call was made. Final shared settings/Password Safe UI assembly needs coordination with Member 1.

## Checks and limitations

`tools/check-f2.ps1` exercises production validation and both fixed generators without starting an IDE. `tools/check-f2-llm.ps1` runs member-three tests and the local HTTP-to-F2 integration tests without model credentials. Neither proves dialog, disk save, Undo or lifecycle behavior in a running IDE. Manual acceptance above is required before declaring F2 complete. Real-provider acceptance and final shared settings integration remain pending.

No report or presentation draft is included.

## Verification on 2026-10-28

- JDK 21 compilation against cached IDEA 2025.2.4 SDK: passed.
- tools/check-f2.ps1: 11 contract checks passed.
- Gradle buildPlugin, verifyPluginProjectConfiguration, verifyPluginStructure: passed (2m 1s).
- Platform emitted searchable-settings/network warnings; the build exited successfully.
- IDE 2025.2.4 manual checks passed: sample action opens, built-in side-by-side read-only Diff renders, Escape returns CANCELLED and preserves disk text, approval saves addition, Ctrl+Z and save restore subtraction. Read-only/changed-file rejection and project-disposal edge cases still need manual acceptance.
- Work is saved on local branch codex/f2-llm-integration; no remote push was performed.

Generated Bug fix and refactor outputs also compile with JDK 21; 3 behavior checks passed. No report or talk script created.

## Member-three integration verification (2026-10-28)

- Saved the original local F2 work as commit 697b5c0, then merged origin/feature/member3-llm-services (be9224f) without conflicts on codex/f2-llm-integration.
- Full Gradle test suite: 117 tests, zero failures/errors. Includes 2 new HTTP generation/F2-validation and safe-failure integration tests.
- Standalone member-three checks: 21 passed; tools/check-f2-llm.ps1 additionally passes both new integration tests offline, using a loopback HTTP fixture and placeholder credentials only.
- Final Gradle buildPlugin, verifyPluginProjectConfiguration and verifyPluginStructure passed. Platform settings-index generation emitted network/shutdown warnings but did not fail the build.
- Closed the old runIde test window to release plugin JAR locks before packaging. Launch runIde again to load the new plugin.
- New live action and cancellation-window behavior compile successfully; their full manual IDE/provider acceptance is still pending. Earlier fixed-sample manual checks above do not prove the live entry works with a real provider.
- Remaining team dependencies: chosen provider/base URL/model/key, real-provider bug-fix and refactoring acceptance, and Member 1 coordination of final settings/Password Safe and shared UI. Ordinary chat remains in sample mode.
