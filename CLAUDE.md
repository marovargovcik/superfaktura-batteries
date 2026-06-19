# Project Guidelines

## Core Principles

> Adapted from [Andrej Karpathy's CLAUDE.md](https://github.com/forrestchang/andrej-karpathy-skills/blob/main/CLAUDE.md).
> These shape *how* you collaborate before any language-specific rule kicks in.

### Think Before Coding

- **State assumptions explicitly.** Name what you're assuming; ask if uncertain rather than guessing.
- **Surface multiple interpretations.** When a request is ambiguous, lay out the options instead of silently picking one.
- **Advocate for simplicity.** Push back on unclear or over-scoped requirements before writing code.
- **Stop when confused.** Don't code through confusion — identify what's unclear and resolve it first.

### Simplicity First

- **Implement only what's asked.** Skip speculative features beyond stated requirements.
- **Avoid premature abstraction.** No abstractions for single-use patterns; three similar lines beats a premature helper.
- **Skip unnecessary flexibility.** No configurability, feature flags, or backwards-compatibility shims unless explicitly requested.
- **Minimize error handling.** Skip handling for scenarios that can't occur. Trust internal code; only validate at system boundaries.
- **Ruthlessly trim excess code.** If 200 lines could be 50, rewrite.

### Surgical Changes

- **Preserve adjacent code.** Don't "improve" unrelated code, formatting, or comments while making your change.
- **Don't refactor stable code** unless explicitly asked.
- **Match existing codebase conventions** over personal preference.
- **Flag dead code separately** rather than deleting it unprompted.
- **Clean only your own orphans.** Remove imports/variables your change made unused; preserve pre-existing dead code.

### Goal-Driven Execution

- **Define verifiable success criteria.** Turn vague requests into testable goals with clear checks.
- **Plan multi-step tasks** with verification points before implementing.
- **Use tests to verify fixes.** When fixing a bug, write a test that reproduces it before implementing the fix.
- **Build independently with clear criteria.** Strong success metrics enable autonomous work without constant re-clarification.

---

## General Development Practices

- All changes must be covered by tests. Planning must include identifying which properties and code paths to test.
- Always use GNU sed via `gsed` instead of `sed` for cross-platform compatibility.
- Avoid using `-C` for `git` unless strictly necessary.
- Avoid using `--repo` for `gh` unless strictly necessary.
- When analyzing code, always check test files for additional context on usage patterns.
- All Scala warnings must be fixed. Leaving them unresolved will cause CI to fail.
- **Code vs Documentation**: When code behavior contradicts documentation, trust the code as the source of truth.

## Building & Testing

- Scope commands to specific subprojects using slash-syntax: `sbt <subproject>/<command>` (e.g. `sbt core/test`).
- sbt 2.0 runs in client mode (the `sbtn` thin client) by default once `project/build.properties` pins `sbt.version`
  to 2.x — no `--client` flag needed (that was an sbt 1.x opt-in).

## Git Hooks

- A version-controlled `pre-commit` hook lives in `.githooks/` and is wired with `git config core.hooksPath .githooks` (run once after cloning).
- The hook runs scalafmt in check mode (`scalafmt --test`) on the staged `*.scala` files and **blocks the commit** if any are unformatted. Fix with `sbt scalafmtAll`, then re-commit.
- Keep the hook fast: it only checks formatting on staged files. Full linting (`-Werror -Wunused`) compilation is the CI gate, not the hook.
- Don't bypass it with `--no-verify` except for genuine emergencies.

## Force Pushing

- Force pushing to a branch is acceptable while the pull request is still in progress.
- Once someone has been asked to review it, do not force push unless explicitly asked to do so.

## Comments

- **Default to no comments.** Prefer self-explanatory code — clear names, small functions — over any comment. If you feel a comment is needed to explain *how* the code works, rewrite the code instead.
- **The only comment worth writing records a business/domain decision or external constraint** that the code itself cannot express — e.g. a real-world rule ("VAT sent as 0 — we record gross totals only") or an API/legal limit ("SuperFaktura caps attachments at 4 MB"). Keep it to one line where possible.
- **Never comment on the implementation.** How the code is written, and why it is written that way, is not documented in comments. That rationale belongs in commit messages or PR descriptions, not the source.
- **Never reference the conversation that produced the code.** No prompts, no "as discussed", no alternatives that were considered, no description of what just changed. A comment must stand on its own for a future reader who was never part of that exchange — if it only makes sense with that context, delete it.

## Commit Messages

- Write commit messages that are clear, concise, and explain the *why* of the change.
- Prefer a **commit-by-commit approach**:
  - Break changes into smaller, logical commits.
  - Each commit should represent one focused change, making it easier to review and, if necessary, revert.

## Code Review

- Detailed code review guidelines are in [`REVIEW.md`](./REVIEW.md).
- You are a staff engineer performing code review.
- The absolute highest priority is **security** — all API endpoints and request handlers must perform security checks.
- The secondary priority is correctness and backwards compatibility — including tests.
- The third priority is code style.
- The fourth priority is performance.
- Never suggest extracting literals into constants unless they are used more than once.

## Repeated Feedback

- If you notice that the user is repeatedly asking for similar changes or expressing frustration, suggest updating these instructions.

---

## Scala — Code Structure

**Tech stack**: Scala 3 with cats-effect for functional programming, sbt 2.0 for build management, ScalaTest for testing.

- **Pure functions first**: Put as much logic as possible in pure, easily testable methods. Write in functional style avoiding side effects.
- **All side-effecting code must be properly handled with cats-effect** — never perform side effects outside of `F[_]`.
- Use the **tagless final pattern** for all effectful dependencies:
  - Use context bounds for general type classes (`F[_]: Monad`)
  - Use `using` parameter clauses for algebras and other effectful collaborators (`(using thing: ThingAlgebra[F])`). Define instances with `given`.
- **Naming & layering (Baeldung vocabulary):** *Algebra* = the interface — a `trait …[F[_]]` over a side-effecting external dependency, named with the **`Algebra` suffix** (`SuperfakturaAlgebra[F]`). *Store* = an Algebra that reads/persists data over some storage (DB, files, …), named with the **`Store` suffix** (`PlanStore[F]`). *Interpreter* = an implementation — the live one a companion `given` named descriptively (`live`, `file`); test interpreters are `…Stub` (trait name + `Stub`). *Program* = effect-polymorphic business logic composing algebras/stores, named with the **`Program` suffix**.
  - Introduce an Algebra/Store only when you'd swap the implementation (e.g. for tests) or it touches a stateful resource (HTTP client, filesystem); otherwise use a Program or a plain pure function.
  - **Programs call algebras/stores; they never call programs.** Programs may compose other programs.
  - **Algebras are not type classes** — pass them via `using` parameters, never as context bounds or with `apply` summoners.
  - `Store` is only for the app's **own** persisted data (DB, files). An algebra over a third-party API is an `…Algebra` even when it writes — e.g. `SuperfakturaAlgebra` creates expenses but isn't a Store, since that data lives in an external system.
- Prefer the generic effect type `F[_]` over a concrete effect type. New abstractions should be `F[_]`-polymorphic.
- **External HTTP calls must retry transient failures** (5xx, connection reset/refused, timeouts) with exponential backoff. Apply the http4s `Retry` client middleware once at the client layer rather than hand-rolling retries per call.
- **Permission/auth checks must execute before any database operation (read or write).** Database reads are only allowed before the check if they are required to perform the check itself.
- Loading of configuration (e.g. `application.conf`) should only be done at application entry points.

## Scala — Code Style

- Use the `cats` library with `case class` for data representation. (`case class` is final by default in Scala 3 — don't add a redundant `final`.)
- Define methods on companion objects.
- Use `MonadThrow` or `ApplicativeThrow` type classes for error handling.
- Don't add empty parameter lists (`()`) to pure methods. Empty parameter lists indicate side effects.
- Always include return type annotations for non-anonymous functions.
- Use named parameters for "magic" argument values at call sites and for functions/classes with 4+ parameters. Exception: when all arguments are variables that align with case-class field names.
- For multi-line lambdas, prefer Scala 3's colon-with-indentation syntax:

  ```scala
  // Preferred
  someValue.map: x =>
    // ...

  // Avoid — wrapping a multi-line lambda in parens
  someValue.map(x =>
    // ...
  )
  ```

- Prefer pattern matching over `isInstanceOf`/`asInstanceOf`. Pattern match on individual case classes when extracting data; pattern match on subtypes only when you genuinely don't need the contents.
- When writing Scaladoc for function parameters, only include `@param` entries for parameters where you have actual documentation to add. Don't list every parameter with an empty description:

  ```scala
  // Preferred — only the params with real docs
  /** Fetch users by IDs.
    *
    * @param includeDeleted Include soft-deleted users in the result set.
    */
  def byIds(userIds: Set[UserId], includeDeleted: Boolean): List[User]

  // Avoid — listing params with no description adds noise
  /** Fetch users by IDs.
    *
    * @param userIds
    * @param includeDeleted Include soft-deleted users in the result set.
    */
  def byIds(userIds: Set[UserId], includeDeleted: Boolean): List[User]
  ```

- Always use the `override` keyword when implementing inherited definitions.
- When overriding an inherited method with a constant, use `val` instead of `def` to avoid recomputing it.
- Avoid numeric tuple selectors (`._1`, `._2`); destructure via pattern match. For tuples in for-comprehensions, destructure on the LHS:

  ```scala
  // Preferred
  for
    (foo, bar) <- getTuple
  yield foo + bar

  // Avoid
  for
    foo <- getTuple.map(_._1)
  yield foo
  ```

- Use `.empty` or `Nil` instead of empty collection constructors:

  ```scala
  // Preferred
  val xs = List.empty[String]

  // Avoid
  val xs = List()
  ```

- Model ADTs with Scala 3 `enum` by default:

  ```scala
  enum OrderStatus:
    case Pending, Shipped, Cancelled
  ```

- Use `sealed trait` + case classes/objects only when `enum` doesn't fit — e.g. when cases need non-uniform members or hierarchies that `enum` can't express. Don't add `extends Product with Serializable`; Scala 3's type inference no longer needs it.
- Prefer imports at the top of the file over inline fully-qualified class paths.
- Don't specify types in pattern matches unless a runtime type test is needed.

### For-comprehension hygiene

- Avoid nesting for-comprehensions. Extract the inner for-comprehension into a separate method.
- Avoid using `flatMap` inside for-comprehensions; use a separate `<-` binding instead.
- Avoid excessive combinators on the right-hand side. If the RHS is heavily chained (e.g. `.as(y).handleErrorWith(...)`), use `flatMap` directly instead of a for-comprehension.
- Avoid one-step for-comprehensions:
  - `for x <- program yield x` → `program`
  - `for _ <- program yield ()` → `program.void`
  - `for x <- program yield f(x)` → `program.map(f)`
- Don't wrap for-comprehensions in extra parentheses; bind to a value if necessary.

### Functional-style preferences

- Use `Option.when` instead of `if` expressions returning `Option`.
- Use `.as(value)` instead of `.map(_ => value)` when mapping to a constant.
- Prefer `Applicative[F].unit` or `F.unit` over `().pure[F]`.
- Use `.traverse_` and `.void` when the result is not needed.
- Use `.use_` and `.surround` when the argument is not needed.
- To bring a value into given scope inside a for-comprehension, use a `given` binding:

  ```scala
  for
    given Logger[F] <- makeLogger
    _              <- doWork[F]
  yield ()
  ```
- Never silence "unused" warnings with `val _ = unusedValue`.
- Use `groupedBy*` helper methods when building maps where you'd otherwise map before/after `toMap` or `groupBy`.
- Use `.flatTraverse` instead of `.traverse(...).map(_.flatten)` when each element yields a collection of the same type.

### Error handling

- Model domain failures as a single typed error `enum` extending `Exception`, raised via `MonadThrow` — keep it small; don't reach for web-app distinctions (user-vs-client, 403/404) this CLI doesn't have.
- Extract from `F[Option]` / `F[Either]` with cats stdlib (`.liftTo[F](error)`, `OptionT`) rather than a bespoke syntax layer.
- **Order error combinators as: extraction → retry → recovery.** Retry helpers react to a *raised* error, so chain anything that raises (turning `None`/`Left` into an error) *before* the retry, and anything that swallows (`.attempt`, `.handleErrorWith`, best-effort logging) *after* it. Put recovery first and the retry never fires; put it before extraction and the retry never sees the error.

### Function size

There are few absolute rules, but watch for these pitfalls:

- **Large jumps in abstraction levels** — e.g. inline email-validation logic inside a function whose primary concern isn't email.
- **Single Responsibility violations** — if it's hard to give the function a descriptive, succinct name, it's probably doing too much.
- **The God-comprehension** — a single large for-comprehension that combines logic from many places. Especially common in effectful code.

## Scala — Circe

- Use `Codec` whenever you need both a `Decoder` and an `Encoder`. Use `Encoder`/`Decoder` directly only if you need just one.
- Place codecs/encoders/decoders in the **companion object** of the same class.
- Never use `io.circe.generic.auto.*` for automatic derivation — it slows compilation and causes given conflicts. Use `semiauto` instead.
- When constructing `Json` manually, use Circe's `:=` operator from `io.circe.syntax.*` instead of `->` with `.asJson`:

  ```scala
  // Preferred
  Json.obj("key" := "value")

  // Avoid
  Json.obj("key" -> "value".asJson)
  ```

---

## Testing — General

- Write as few tests as possible that cover all the important properties of the code.
- Before writing any tests, identify the specific properties worth testing.
- Only write tests that cover **different** properties and code paths — no duplicates, no low-value tests.

## Testing — Scala

- Place tests in the `test` source root, mirroring the package structure with a `Test` suffix.
- Write in ScalaTest's `AnyFreeSpec` style.
- Run tests with `sbt <subproject>/test`.
- Prefer unit and property tests over integration tests.
- **Don't use mocking libraries.** Create simple constant interpreters / stubs for tagless-final algebras.
- Prefer **hard-coded values** for dates and IDs over `ZonedDateTime.now()` or `UUID.randomUUID()`.
  - Hard-coded times avoid flakiness from weekends, month/year boundaries, out-of-office hours, leap days, etc.
  - When a test legitimately needs a dynamic date (e.g. "a Monday in the future"), use `TemporalAdjusters` (e.g. `today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))`) instead of raw `now()` to avoid hitting problematic boundaries.

---

## Documentation

Documentation lives in `./docs`. Use **kebab-case** for filenames (e.g. `scala-style.md`, `writing-docs.md`). Group related docs in subdirectories.

### Required frontmatter

All documentation files **must** include YAML frontmatter with at least a `title`:

```yaml
---
title: Your Document Title
icon: 📝
---
```

- **`title`** (required): non-empty string.
- **`icon`** (optional): emoji.

### Style

- Use ATX-style headings (`#`, `##`, `###`).
- Always specify the language on code blocks (`scala`, `sql`, `bash`, etc.).
- Use `-` (dash) for unordered lists.
- Use [Mermaid](https://mermaid.js.org/) for diagrams. When specifying colors, ensure readability in both light and dark modes.
- Use GitHub-flavored Markdown callouts for important info: `> [!NOTE]`, `> [!WARNING]`, `> [!TIP]`.
- Keep lines to a maximum of **120 characters**.
- Use standard Markdown tables.

### Writing patterns

- For style guides, use a clear **"Do this / Don't do this"** pattern with concrete examples. Always justify *why*.
- For concept docs: start with context (why this exists, what problem it solves), provide visual aids (Mermaid), include examples, and answer common questions.
- Reference code with `file_path:line_number` when pointing at specific implementations.
- Add a file-level comment only when a non-trivial library file carries a business/domain purpose the code can't convey on its own (see [Comments](#comments)); never to narrate what the code does.
