# Demo Design — Course AI Assistant

## Demo A — Course Q&A: Understanding Code via Lecture Knowledge

| Item | Detail |
|---|---|
| **File** | `examples/f2/Notifier.java` |
| **Select** | All three type declarations (`interface Notifier`, `abstract class BaseNotifier`, `class EmailNotifier`) |
| **Question** | "When should I use an interface versus an abstract class in Java? Use this Notifier example to explain." |
| **Lecture** | Lec-2-OOP-with-Java.pdf — class declaration syntax, `implements` vs `extends`, partial implementation via abstract class |
| **Expected citations** | p2 (class declaration modifiers), interface/abstract class distinction, concrete subclass completing abstract contract |
| **Narrative** | Student selects correct code → plugin explains the design pattern using course knowledge → sources show exact lecture pages |

---

## Demo B — Bug Fix: Exception Handling

| Item | Detail |
|---|---|
| **File** | `examples/f2/FileProcessor.java` |
| **Bug** | `catch (Exception e)` swallows IOException; resources never closed on failure; caller gets null with no error signal |
| **Instruction** | "Fix the readConfig method so that it properly handles IOException with try-with-resources and does not swallow exceptions. Preserve the class name, method signature, and all unrelated code." |
| **Lecture** | Lec-3.1-Exceptions.pdf — Catch or Specify Requirement (p4), checked vs unchecked exceptions (p5), try-with-resources as preferred resource management |
| **Run before fix** | `java examples/f2/FileProcessor.java` → prints `Config: null` (silent failure) |
| **Expected change** | `catch (Exception e)` → `catch (IOException e)`; add `try-with-resources`; propagate or log instead of returning null |

---

## Demo B — Refactoring: Generics Replace Raw Types

| Item | Detail                                                                                                                                                                                                       |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **File** | `examples/f2/StudentManager.java`                                                                                                                                                                            |
| **Bug** | Raw `List` / `Object` parameters require unsafe `(Student)` cast; runtime ClassCastException risk                                                                                                            |
| **Instruction** | "Refactor StudentManager to use generics (List<Student>) instead of raw types, eliminating unsafe casts. Preserve the class name, public method signatures, existing behavior, and the inner Student class." |
| **Lecture** | Lec-3.2-Generics-and-Collections.pdf — generics eliminate casting (p4), stronger compile-time type checks (p4), generic class format `ClassName<T>` (p5)                                                     |
| **Run before refactor** | `java examples/f2/StudentManager.java` → works but compiler warns about raw types                                                                                                                            |
| **Expected change** | `List students` → `List<Student> students`; `new ArrayList()` → `new ArrayList<>()`; `addStudent(Object)` → `addStudent(Student)`; remove `(Student)` cast in `getStudentName`                               |

---

## Prerequisites

- **API credentials** configured in Settings > Tools > Course AI Assistant > Language model (Demo B requires live LLM; SampleCodeGenerationService only recognizes SumDemo/GreetingDemo).
- **Course materials indexed**: all 8 PDFs present in `.course-ai/documents/default/documents.json`.
- **Files must remain unmodified** before each demo run; reset from git if a previous demo altered them.
