# Course AI Assistant — Presentation Outline

> 中文编排说明：正文与屏幕文案使用英文，以满足作业要求。建议 14 张主幻灯片，含现场演示约 19 分钟，另外准备问答备份页。此文件是逐页制作与讲述大纲，尚非成品幻灯片。成员用编号代替，正式制作前填入姓名、学号；实际截图和验收结果不得用虚构内容替代。

## Presentation Plan

**Core message:** Course-grounded answers with inspectable evidence, and AI-generated Java changes under explicit developer control.

| Slides | Presenter | Time including demo |
| --- | --- | --- |
| 1–4 | Member 1: project and integration | 4:00 |
| 5–7 | Member 2: RAG and grounded-answer demo | 5:00 |
| 8–9 | Member 3: LLM and answer reliability | 3:30 |
| 10–12 | Member 4: reviewed modification demo | 4:30 |
| 13–14 | Assigned speakers: collaboration and results | 2:00 |
| Total | All speakers | **19:00**, leaving one minute of margin |

## Slide 1 — Course AI Assistant

**Time:** 0:20 · **Presenter:** Member 1

On-slide content:

- CST3492 — Java Enterprise Application Development.
- Assignment A · Group C · F0 + F2.
- All four members' full names and matriculation numbers: **[TO COMPLETE]**.
- “Course-grounded answers. Reviewed Java changes.”

Visual: one clean product screenshot beside the project title; readable member list. The first-slide identity requirement is mandatory.

Speaker cue: “We built an IntelliJ IDEA assistant that connects Java questions to course evidence and lets developers review AI-generated changes before applying them.”

## Slide 2 — Problem and Delivered Scope

**Time:** 0:50 · **Presenter:** Member 1

On-slide content:

- Students need answers aligned with their actual lecture materials.
- Selected code supplies the immediate programming context.
- Generated source changes require a reviewable baseline and explicit approval.
- F0: course RAG, citations, embedded chat and selected-code questions.
- F2: instruction → proposal → built-in Diff → approval → application.

Visual: two short user journeys, Q&A and modification. Keep the full feature mapping in the report rather than a dense slide table.

## Slide 3 — Inside the IDE

**Time:** 1:30 · **Presenter:** Member 1 · **Demo A**

On-slide content:

- Chat and Course materials in one Tool Window.
- IDE-wide model settings and shared course library.
- Editor actions for questions and modifications.

Live sequence: show the Tool Window; point out settings without exposing keys; show an already indexed materials list; select a method and invoke **Ask Course AI Assistant**. Submit a pretested code question and identify the captured context. If time permits, show answer formatting and clear chat. Explain that Enter sends and Shift+Enter inserts a newline.

Visual fallback: annotated Tool Window and editor context-menu screenshots.

## Slide 4 — Architecture and Responsibility Boundaries

**Time:** 1:20 · **Presenter:** Member 1

On-slide content:

```text
Tool Window / Editor Actions
           ↓
Application services and request lifecycle
       ↙                         ↘
Chat + Retrieval              Modification
       ↓                         ↓
LLM + local index         LLM → Diff → approved write
```

- Shared contracts separate UI, retrieval, LLM and source editing.
- Global library; project-local request sessions.
- Background work with EDT presentation and stale-result suppression.

Speaker cue: explain who owns model generation versus file modification. Transition: “Next, we will show how lecture text becomes traceable evidence.”

## Slide 5 — From Lecture Files to Knowledge Chunks

**Time:** 1:10 · **Presenter:** Member 2

On-slide content:

- PDFBox / POI → physical pages / slides → boundary-aware chunks.
- Paragraph → sentence → word → Unicode code point fallback.
- Default maximum: 800 code points; overlap budget: 120.
- Source document and page/slide travel with each chunk.
- Batched embeddings; local persisted vector snapshot.

Visual: one lecture page split into two illustrated chunks with identical page metadata. Label the overlap as a maximum budget, not a guaranteed fixed length. State that image-only text requires OCR, which is outside the current implementation.

## Slide 6 — Retrieval, Storage and Efficiency

