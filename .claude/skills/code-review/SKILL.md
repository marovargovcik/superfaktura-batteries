---
name: code-review
description: "Code review a pull request. Use for 'review PR', 'code review', 'review this branch', etc."
---

# Code Review Skill

You are orchestrating a multi-agent code review of the current branch. Follow these steps precisely.

## Git Context

- Current branch: !`git branch --show-current`
- Base commit: !`git merge-base origin/master HEAD 2>/dev/null || git merge-base origin/main HEAD 2>/dev/null`
- Changed files: !`git diff --name-only origin/master...HEAD 2>/dev/null || git diff --name-only origin/main...HEAD 2>/dev/null`
- Commit messages: !`git log --format="%s%n%b" origin/master..HEAD 2>/dev/null || git log --format="%s%n%b" origin/main..HEAD 2>/dev/null`

## Step 1: Gather Context

1. **Read `REVIEW.md`** — this contains all focus area descriptions and review rules. You will embed relevant sections into sub-agent prompts.

2. **Read the relevant sections of `CLAUDE.md`** — the project's consolidated guidelines. You will embed these into sub-agent prompts. Sections to use:
   - "Scala — Code Structure"
   - "Scala — Code Style"
   - "Scala — Circe"
   - "Testing — General"
   - "Testing — Scala"
   - "Comments"

3. **Identify changed Scala files**. If no `.scala` files are changed (only docs, config, etc.), skip the multi-agent review entirely. Instead, review the diff yourself directly and provide a brief summary with any findings. No sub-agents needed.

4. **Summarize the changes**: Write a brief summary of what changed, why (from commit messages), and the scope. This summary will be passed to every sub-agent.

## Step 2: Spawn Sub-Agents

Spawn ALL agents in a **single message** using parallel Agent tool calls.

### Agents to spawn (6 fixed agents)

| Focus area | Notes |
|---|---|
| Easy to Understand | Cross-cutting; reads the full diff |
| Well Tested | Reviews test coverage and quality |
| Composable | Reviews abstraction and structure |
| Security | Reviews auth, input validation, data exposure |
| Performance | Reviews N+1, queries, unbounded loads |
| Style | Reviews conformance to documented guidelines |

### Sub-agent prompt template

Every sub-agent prompt MUST include:

1. The PR context summary (from Step 1).
2. The list of changed Scala files relevant to that agent.
3. The full diff (or filtered diff if useful).
4. The focus area description — read the relevant section from `REVIEW.md` and embed it verbatim, including the "Scala" sub-section if one exists.
5. The relevant `CLAUDE.md` content (read by you, embedded verbatim).
6. The output format instructions (from "Sub-agent output format" below).
7. A note that the agent is free to read additional files (docs, source code, tests) for context beyond the diff.

### Sub-agent output format

Include this in every sub-agent prompt:

```
## Output Format

Report your findings using conventional comments with a criticality score (0-100).

Format each finding as:

**type (score):** `file.scala:42` - Brief title

Description of the issue.

Types (inspired by conventional comments):
- blocking: Blocks merging. Security vulnerability, data loss risk, broken functionality. Score: 90-100
- issue: Incorrect behavior, missing critical tests, backwards incompatibility. Score: 70-89
- suggestion: Would improve code quality but not blocking. Score: 40-69
- nitpick: Style preference, minor naming, non-blocking. Score: 20-39
- question: Something you want to understand better. Score: 30-50 depending on importance.
- thought: An observation or idea for consideration. Score: 20-40.

Score guide:
- 90-100: Security vulnerability, data loss, broken functionality
- 70-89: Incorrect behavior, missing tests for critical paths, backwards incompatibility
- 40-69: Would improve code quality but not blocking
- 20-39: Style preference, minor naming, non-blocking

If you find nothing noteworthy in your focus area, say so explicitly. Do not invent findings.
```

## Step 3: Synthesize Results

After ALL sub-agents complete, collect their findings and produce the final review.

### Deduplication

- If multiple agents flag the same file+line for the **same concern**, merge into one finding keeping the highest score and most complete description. Note which focus areas flagged it (e.g., "Flagged by: Security, Composable").
- If multiple agents flag the same file+line for **different concerns** (e.g., a security issue and a missing test), keep them as separate findings.

### Sort and group

- Sort all findings by criticality score descending (most critical first).

### Verdict rules

- **Approve**: The PR improves overall codebase health, even if not perfect. No blocking findings, no high-score issues. Follow the guideline: "Approve when PR improves overall codebase health, even if not perfect."
- **Request Changes**: There are blocking findings (score >= 90) or multiple high-score issues (score >= 70).
- **Comment**: There are issues worth discussing but nothing clearly blocking. Use when uncertain.

### Output format

Output the review as text in the conversation:

```
## Code Review: [branch-name]

### Summary
[Your summary of what changed, from Step 1]

### Verdict: [Approve / Request Changes / Comment]
[Brief justification]

---

### Blocking & Issues (score >= 70)
[All blocking and issue findings, sorted by score descending]
[If none: "No blocking issues found."]

### Suggestions (score 40-69)
[All suggestion findings, sorted by score descending]
[If none: "No suggestions."]

### Nitpicks
[If 5 or fewer: show all nitpick findings]
[If more than 5: show top 5 by score, then "N additional nitpicks omitted. Ask to see all."]
```

## Step 4: Interactive Triage

If the `AskUserQuestion` tool is available, you **MUST** use it for this step.

After presenting the review, use `AskUserQuestion` to let the user decide which findings to act on. Ask a multi-select question listing the actionable findings (score >= 40) as options, letting the user pick which ones to address.

Once the user selects findings, ask how they'd like each one addressed (e.g., fix it now, leave a TODO, dismiss with reason). Then proceed to implement the chosen fixes.

If there are no actionable findings (all scores < 40), skip this step.
