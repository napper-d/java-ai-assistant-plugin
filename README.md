# Course AI Assistant

CST3492 Assignment A, Group C (F0 + F2).

This repository contains the IntelliJ plugin, project report and presentation sources.

```text
code/       Gradle plugin project, implementation, tests and technical documentation
report/     Project report and its future image assets
ppt/        Presentation outline and future slide assets
```

- [Plugin setup, build and usage](code/README.md)
- [Contribution guide](code/CONTRIBUTING.md)
- [Project report](report/Project-Report.md)
- [Presentation outline](ppt/PPT-Outline.md)

Open **code/** as the Gradle project in IntelliJ IDEA. The Git repository root is
its parent directory; IDEA's VCS mapping should point to that parent repository.
The shared **Run IDE with Plugin** configuration is in `code/.run/` and assumes
that `code/` is the IDE project directory.

From the repository root, build with:

```powershell
cd code
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

The plugin ZIP is generated under `code/build/distributions/`. Run helper scripts
from `code/` as described in the plugin documentation. Their paths are relative to
the Gradle project rather than this repository root.