**Time:** 1:10 · **Presenter:** Member 2

On-slide content:

- Original-path registry + manifest + versioned JSON vector snapshot.
- One question embedding; reuse stored document vectors.
- Local similarity search → minimum score → up to five chat results.
- `score = (cosine + 1) / 2`; default threshold 0.70, pending calibration.
- Approximate work O(Nd); appropriate starting point for a modest course library.

Visual: show the three storage artifacts and query flow. Explain atomic manifest publication and configuration fingerprints in one sentence each. Scores are similarity measures, not confidence probabilities.

## Slide 7 — Course Answer with Inspectable Sources

**Time:** 2:40 · **Presenter:** Member 2 · **Demo B**

On-slide content:

- Retrieve a course concept from an actual imported lecture.
- Answer combines the current question and retrieved evidence.
- Source row shows document and page/slide.
- Expand the original excerpt without another API request.

Live sequence:

1. Add one small, pretested PDF/PPTX and show automatic indexing; avoid rebuilding the full demonstration corpus.
2. Submit **[TO COMPLETE: actual question verified against the chosen lecture]**.
3. Show the answer and numbered citation.
4. Expand the source and compare it with the real page/slide.
5. Briefly show duplicate refresh or removal if it completes within the timing budget.

Prepare actual question, expected source, screenshot and timing before rehearsal. Do not claim a course-specific answer is grounded unless its evidence has been inspected.

## Slide 8 — Prompt Contracts and Validated Replies

**Time:** 1:15 · **Presenter:** Member 3

On-slide content:

- Chat input: question + explicit selection + retrieved evidence.
- Prompt: use evidence; copy source IDs; return one JSON object.
- Validate current-evidence membership and marker/list agreement.
- Source locations come from ingestion metadata.
- Code generation returns path, complete replacement and summary.

Visual: compact request/response schema with a single citation ID; avoid a full prompt screenshot.

Speaker cue: “Validation prevents fabricated reference identifiers from becoming accepted citations. Human inspection is still needed to check whether an excerpt supports a claim.” Mention non-streaming `/chat/completions` and provider-specific compatibility.

## Slide 9 — No Match, Failure and Cancellation

**Time:** 2:15 · **Presenter:** Member 3 · **Demo C**

On-slide content:

| Situation | Behavior |
| --- | --- |
| Relevant evidence found | Grounded answer with validated references |
| Successful search, no match | Explicit general-knowledge disclosure |
| Retrieval or provider failure | Error; no silent evidence fallback |
| Old request/configuration | Cancel or ignore stale result |

Live sequence: use a rehearsed question that actually produces no match; show the disclosure. Show a pre-recorded failure screenshot or reproduce a controlled invalid connection, then restore the working settings before handing over. Briefly demonstrate source expansion and explain that it does not trigger another model request.

Speaker cue: chat history is display-only and not sent to the model; ordinary questions send the explicit selection rather than the whole project. Credentials for chat and embeddings are separate in Password Safe.

## Slide 10 — Safe Application of a Generated Change

**Time:** 0:50 · **Presenter:** Member 4

On-slide content:

```text
Instruction + original snapshot
  → single-file proposal
  → structure and identity validation
  → built-in side-by-side Diff
  → explicit approval
  → snapshot/configuration recheck
  → undoable write and save
```

Visual: two clear approval/cancel branches. The cancel branch ends at “Original unchanged”. Explain that generating and reviewing do not write the file; the plugin does not automatically prove or test the generated program.

## Slide 11 — Live Bug Fix: Review, Cancel, Approve

**Time:** 2:30 · **Presenter:** Member 4 · **Demo D**

On-slide content:

- Example: `GreetingDemo.greet(name)` calls `name.trim()` without a null check.
- Instruction: “Return Hello, guest for null or blank names; keep trimmed greetings for other names.”
- Inspect the proposed replacement in the built-in Diff.
- Reject once, then approve a fresh proposal and verify behavior.

