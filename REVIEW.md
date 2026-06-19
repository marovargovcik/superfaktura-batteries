# Code Review Guidelines

The primary purpose of code review is to ensure the overall health of the codebase improves over time.

Approve a pull request when it definitely improves the overall health of the codebase, even if it isn't perfect. For new code, it must not lower the overall health of the codebase. Seek continuous improvement, not perfection.

## Priority order

1. **Security** — the highest priority. All API endpoints and request handlers must perform security checks.
2. **Correctness and backwards compatibility** — including adequate test coverage.
3. **Code style** — only flag violations of documented style guides, not personal preferences.
4. **Performance**

## Focus areas

### Security

All API endpoints and request handlers must perform security checks. Trace the code path — don't just check that a security import exists.

Check for:

- **Authentication**: is the user properly authenticated?
- **Authorization**: are permissions checked before data access?
- **Input validation**: is user input sanitized?
- **Data exposure**: could sensitive data leak through responses?
- **Privilege escalation**: could a user access data they shouldn't?
- **Injection risks**: SQL injection, command injection, XSS.

#### Scala

- Security checks belong in the **business-logic layer**, not the data-access layer.
- When a request handler calls a data-access method directly, ensure the security check is performed at the handler level.
- Extract security checks into named methods to avoid re-declaring them and to make testing easier.

### Easy to understand

- **Default to the most obvious code that works.** When introducing an abstraction, make sure its benefit outweighs the complexity it adds.
- **Write for the engineer who lacks your context.** Special cases and assumptions might seem obvious today but cause headaches years from now. Keep it principled and unsurprising.
- **When simple code isn't possible, prefer making it obvious over commenting it.** A comment is justified only to record a business/domain decision or external constraint the code cannot express — never to explain how or why the code is written that way (that belongs in the commit/PR). See `CLAUDE.md` "Comments".

Check for:

- **Naming clarity**: are variable/function/class names self-explanatory?
- **Justified abstraction**: is every abstraction earning its complexity? Flag over-engineering.
- **Surprising behavior**: would a new team member be confused by any logic?
- **Missing business rationale**: does a value or branch driven by a domain rule or external constraint lack the one-line business comment that would explain it? (Do not ask for comments on implementation mechanics.)

Do NOT flag style issues or performance concerns here — those are separate focus areas.

### Well tested

We should have *exactly* the right tests to verify the key properties of some functionality, and no more. A good test should break if the behavior it describes changes, and *only* if that behavior changes.

Check for:

- All interesting code paths covered by tests.
- No low-value or duplicate tests.
- Tests that would break for the wrong reasons (testing implementation details).
- Missing edge case coverage for critical paths.
- **Time-sensitive flakiness**: tests using dates/times should use hard-coded values or `TemporalAdjusters` to avoid flaking on weekends, month boundaries, leap days, or out-of-office hours.
- **For bug fixes**: is there a test that would have caught the bug?

Explore beyond the diff — check if test files exist for changed code, read existing tests to understand coverage gaps.

### Composable

Functionality should be clearly encapsulated, using the right abstractions to make it composable and easy to build upon. Avoid over-complicating things too early — design for the needs *today* with simple, composable abstractions that can easily be expanded upon.

Check for:

- Are new abstractions justified? Could existing patterns be reused?
- Is functionality clearly encapsulated with well-defined boundaries?
- Are there signs of over-engineering for hypothetical future needs?

#### Scala

- Use of trait + companion object pattern for effectful components, with a generic `F[_]` type parameter.
- Effectful components should take their dependencies via `using` parameter clauses (`given` instances), not on individual methods.
- Use generic effect type `F[_]`, not a concrete effect.
- Security checks belong in the business-logic layer, not the data-access layer.
- API/request handlers should delegate to business-logic components for non-trivial logic.
- Data-access components should be simple data access, not contain business logic.

### Performance

Check for:

- **N+1 query patterns**: `.map` followed by a DB call, loading entities in a loop.
- **Inefficient queries**: missing indexes, full table scans, unnecessary JOINs.
- **Missing pagination**: queries that could return unbounded results.
- **Unnecessary data loading**: loading full entities when only IDs or specific fields are needed.
- **Migrations**: should be backward-compatible and ideally a single statement per file.

Specific patterns to flag:

- `.map` or `.traverse` calling a data-access method (likely N+1).
- Missing `LIMIT` on queries that could grow unboundedly.
- New columns without considering index impact.
- Loading related entities eagerly when they may not be needed.

### Style

Only flag style issues that violate documented guidelines. Anything not in the style guide is personal preference and must not block a review. Never suggest extracting literals into constants unless they are used more than once.

## Principles

- **Balance progress with quality.** Don't delay a PR for days because it isn't perfect. Balance the need to make forward progress against the importance of the suggested changes.
- **Keep ego out of the process.** Share alternative solutions with different tradeoffs, but don't block a PR just because you would have done it differently.
- **Use conventional comments.** Use types like `blocking`, `issue`, `suggestion`, `nitpick`, `question`, and `thought` to clearly communicate the weight of feedback.
- **Trust the author.** Reviewers should trust that the author will create follow-up PRs to address non-blocking feedback.
- **Durable domain answers go in code; implementation answers stay in the PR.** If a reviewer's question is about a business/domain decision, capture the answer as a one-line code comment so it outlives the PR. Questions about *how* or *why* the code is written are answered in the PR thread, not the source.
