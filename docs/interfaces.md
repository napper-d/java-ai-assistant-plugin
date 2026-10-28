# Service Contracts — Stage 4

Member 3 update: `llm.LlmChatService` and `llm.LlmCodeGenerationService` now implement
the existing ports without changing their signatures. See [assembly and configuration](llm-services.md).
These standalone services are not yet wired into the Tool Window. The modification
review/apply implementation remains pending; the historical integration checklist
below must not be read as provider or IDE acceptance.

The Java APIs below exist in `model/`, `context/` and `service/`. Chat uses
`CompletableFuture`; the default Tool Window adapter is MockChatService, visibly
marked as sample data. No real retrieval, LLM or modification service is wired into chat.
The separate Course materials tab calls ProjectRagService through
KnowledgeBaseOperations for registry management and rebuild/reload; it displays
KnowledgeBaseSnapshot values and never turns them into ChatAnswer results.
Retrieval preview calls the same project's `search(query, 5)` and displays the
returned KnowledgeChunk/SourceReference values directly. Its settings/library
subscriptions invalidate results without automatically issuing new queries.
Its bottom composer and middle transcript reuse ChatWidgets and ConversationView.
Each question is searched independently; in-memory exchanges are presentation
history, never model context. Cancel keeps earlier exchanges; Clear removes history
and the draft. Successful empty retrieval, exceptional completion and cancellation
remain distinct and are shown as responses to their submitted question.
`rag.LocalKnowledgeSearch` implements KnowledgeSearch independently; its setup,
scores, cancellation and error mapping are documented in [the RAG guide](rag-search.md).
The F2 contracts are a concrete handoff proposal for Members 3–4, pending their
agreement and Stage 5 integration; their existence does not imply F2 is implemented.

## Shared values

| Java value | Fields / semantics |
| --- | --- |
| `CodeContext` | Project-relative path (absolute for an external file), language ID, selected text, inclusive one-based start/end lines, document modification stamp, optional bounded surrounding text. The collector supplies no surrounding text. |
| `SourceReference` | Reference ID, document name, optional page start/end or scope. At least page or scope is required. Equal start/end pages display as one page. |
| `KnowledgeChunk` | Chunk ID, source, text and score in [0, 1]. Local RAG uses original-question `(cosine + 1) / 2`, including for multi-step evidence. Explicitly expanded definitions may score below the provisional 0.75 semantic threshold; see the RAG guide. |
| `ChatRequest` | Generated request ID, question, nullable CodeContext. No conversation history is currently submitted. |
| `ChatAnswer` | Same request ID, answer, immutable sources, provenance (`COURSE_GROUNDED` / `GENERAL_KNOWLEDGE`), optional disclosure. |
| `ServiceFailure` | Request ID, category, safe user-facing message and retryability; extends Exception. |
| `ModificationRequest` | Request ID, nonblank instruction, CodeContext and original full-file text. Capturing the full file belongs only to an explicitly invoked future modification flow. |
| `CodeChange` | Project-relative path, original full-file text, proposed full-file text and summary. This is a proposal, not permission to access the path. |
| `ChangeProposal` | Proposal ID, request ID and one CodeChange. Initial scope is single-file replacement. |
| `ModificationResult` | Request ID, proposal ID, outcome (`APPLIED` / `CANCELLED`) and message. Failure completes exceptionally with ServiceFailure. |

The approved context design uses **line numbers, not offsets**. A selected snippet
may begin or end mid-line. The modification flow must preserve the exact selected
text and validate the original full-file snapshot instead of inferring edit offsets
from line numbers. None of these value types reads or writes files.

## Ports and ownership

| Java port | Owner | Signature |
| --- | --- | --- |
| `KnowledgeSearch` | Member 2 | `search(String query, int maxResults) -> CompletableFuture<List<KnowledgeChunk>>` |
| `ChatService` | Member 3 | `ask(ChatRequest) -> CompletableFuture<ChatAnswer>` |
| `CodeGenerationService` | Member 3 | `generate(ModificationRequest) -> CompletableFuture<ChangeProposal>` |
| `ModificationService` | Member 4 | `modify(ModificationRequest) -> CompletableFuture<ModificationResult>` |

