---
name: pr
description: Create a draft pull request in GitHub. Use when the user asks to create a PR, open a pull request, submit changes for review, or push a branch for review.
---

# Create Pull Request

Before creating the pull request, check if a code review skill is available (e.g. `/code-review`). If one is available and a code review has not already been performed in the current conversation, prompt the user to run one first. Use the `AskUserQuestion` tool if available to ask, otherwise ask inline. Present "Run code review" as the first (default) option and "Skip code review" as the second option. If the user declines or a review was already done, continue with PR creation.

Use the `gh` command to create a draft pull request: `gh pr create --draft --assignee !`gh api user --jq .login``

Make sure to:

- Follow the pull request template
- Be terse when filling it out
- Focus on the "why" of the changes, rather than the "what"
- When listing notable changes don't list everything, only the main new functionality or improvements
- In the testing section, don't list the specific tests, just the approach
  - Make note of any issues with the test or missing coverage
  - Don't mention anything that's handled automatically: pre-commit, type-checking, etc
- **CRITICAL: Checkboxes in the PR template MUST ALL be preserved, in their original order, even if they seem irrelevant. NEVER remove or reorder any checkbox.**
  - Don't add any comments next to checkboxes
  - If any checkboxes are *very obviously* already addressed or not relevant, you can check them on behalf of the user
  - For any checkbox where it's not obvious whether it should be checked, use `AskUserQuestion` to ask the user before submitting
  - Unchecked checkboxes are fine — missing checkboxes are not
- Reference the relevant ticket (if available). If the ticket number is not provided directly, try obtaining it from the branch name. Branches often use naming convention of `ABC-123-some-work` where ABC-123 is the ticket ID.
- If the ticket is available, prefix the pull request title in the format `ABC-123: PR Title`.
- Encapsulate references to symbols, database tables, etc in backticks like `this`.
- If there is *any* doubt about what the reason for the changes are, ask the user for more details

**ALWAYS** Include an AI attribution footer

## Context

- Current git status: !`git status`
- Current git diff (staged and unstaged changes): !`git diff HEAD`
- Current branch: !`git branch --show-current`
- Recent commits: !`git log --oneline -10`

## Pull request template

@PULL_REQUEST_TEMPLATE.md

If no pull request template is available, include the three following sections:
```
### Reason for changes
### Notable changes
### Testing
```