Live sequence: start with the unchanged example from `code/examples/f2/GreetingDemo.java`; show the original behavior or failure. Generate a proposal, review both sides and cancel; verify the original remains unchanged. Generate again, approve and run a small prepared caller covering null, blank and a normal name. Show actual observed output.

Prepare the caller and exact expected behavior during rehearsal; no benchmark or passing result is assumed in this outline. Keep successful screenshots as backup.

## Slide 12 — Refactoring and Stale-Proposal Protection

**Time:** 1:10 · **Presenter:** Member 4 · **Demo E**

On-slide content:

- A second meaningful instruction demonstrates refactoring rather than only text replacement.
- Full-file proposals preserve unrelated code.
- Intervening edits invalidate the proposal's baseline.
- Application uses an undoable IDE command.

Demo: prepare a short Java method with a genuine repeated conditional or duplicated validation. Request a behavior-preserving extraction or Strategy refactoring that remains within one file. Show the diff and explain the reasoning. Demonstrate Undo, or show the prepared stale-proposal rejection evidence after editing the file while review is pending.

**[TO COMPLETE: select and verify a meaningful refactoring fixture.]** The current `SumDemo` is a simple addition method; do not describe it as an existing design-pattern example. If live generation is slow, use the captured real refactoring Diff and explain the workflow with the actual result.

## Slide 13 — Collaboration and Engineering Decisions

**Time:** 1:00 · **Presenter:** Assigned speaker

On-slide content:

| Responsibility | Integration contract |
| --- | --- |
| Plugin/UI | Code context and request lifecycle |
| Course retrieval | Chunks and source metadata |
| LLM services | Structured answers and proposals |
| Reviewed modification | Decision, validation and write |

- Boundary-aware splitting preserves source meaning.
- Session checks prevent obsolete asynchronous delivery.
- Global settings/library reduce repeated project setup.
- Stage and integration reviews document evolving interfaces.

Add actual names and one concrete integration issue with its resolution. Confirm the team's real branch/review and communication practices before presenting them as facts.

## Slide 14 — Evidence, Limits and Q&A

**Time:** 1:00 · **Presenter:** Assigned speaker

On-slide content:

- Existing recorded results: 135 tests, zero failures/errors/skips.
- **[TO COMPLETE: actual final build revision and live acceptance outcomes].**
- Delivered F0 + F2 with traceable evidence and explicit review.
- Limits: no OCR, single-file changes, no automatic code correctness proof.
- Next: measured retrieval calibration and larger-library indexing.

Speaker cue: “The automated results exercise controlled behavior. Our live examples establish the demonstrated provider and course-material paths.” Only use that second sentence after the live cases have actually passed.

Close with one sentence about delivered user value, then invite questions. Q&A begins after the timed presentation.

## Backup Slides — Outside the 19-Minute Main Talk

1. **Detailed Feature Mapping:** F0/F2 requirements → class → screenshot/demo case.
2. **Index Consistency:** file hashes, configuration fingerprint, snapshot checksum and atomic manifest.
3. **Prompt Examples:** actual chat/code system prompts and accepted JSON shapes.
4. **Evaluation Evidence:** labeled course questions, expected sources, measured timings and threshold comparison; fill only after measurement.
5. **Global Scope:** shared settings/library versus project-local requests; model configuration invalidation.
6. **Failure Cases:** malformed JSON, invented citation ID, provider timeout, stale file and save failure.

## Demo and Export Checklist

- Use the same final plugin revision for screenshots, demos and reported results.
- Preconfigure both services and conceal keys; verify network access before presenting.
- Prepare real course questions with known page/slide locations and no-match cases verified against the actual corpus.
- Prepare small Java fixtures and a caller for before/after behavior checks.
- Rehearse all feature demonstrations, including materials management, citations, selection, general knowledge, cancellation and approved changes.
- Keep screenshots or a short recording of actual successful behavior for network-related interruptions.
- Count API waiting and presenter handoffs inside the 19-minute budget.
- Use a 16:9 layout, legible code and actual product screenshots; keep internal class names mainly in technical diagrams.
- Export the final slides to PDF and check every page for clipping and unreadable text.
- Put all members' full names and matriculation numbers on slide 1.

