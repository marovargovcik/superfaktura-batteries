---
name: cats-effect-concurrency
description: >
  Guidelines for planning, reviewing, or writing code that uses low-level cats-effect concurrency primitives:
  Ref, Semaphore, Deferred, AtomicCell, fibers (.start, .join, .cancel),
  cancellation (uncancelable, onCancel, bracket, guaranteeCase, poll), Queue, Topic, Resource.make.
  NOT for high-level combinators (flatMap, map, parTraverse, parMapN, traverse, sequence).
---

# cats-effect Concurrency Guidelines

### 1. Ref Safety

- **NEVER** use `ref.get` followed by `ref.set` — this is a race condition (get-then-set bug)
- Use `update`, `modify`, `getAndUpdate`, or `updateAndGet` for atomic read-modify-write
- `set` is only safe when you don't need the previous value or when you have external synchronization
- `Ref` must only store immutable data — mutable collections inside a `Ref` defeat atomicity. `MapRef` can help reduce contention.
- For high write contention, consider `AtomicCell` (mutual exclusion) over `Ref` (optimistic retry)

### 2. Semaphore Safety

- Prefer `semaphore.permit.surround` over manual `acquire`/`release` — the latter risks leaking permits on errors/cancellation
- In stream contexts, use `Stream.resource(semaphore.permit)` rather than manual `acquire` + `onFinalize(release)`

### 3. Deferred Safety

- Single-completion only — `complete` returns `false` if already completed
- `get` blocks (semantically) until completion — never call `get` inside `uncancelable` without `poll`
- Use `tryGet` for non-blocking checks

### 4. Fiber Safety

- **Raw fibers (`.start`/`.join`/`.cancel`) should almost never be used.** If raw fibers are truly necessary, justify with a comment explaining why.
- When using `.start`, always ensure the fiber is joined or canceled (leaked fibers are resource leaks)
- `join` returns `Outcome` with three cases: `Succeeded`, `Errored`, `Canceled` — handle all three
- Consider using `Supervisor` for managed fiber lifecycles

### 5. Cancellation Safety

- `uncancelable` blocks must use `poll` for any potentially blocking operations — otherwise the fiber can deadlock
- Pattern: `uncancelable(poll => poll(blockingOp).onCancel(cleanup))`
- Cancellation bypasses `handleError`/`onError` — use `onCancel` or `guarantee` for cancellation cleanup
- Cancellation is irreversible: once observed, it must be respected
- Nested `uncancelable` blocks: inner `poll` only undoes the inner `uncancelable`, not outer ones

### 6. Resource/Bracket Safety

- `bracket` guarantees release runs on success, error, AND cancellation
- With multiple resources, use `Resource` (not nested `bracket`) to ensure proper cleanup ordering
- `Resource.make(acquire)(release)` + `.use` (or `.surround` when the function argument to `.use` can be ignored) is the gold standard
- Inside `Resource.use`, cancellation triggers the release automatically
- Avoid nesting calls to `Resource.use`, rather use `flatMap` to combine several resources into one and `use` on that

### 7. Queue/Topic

- Use bounded queues (`Queue.bounded`) to prevent unbounded memory growth
- `offer` on a full bounded queue blocks semantically — be aware of backpressure
