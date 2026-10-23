# Architecture and Ownership — Stage 1 Draft

## Scope and implementation status

Group C must implement F0 (course RAG Q&A, citations, embedded UI and selected-code
questions) and F2 (instruction-driven changes reviewed in the built-in Diff Viewer
before application). Stage 2 has a Java chat Tool Window with local sample replies.
The following design assigns future services; it does not claim live RAG/LLM integration.

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
5. **Responsive IDE.** Run network/indexing work off the UI thread, update UI on
   that thread, and respect project disposal. Implement in the request-handling stage.
6. **Review before write.** Member 4 owns original snapshots, Diff and explicit
   approval/cancel. Undo and stale-file checks are recommended reliability measures.

## Integration order

1. Agree on draft contracts; develop UI with clearly marked sample results.
2. Connect selected-code context and background requests.
3. Integrate real retrieval/chat, including citation/no-match/failure paths.
4. Integrate modification generation and the review/apply lifecycle.

No model credentials, network clients, indexing or source edits are introduced in
Stages 1–2. Application code uses Java 21; retain Kotlin build scripts. The temporary
`sample` package is for UI examples, not the final LLM/RAG contract.
