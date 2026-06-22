# Code Review Guidelines

The primary purpose of code review is to ensure the overall health of the codebase improves over time.

Approve a pull request when it definitely improves the overall health of the codebase, even if it isn't perfect. For new code, it must not lower the overall health of the codebase. Seek continuous improvement, not perfection.

> [!NOTE]
> This project is a **local, single-user command-line tool** — there is no server, database, authentication, or
> request handler. Review guidance is framed accordingly: the security surface is secrets, untrusted inputs, the
> filesystem, and data that leaves the machine; the performance surface is external HTTP/OCR calls and I/O, not
> queries or migrations.

## Priority order

1. **Security** — the highest priority. For this tool that means protecting credentials, validating untrusted inputs at the boundary, and being deliberate about data that leaves the machine. Trace the code path — don't just check that a guard exists.
2. **Correctness and backwards compatibility** — including adequate test coverage. The tool mutates a real accounting system, so idempotency and re-run safety matter as much as a single correct run.
3. **Code style** — only flag violations of documented style guides, not personal preferences.
4. **Performance**

## Focus areas

### Security

There are no endpoints or request handlers to authenticate. The real risks are credential leakage, trusting unvalidated input, and unexpected filesystem or network egress. Trace the actual code path; don't assume.

Check for:

- **Secret handling**: the API keys (SuperFaktura, Anthropic) come from the environment only, are wrapped in `Secret` (redacted in `toString`/logs), and are unwrapped only into the request header that needs them. They must never be logged, written into the plan file, or committed — the committed `application.conf` holds only defaults and `${?ENV}` references, never a real value.
- **Transport**: outbound API base URLs must be `https`, rejected before any keyed request is built, so a key never travels in cleartext.
- **Boundary input validation**: untrusted inputs — the bank CSV, the SuperFaktura/Anthropic API responses, the hand-edited plan file, the rules file — are decoded into trusted domain types at the edge (Circe, the CSV parser, pureconfig) and failures surface as a typed `CliError`. The pure core trusts its inputs and does not re-validate. Flag input that reaches the core without passing a boundary parser.
- **Filesystem & path handling**: paths the user supplies (`--csv`, `--receipts`, a rule's `attach` path) are read directly. For this single-user tool the user controls their own inputs, so this is mostly about avoiding surprises — but call out anything that would be a real trust-boundary crossing if a rules or plan file were ever shared or fetched from elsewhere.
- **Data egress**: receipts are sent to the Anthropic API for OCR and the matched file is uploaded to SuperFaktura. Any new code that sends local data to a third party must be intentional and worth a line in the docs.
- **Injection**: there is no SQL and no shell-out. API request bodies are built with Circe (`Json.obj` / `:=`), never string concatenation, so values are escaped — flag any hand-assembled request string or URL built by concatenating unescaped input.

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
- **No mocking libraries**: effectful dependencies are exercised with the project's `*Stub` constant interpreters, not mocks.
- **For bug fixes**: is there a test that would have caught the bug?

Explore beyond the diff — check if test files exist for changed code, read existing tests to understand coverage gaps.

### Composable

Functionality should be clearly encapsulated, using the right abstractions to make it composable and easy to build upon. Avoid over-complicating things too early — design for the needs *today* with simple, composable abstractions that can easily be expanded upon.

Check for:

- Are new abstractions justified? Could existing patterns be reused?
- Is functionality clearly encapsulated with well-defined boundaries?
- Are there signs of over-engineering for hypothetical future needs?

#### Scala

- Effectful dependencies use the **trait + companion-object interpreter** pattern with a generic `F[_]` type parameter; the live interpreter is a `given`.
- Dependencies are passed via `using` parameter clauses (`given` instances), not on individual methods.
- Use the generic effect type `F[_]`; the concrete effect (`IO`) appears only at the entry point.
- Respect the layering (see `CLAUDE.md`): an **Algebra** wraps a side-effecting external dependency, a **Store** persists the app's own data, and a **Program** is effect-polymorphic business logic. Programs compose algebras/stores (and other programs); algebras/stores never call programs.
- Introduce an Algebra/Store only when you'd plausibly swap the implementation (e.g. for tests) or it touches a stateful resource; otherwise a plain pure function or a Program is enough.
- Keep the pure core free of `F[_]` and side effects — parsing, dedup, matching, and plan-building are total functions over immutable data.

### Performance

This is a batch CLI processing one statement per run (tens to low-hundreds of transactions); raw CPU is rarely the concern. The dominant costs are external API/OCR calls and file I/O.

Check for:

- **External HTTP**: SuperFaktura and Anthropic calls are the slow, rate-limited, paid part. Transient failures should be retried with exponential backoff via the http4s `Retry` middleware applied once at the client layer — and only idempotent requests (the GET listing) may be retried; create/edit POSTs must never be, to avoid double-booking.
- **Per-item network/OCR calls in a loop**: flag a `.map`/`.traverse` that issues an API or OCR call per element where the work could be windowed or batched (e.g. existing expenses are fetched once over a date window, not once per transaction).
- **Unbounded results**: a paginated API listing must follow pagination to completion, not assume a single page. In-memory collections are fine at this scale, but flag anything that could grow without bound.
- **Repeated I/O / OCR**: don't read or OCR the same file twice; OCR is slow and billed, so its results/markers shouldn't be recomputed needlessly.
- **Large blobs**: images are downscaled to the API's size cap before upload — flag code that loads or holds large attachment bytes longer than needed.
- **Parallelism**: the apply step traverses sequentially for safety; bounded parallelism (`parTraverseN`) is a deliberate choice, not a default — flag unbounded `parTraverse` over external calls.

### Style

Only flag style issues that violate documented guidelines. Anything not in the style guide is personal preference and must not block a review. Never suggest extracting literals into constants unless they are used more than once.

## Principles

- **Balance progress with quality.** Don't delay a PR for days because it isn't perfect. Balance the need to make forward progress against the importance of the suggested changes.
- **Keep ego out of the process.** Share alternative solutions with different tradeoffs, but don't block a PR just because you would have done it differently.
- **Use conventional comments.** Use types like `blocking`, `issue`, `suggestion`, `nitpick`, `question`, and `thought` to clearly communicate the weight of feedback.
- **Trust the author.** Reviewers should trust that the author will create follow-up PRs to address non-blocking feedback.
- **Durable domain answers go in code; implementation answers stay in the PR.** If a reviewer's question is about a business/domain decision, capture the answer as a one-line code comment so it outlives the PR. Questions about *how* or *why* the code is written are answered in the PR thread, not the source.
