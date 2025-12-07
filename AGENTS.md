# Style Guide & Brokk Usage

This project enforces automated formatting, style checks and static analysis. Brokk (the code generator) and all contributors should follow the steps below after creating or modifying Java code.

## Quick workflow

1. After modifying or generating Java code, normalize formatting and indentation:
   - Run: `./gradlew spotlessApply`
   - This applies the project's canonical formatter (google-java-format via Spotless) and fixes whitespace/indentation.

2. Validate changes with the full lint/verification suite:
   - Preferred: `./gradlew lint`
   - Equivalent (two-step): `./gradlew spotlessCheck check`
   - Treat any failures from Spotless, Checkstyle, or SpotBugs as blockers: fix the underlying issue and re-run checks before submitting code.

3. Common Gradle commands
   - Format in-place: `./gradlew spotlessApply`
   - Check formatting only: `./gradlew spotlessCheck`
   - Run static analysis & style checks: `./gradlew check` (includes Checkstyle & SpotBugs)
   - Run everything (format verification + checks): `./gradlew lint`

## When a lint check fails
- Spotless failure: formatting or indentation doesn't match the canonical style. Run `./gradlew spotlessApply` and re-check.
- Checkstyle failure: code style rule violated (naming, Javadoc, visibility, etc.). Inspect the report and amend the code.
- SpotBugs failure: potential bug or bad pattern detected. Review the HTML report at `build/reports/spotbugs/` and fix the issue or add a justified suppression if appropriate.

All three categories are considered blockers — do not submit code with unresolved Spotless/Checkstyle/SpotBugs failures.

## Adjusting indentation width / switching formatter
- This repository currently uses google-java-format via Spotless and an `.editorconfig` at the project root to communicate basic editor settings.
- google-java-format enforces its own formatting rules (it's not configurable for indent width). If the project needs a different indentation width or a configurable formatter:
  1. Update `.editorconfig` (change `indent_size`) to reflect the desired editor preference for humans.
  2. Update `build.gradle` Spotless configuration:
     - Replace the `googleJavaFormat(...)` step with a different formatter supported by Spotless (for example `eclipse()` or a custom import-order/formatter).
     - After changing formatter, run `./gradlew spotlessApply` across the repo to normalize files.
  3. Update `config/checkstyle/checkstyle.xml` if your style rules (line length, braces, etc.) need adjustment to match the new formatter.
- Note: changing the formatter is a repository-level decision — coordinate with maintainers to avoid churn.

## Notes for Brokk
- Always run `./gradlew spotlessApply` automatically after code generation before committing.
- Treat lint output as authoritative and failing builds as blockers; fix issues rather than bypassing checks.
- If you (or CI) need different behavior for generated code (for example, temporary spacing differences), use a short-lived `// CHECKSTYLE:OFF` / `// CHECKSTYLE:ON` block sparingly and only with review/justification.

## Optional: Local Git pre-commit hook (recommended)
To help contributors catch formatting issues before committing, you can install a local Git pre-commit hook that runs Spotless formatting and checks. This hook is optional but recommended to reduce CI failures and review churn.

Two installation approaches are shown below: a recommended repo-managed hooks approach, and a quick local install.

### Recommended (store a hook in the repo)
1. Add a small shell script to the repository, for example `scripts/pre-commit` (commit this file to the repo).
2. Set the repository to use that hooks directory locally (once per clone):
   - git config core.hooksPath scripts
3. Ensure the script is executable:
   - chmod +x scripts/pre-commit

Benefits:
- The hook is versioned with the repo and easy for all contributors to adopt (they only need to run step 2 once after cloning).
- Works well in environments with Git that respects `core.hooksPath` (most modern Git versions).

### Quick local install (single-developer)
1. Copy the provided script into `.git/hooks/pre-commit`:
   - Create `.git/hooks/pre-commit` with the sample content below.
2. Make it executable:
   - chmod +x .git/hooks/pre-commit

This installs the hook only for your clone.

### Sample hook script (portable POSIX shell)
Place this exact content in `scripts/pre-commit` (recommended) or `.git/hooks/pre-commit` (local install):

```sh
#!/bin/sh
# Simple pre-commit hook that formats and checks Java files with Spotless.
# - Runs spotlessApply (may modify files)
# - If modifications were made, aborts commit so the user can review and stage changes
# - Runs spotlessCheck to ensure formatting is correct
# Exit codes are propagated to abort the commit on failure.

set -e

# Run formatter (may modify files)
./gradlew --no-daemon spotlessApply

# If spotlessApply made changes, abort so the user can review and re-stage.
if ! git diff --quiet --exit-code; then
  echo ""
  echo "spotlessApply made changes to files. Please review, 'git add' the changes, and commit again."
  echo ""
  exit 1
fi

# Run the check to be safe
./gradlew --no-daemon spotlessCheck || {
  echo ""
  echo "spotlessCheck failed. Please run './gradlew spotlessApply' and fix issues before committing."
  echo ""
  exit 1
}

# If we reach here, everything passed
exit 0
```

Notes:
- The script uses a POSIX shell. On Windows, use Git Bash, WSL, or provide a `.bat`/PowerShell variant if needed.
- `--no-daemon` is used to avoid long-lived Gradle daemon interactions in hook contexts; you can remove it if desired.
- `spotlessApply` will modify files; the hook intentionally aborts the commit if changes are made so contributors can review and stage them.

### Cross-platform considerations
- Windows users can run hooks from Git Bash or WSL for the POSIX script above.
- If a pure Windows-native approach is required, create a `pre-commit.bat` that invokes `gradlew.bat` and checks for modified files; keep the core logic the same.
- Alternatively, consider adding a small Node-based or Python-based hook manager if your team prefers cross-platform tooling.

### Bypassing the hook
- To skip the local pre-commit hook for a specific commit:
  - git commit --no-verify
- Use this sparingly; CI will still run `spotlessCheck` and other linters, and failing CI remains a blocker.

### Optional: automated onboarding
To ensure new clones automatically get the recommended hook, you can provide a short setup script (e.g., `scripts/setup-hooks.sh`) that runs:
```sh
git config core.hooksPath scripts
chmod +x scripts/pre-commit
```
Ask new contributors to run `./scripts/setup-hooks.sh` after cloning (or add onboarding docs that instruct this step).

---

That's it — following these steps helps catch formatting issues earlier and keeps the CI green. Always run `./gradlew spotlessApply` after Brokk generates or modifies code, then validate with `./gradlew lint` before submitting changes.
