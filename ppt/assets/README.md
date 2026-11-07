# PPT Diagram Assets

Hand-crafted SVG diagrams for the Course AI Assistant presentation. All diagrams use 1600×900 viewport (16:9), consistent color palette, and Arial/Helvetica typography.

## Slide 3 — System Architecture

- `system-architecture.svg`: editable vector original. All boxes, text and connectors remain SVG elements.
- `system-architecture.mmd`: Mermaid relationship source for re-layout.
- `system-architecture.png`: raster preview/fallback.

Logical responsibility diagram within one IntelliJ plugin. Arrows represent dependencies/delegation. Colors: blue = IDE/project coordination; green = retrieval; violet = grounded chat; orange = reviewed changes; gray = shared support/resources.

## Slide 4 — Course Material Indexing

- `material-indexing.svg`: pipeline flow from source documents to local index.
- `material-indexing.mmd`: Mermaid source for the main pipeline.

Shows the 5-step document processing pipeline: source → page extraction → chunking → embeddings → local index. Includes chunking strategy detail (800 code points, 120 overlap) and stored data structure.

## Slide 5 — Knowledge Storage and Retrieval

- `knowledge-retrieval.svg`: storage structure and per-question retrieval flow.

Upper section shows three stored data types: file registry, chunk vectors, index manifest. Lower section shows the retrieval flow: question → query embedding → similarity search → threshold filter → up to 5 chunks. Distinguishes pre-built document vectors (reused) from per-question new vectors.

## Slide 6 — Course Q&A Workflow

- `qa-workflow.svg`: three-input convergence to LLM, output with cited evidence.

Shows inputs (question, optional selected code, retrieved chunks) converging into the LLM prompt. Output includes answer with citation markers and expandable evidence. Alternative paths: no relevant match (discloses general knowledge) and retrieval failure (shows error).

## Slide 7 — LLM Integration and Source Validation

- `source-validation.svg`: prompt rules, local checks, and citation chain.

Left section: prompt rules constraining model output. Right section: local validation checks before display. Bottom: citation chain showing traceability from source metadata → evidence ID → validated citation → visible [1] marker.

## Slide 8 — Reviewed Code Modification

- `code-modification.svg`: branch flow with approve/cancel paths.

6-step workflow: instruction+snapshot → LLM proposal → validate → Diff review → (cancel: keep original | approve: recheck → undoable write). Emphasizes that generation/preview never writes source files, and approval triggers a recheck to detect intervening edits.

## Design Conventions

| Element | Value |
|---------|-------|
| Viewport | 1600 × 900 px (16:9) |
| Font | Arial, Helvetica, sans-serif |
| Arrow color | `#64748b` (slate-400) |
| Corner radius | 12px (boxes), 16px (sections) |
| Background | `#ffffff` (canvas), `#f5f7fa` (sections) |

### Color Palette

| Color | Hex | Usage |
|-------|-----|-------|
| Blue | `#e9f0ff` / `#c6d6f5` | IDE, project coordination |
| Green | `#e7f5ef` / `#b9dfcd` | Retrieval, success |
| Violet | `#eef0ff` / `#cdd1f2` | Chat, LLM |
| Orange | `#fff2e5` / `#ebd1b1` | Input, modifications |
| Yellow | `#fef3c7` / `#f59e0b` | Warnings, thresholds |
| Red | `#fee2e2` / `#ef4444` | Errors, cancel paths |
