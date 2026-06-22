# superfaktura-batteries

A command-line tool that automates business bookkeeping against the
[Superfaktura.sk](https://www.superfaktura.sk) API.

## Goal

Turn a bank statement into correctly-recorded expenses, with receipts attached — with a human check in the middle:

- **Create expenses** from a Tatra banka transaction-export CSV.
- **Attach receipts/invoices** (PDF / JPG / PNG / HEIC) from a folder, paired to the right expense.
- **Plan first**: analyse the inputs and write a reviewable plan (what it *would* create/attach); you review and edit it;
  only then does `apply` make any changes. Re-runs are idempotent.

The two capabilities compose or run independently (expenses only, or receipts-only against existing expenses).

Optionally, a **rules file** (`--rules`) rewrites expense names and attaches fixed files automatically: each rule
matches a transaction by exact/partial name or recipient IBAN and can rename it (with a `{date}` placeholder) and/or
attach a specific file. See [`docs/pdr.md`](docs/pdr.md#name-rewrite--fixed-attachment-rules).

## Architecture

Scala 3 + cats-effect, functional-core / imperative-shell, tagless final:

- **Pure core** (`core`) — domain model and business logic (`ExpensePlanner`) as pure, total functions over immutable
  data. No side effects.
- **Algebras & stores** — `trait …[F[_]]` interfaces over side-effecting dependencies (`SuperfakturaAlgebra`,
  `PlanStore`, …). Live interpreters live in `cli`; tests bind `…Stub` interpreters. No mocking library.
- **Programs** — effect-polymorphic business logic composing algebras; the concrete effect (`IO`) appears only in
  `Main` (`cli`), which loads config and wires everything via `given` instances.

Full design, conventions and API details: [`docs/pdr.md`](docs/pdr.md).

## Requirements

- JDK 21+
- [sbt](https://www.scala-sbt.org/) (launcher; the build pins sbt 1.12.x via `project/build.properties`)

## Configuration

Credentials are read from the environment (never committed). `cli/src/main/resources/application.conf` maps these env
vars and provides defaults:

| Env var | Meaning |
|---------|---------|
| `SUPERFAKTURA_API_EMAIL` | API account email |
| `SUPERFAKTURA_API_KEY` | API key |
| `SUPERFAKTURA_API_URL` | Base URL (use `https://sandbox.superfaktura.sk` to test) |
| `SUPERFAKTURA_COMPANY_ID` | Company id |
| `ANTHROPIC_API_KEY` | Anthropic key for receipt OCR (Claude vision); only used with `--receipts` |
| `ANTHROPIC_API_URL` | Anthropic base URL (default `https://api.anthropic.com`) |
| `ANTHROPIC_MODEL` | Model for OCR (default `claude-haiku-4-5-20251001`) |

Locally, put them in a git-ignored `.envrc` (loaded by [direnv](https://direnv.net/)) or export them in your shell.

> **Receipts leave your machine.** With `--receipts`, each receipt image/PDF is sent to the Anthropic API for OCR, and
> the matched receipt is uploaded to SuperFaktura as the expense attachment. Both API URLs are required to be `https`.

## Run

```bash
# Analyse inputs and write a plan (makes no changes):
sbt 'cli/run plan --csv statement.csv --receipts ./receipts'

# Apply name-rewrite / fixed-attachment rules while planning:
sbt 'cli/run plan --csv statement.csv --rules rules.json'

# Execute a reviewed plan:
sbt 'cli/run apply --plan plan.json'

# Help:
sbt 'cli/run --help'
```

## Test

```bash
sbt test                                          # compile + run tests
sbt 'core/testOnly superfaktura.ExpensePlannerTest'   # a specific suite
```

Formatting and linting are enforced: `sbt scalafmtAll` formats, `sbt scalafmtCheckAll` verifies, and the compiler runs
with `-Werror -Wunused:all`. A git-ignored pre-commit hook (`.githooks/`, enabled via
`git config core.hooksPath .githooks`) blocks unformatted commits.
