# Service Contracts — Draft v0.1

Status: proposed for team review, not implemented Java APIs. Final signatures and
libraries need agreement with Members 2–4. Sample adapters must be visibly marked.

Stage 2 implements local UI examples under `sample/`, not these final service APIs.
Its provider intentionally accepts only an example scenario and does not send a
question to a model. Shared chat/context/modification contracts remain a team draft.

## Shared values

| Value | Proposed fields / rules |
| --- | --- |
| `CodeContext` | Project-relative file path, language ID, selected text, start/end offsets, captured document modification stamp. Optional surrounding text must be explicit and bounded. |
| `SourceReference` | Stable reference ID, document ID/name, page start/end or meaningful source scope. At least page or scope must be present. |
| `KnowledgeChunk` | Chunk ID, source reference, text, retrieval score and scoring metadata if available. |
| `ChatRequest` | Request ID, question, optional code context. History policy to be agreed before multi-turn support. |
| `ChatAnswer` | Request ID, answer text, cited sources, provenance (`COURSE_GROUNDED` / `GENERAL_KNOWLEDGE`), optional disclosure. |
| `ModificationRequest` | Request ID, instruction, code context and stable original-file snapshot needed for review/application. |
| `CodeChange` | File path, original full text or explicitly defined edit range, proposed text, explanatory summary. Representation to be agreed. |
| `ChangeProposal` | Proposal ID, request ID, code changes. Initial supported scope to be agreed; multi-file support is not assumed. |
| `ServiceFailure` | Request ID, category, safe user-facing message, optional retryability. Diagnostics belong in logs. |

Offsets follow IntelliJ documents: zero-based UTF-16, inclusive start and exclusive
end. Services must not infer permission to read/change arbitrary paths from model output.

## Ports and ownership

| Port | Owner | Input → output |
| --- | --- | --- |
| `KnowledgeSearch` | Member 2 | Query + options → matched chunks or successful empty result |
| `ChatService` | Member 3 | Chat request → answer or service failure |
| `CodeGenerationService` | Member 3 | Modification request → proposed changes or service failure |
| `ModificationService` | Member 4 | Coordinate generation, review and explicit apply/cancel outcome |

Context collection and request coordination belong to Member 1. `ChatService`
consumes `KnowledgeSearch`; `ModificationService` consumes `CodeGenerationService`.
The Tool Window normally does not call search or generation directly.

## Required semantics

### Questions and citations

- Reject blank questions before invoking a service; capture immutable context.
- Course-grounded answers include sources actually used; answer markers and
  returned reference IDs must agree.
- General-knowledge answers have no claimed course citations and explicitly
  disclose that no relevant course material was referenced.
- Distinguish successful empty retrieval from failed retrieval.
- Do not silently display missing/inconsistent metadata as verified course citations.
  Exact validation and fallback policy needs agreement with Members 2–3.

### Changes and approval

- Generation returns proposals only; it never writes files.
- Member 4 validates scope and uses the built-in Diff Viewer before any write.
- Applying requires explicit approval. Cancelling/closing the preview leaves files unchanged.
- If target content changed since capture, regenerate or obtain renewed review
  rather than overwriting newer content.
- Apply/cancel/failure outcomes return to the UI.

### Execution and lifecycle

- Run slow work in the background. Choose one asynchronous adapter in Stage 4;
  this draft does not force a particular future/coroutine library.
- Correlate responses by request ID; ignore callbacks for disposed projects or stale requests.
- Do not log credentials or full user code by default.

## Sample acceptance data

Invented UI-development data, not actual retrieved course content:

- Course answer: marker `[S1]`, source `S1`, document `sample-course.pdf`, page 3,
  provenance `COURSE_GROUNDED`.
- No-match: no course sources, provenance `GENERAL_KNOWLEDGE`, disclosure
  `Response is based on general knowledge; no specific course material is referenced.`
- Failure: category `TIMEOUT`, a safe message, retry allowed; not a no-match answer.
- Change: a proposed null-check fix; declining it must preserve original content.

## Items requiring teammate agreement

- Member 2: formats, page/scope conventions, persistence, scores and no-match threshold.
- Member 3: provider/configuration, timeouts, prompts, citation validation and results.
- Member 4: initial change scope, full-text vs ranged edits, validation and review callbacks.
- All members: stable method signatures and asynchronous adapter before integration.