Context collection and chat request coordination belong to Member 1. The future
ChatService implementation consumes KnowledgeSearch; ModificationService coordinates
CodeGenerationService, built-in Diff review and explicit apply/cancel. The Tool Window
does not query an index or construct model prompts. The current RequestCoordinator
coordinates chat only; the modification UI and lifecycle are Stage 5 work.

## Chat execution and lifecycle

1. Reject blank questions in the UI and capture immutable code context when the
   editor action is invoked. The request retains that snapshot even after editor changes.
2. RequestCoordinator calls `ask` off the EDT, including service startup. Return a
   non-null future, completed with a non-null answer and the same request ID, or
   completed exceptionally with ServiceFailure. A synchronous startup exception is
   converted into a safe failure and the UI becomes usable again.
3. The coordinator owns a deadline (30 seconds by default, configurable through the
   Duration constructor). A real deadline produces retryable TIMEOUT and cancels
   invocation/service futures. Mock TIMEOUT is a separate deterministic test scenario.
4. CompletionException / ExecutionException / plain RuntimeException wrappers are
   unwrapped, preserving ServiceFailure category and retryability. Unknown errors
   use a safe generic message rather than exposing raw exception text.
5. All lifecycle callbacks run on the EDT. Each request delivers at most one terminal
   callback, provided the owner is still alive. Request IDs are checked before display.
6. Clear chat invalidates the UI request before cancellation. A cancellation is
   `onRequestCancelled`, never an error message. A queued success can still be
   cancelled before delivery. Old callbacks must not finish a new request.
7. The content disposer releases the panel/coordinator, clears its registry entry,
   cancels outstanding work and stops owned executors. Merely hiding the Tool Window
   preserves its session. Delivery rechecks both project and coordinator disposal.
8. Adapters must propagate cancellation to their owned HTTP/retrieval work where
   possible. `CompletableFuture.cancel(true)` alone does not interrupt arbitrary
   work or guarantee cancellation at a remote provider. The Mock schedules a delayed
   completion without sleeping worker threads and skips generation if cancelled.

## Answer and citation semantics

- Course-grounded answers include sources actually used; markers must agree with
  returned reference IDs. Preserve document identity and pages/scope end to end.
- General-knowledge answers have no course citations and explicitly disclose that
  no relevant course material was referenced. The UI renders the supplied disclosure;
  the real adapter must enforce provenance/citation consistency before returning.
- Distinguish successful empty retrieval from retrieval failure. Never turn a failed
  search into an apparent no-match result.
- Sample mode is explicit UI configuration, independent of asynchronous execution.
  Mock answers retain Fixed sample / Example sources (invented) labels. The example
  selector changes the next mock request and is disabled during the current request.

## Modification and approval semantics

- Generation returns proposals only. It must not write files.
- Member 4 must validate request/proposal identity, target identity and the exact
  original snapshot. Paths from model output never authorize arbitrary file access.
- Display the proposal in IntelliJ's built-in Diff Viewer; write only after explicit
  approval. Cancelling or closing review leaves the source file unchanged.
- If the file changed after capture, reject/regenerate and obtain renewed review.
  APPLIED means an approved write actually succeeded, not merely that a proposal exists.
- Return apply/cancel/failure to the future UI. Undo support and transport cancellation
  details must be agreed during integration.

## Integration handoff

- Member 2: validate the implemented LocalKnowledgeSearch against the chosen provider
  and real materials; agree on identity, no-match threshold and integration. RAG
  failures use RagException; ChatService maps them to ServiceFailure with its real
  request ID. See the RAG guide for the proposed mapping and local index format.
  Multi-step execution accepts an optional asynchronous FollowUpQueryPlanner;
  the default expands explicit identifiers within each triggering evidence document
  and preserves that evidence alongside its definitions within the result budget.
  Member 3 may supply model-based planning, owning that client's lifecycle.
- Member 3: standalone ChatService / CodeGenerationService adapters are now implemented
  in `llm`; validate the chosen provider and agree on IDE configuration and assembly.
  See [LLM services](llm-services.md) for timeouts, safe failures, citation validation
  and propagation of cancellation.
- Member 4: confirm the initial single-file/full-text proposal format, snapshot
  validation, Diff review, explicit approval and result callbacks.
- Member 1: replace the mock in CourseAiToolWindowFactory; construct a live ChatPanel
  without the sample selector callback. Bind every future lifecycle to content/project
  disposal and keep real integration marked pending until its acceptance passes.
