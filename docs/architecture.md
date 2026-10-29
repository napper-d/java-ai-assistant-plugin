# Architecture and Ownership — Stage 4

> Current UI update: Chat and Course materials share one icon navigation surface;
> import indexes automatically and numbered citations expand original evidence.
> Historical independent retrieval-page descriptions below are superseded.
> See [current UI review](integration-i3-review.md).


> I2 update (2026-10-28): Tool Window Chat now uses ProjectLlmService through
> LiveChatSession, with project settings/Password Safe and ProjectRagService.search.
> Mock and Sample descriptions below are historical. F2 already has a separate live
> Diff/apply entry and awaits shared settings wiring in I4. See [I2 review](integration-i2-review.md).


Member 3 update: the `llm` package now implements the existing chat and code-generation
ports as standalone adapters. See [LLM services](llm-services.md). The historical
scope below predates this delivery; real-provider acceptance, Tool Window wiring
and Member 4's modification workflow still remain pending.

## Scope and implementation status

Group C must implement F0 (course RAG Q&A, citations, embedded UI and selected-code
questions) and F2 (instruction-driven changes reviewed in the built-in Diff Viewer
before application). Stages 2–4 implement the Java chat Tool Window, selected-code
snapshots and asynchronous request coordination. The default adapter is still a
visibly marked MockChatService. The RAG package is available as a standalone adapter
with original-path PDF/PPTX registration, online embeddings, persisted local search
and bounded multi-step retrieval. Query planning is pluggable; the default expands
explicit identifiers and the chat owner may supply model-based planning. Real-provider
acceptance, live chat integration and modification review/apply services remain pending.
The standalone Member 3 LLM adapters are documented in the update above.
The R1-R3 GUI adaptation provides project settings and a Course materials tab.
ProjectRagService owns each RAG session; KnowledgeBaseManager serializes material
operations and rebuild/reload work, while KnowledgeBasePanel delivers results on
the EDT. RetrievalPreviewPanel calls the existing KnowledgeSearch through the
project service and shows raw excerpts and real sources. Settings and library
changes invalidate its pending work and old results. The original ChatService
remains a mock. Chat uses one asynchronous coordinator path for both the Tool
Window and JUnit tests; obsolete synchronous sample adapters have been removed.
Retrieval messages append incrementally, and score changes update existing reply
text without rebuilding the transcript. See the RAG GUI plan and R3 review for acceptance.

## Proposed dependency flow

```text
Tool Window / Editor Action                       Member 1
        |
        +--> Context Collector                    Member 1
        |
        +--> Request Coordinator                  Member 1
                 |
                 +--> Chat Service                Member 3
                 |       +--> Knowledge Search    Member 2
                 |       |       +--> Course document ingestion / index
                 |       +--> LLM Client          Member 3
                 |
                 +--> Modification Service        Member 4
                         +--> Code Generator      Member 3
                         +--> Built-in Diff Viewer / explicit approval
                         +--> Validated write and undo
```

The UI consumes application-level results; it does not construct prompts or access
the index directly. Generating or previewing a proposal must never write source files.

## Proposed Java packages

Base package: `edu.teamc.courseaiassistant`.

| Package | Purpose | Owner |
| --- | --- | --- |
| `ui` | Tool Window and result/state presentation | Member 1 |
| `action` | Editor context-menu entry points | Member 1 |
| `context` | Capture immutable selected-code snapshots | Member 1 |
| `application` | Coordinate requests and UI lifecycle | Member 1 |
| `model` | Shared request/result data contracts | Coordinated by Member 1 |
| `rag` | Document parsing, chunk metadata, index and retrieval | Member 2 |
| `llm` | LLM transport, prompts, chat and code generation | Member 3 |
| `modification` | Proposal validation, Diff, approval and source edits | Member 4 |

Use one Gradle plugin project initially. These packages do not need separate Gradle
modules. The root-package Kotlin sample was replaced by the Java UI in Stage 2.

## Design decisions

1. **Java 21 baseline.** Toolchains and daemon criteria require 21. Retain the
   already working target IDEA and dependency versions.
2. **Plain Java contracts.** Shared requests/results do not contain Swing widgets
   or IntelliJ `Project`/`Editor` objects. IDE-facing orchestration may use them.
3. **Traceable sources.** Preserve document identity and page/scope from ingestion
   to answer display. Do not reconstruct page numbers from model guesses.
4. **Explicit provenance.** Disclose general knowledge on no-match answers.
   Retrieval failure remains distinct from a successful empty result.
5. **Responsive IDE.** RequestCoordinator invokes ChatService on owned background
   workers and delivers terminal callbacks once on the EDT, with a 30-second
   deadline. It checks disposal at delivery and retains completed requests until
   delivery so clearing can invalidate queued replies. ChatPanel also checks the
   active request ID. Disposing the content stops workers and deadlines; hiding it
   does not dispose it. Transport adapters must implement cancellation themselves.
6. **Review before write.** Member 4 owns original snapshots, Diff and explicit
   approval/cancel. Undo and stale-file checks are recommended reliability measures.
7. **Line-based context.** The approved CodeContext design uses inclusive one-based
   start/end lines, selected text and a document modification stamp, not offsets.
   F2 handoff contracts use an explicit original full-file snapshot and a single-file
   replacement proposal. Ordinary chat does not collect full files. These contracts
   are available for Members 3–4 to review; implementations are not registered.

## Integration order

1. Agree on draft contracts; develop UI with clearly marked sample results.
2. Connect selected-code context and background requests.
3. Integrate real retrieval/chat, including citation/no-match/failure paths.
4. Integrate modification generation and the review/apply lifecycle.

No model credentials, network clients, indexing or source edits are introduced in
Stages 1–2. Application code uses Java 21; retain Kotlin build scripts. The temporary
`sample` package is for UI examples, not the final LLM/RAG contract.
