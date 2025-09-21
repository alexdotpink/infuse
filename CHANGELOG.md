# Changelog

## 1.5.0 — 2025-09-21

### Overview
- First-class scoping is now part of the core injector, with request/session lifecycles, custom scope registration, and deterministic shutdown to support multi-tenant applications.
- Binding and module APIs gained qualifiers, richer DSL helpers, install-time composition, and package scanning rules so large graphs can be wired declaratively.
- Injector internals were hardened for concurrent use, with per-thread resolution state, reflective plan caching, constructor memoization, and descriptive diagnostics when cycles or configuration issues surface.

### Dependency Injection Core
- Optional injection flows (`@Inject(optional = true)`) now return `null` instead of exploding, guard against primitive targets, and consistently apply across constructors, fields, and methods (`7978fbf`, `a747d82`).
- Method injection is planned alongside field injection and invoked per lifecycle priority, with `@PostConstruct` now running after dependency wiring and inside the correct resolution scope (`899275c`, `2a70386`, `b877d40`).
- Direct constructor instantiation helpers were expanded so the injector can build via explicit `Constructor` handles, respect annotated constructors first, and validate argument compatibility up front (`6076c92`, `a747d82`).

### Binding & Module API
- `BindingBuilder` gained fluent helpers for implementations, suppliers, multi-argument factories, constructor bindings, and scoped variants, while `Binding` now tracks qualifiers/scope in a `BindingKey` (`a747d82`, `0485adc`, `2707492`).
- Qualifier support landed via `@Qualifier` and `@Named`, enabling multiple bindings per type and qualifier-aware lookups with clear conflict errors (`0485adc`, `696722e`).
- Duplicate binding registration is prevented by resetting modules between installs and clearing produced bindings after configuration, and modules can now install submodules safely (`561083c`, `938e69b`).
- Package scanning is configurable through `PackageScanOptions` and rule sets, auto-registering scoped or singleton components discovered under a package while honoring custom filters (`72a7ee1`).

### Scopes & Lifecycle
- Request and session scopes were introduced with memoizing providers, thread-safe state, and integration tests covering destruction hooks; `BindingScope` understood new canonical names and fluent helpers (`2707492`, `6118584`).
- Scoped instances are tracked centrally so destruction order reverses creation order, and eager singleton boot now records scoped instances only after successful lifecycle completion (`6118584`, `b877d40`).
- `SingletonProvider` now uses double-checked locking for lazy constructors and exposes eager flags safely, eliminating race conditions during concurrent provisioning (`a5dd2d6`).

### Performance & Concurrency
- Injection plans moved to `ConcurrentHashMap`, fields/methods are marked accessible during plan assembly, and post-inject scheduling reuses cached plan data (`91a2600`, `728284e`, `b877d40`).
- Constructor resolution is memoized per type with arity buckets and argument signature caches, while constructor argument resolution precomputes metadata to avoid recreating contexts on every call (`907dd66`, `d25d059`).
- Resolution scopes became per-thread `ThreadLocal` stacks with qualifier-aware cycle tracking, ensuring injectors are safe across parallel invocations (`91a2600`, `696722e`).

### Diagnostics & Error Handling
- Circular dependencies now surface as rich `IllegalStateException`s describing the resolution path, qualifiers, and injection points, with unit coverage for both failure and qualifier-differentiated success cases (`696722e`).
- Configuration and provisioning failures emit domain-specific exceptions (`ConfigurationException`, `ProvisionException`) instead of raw `RuntimeException`, improving caller error handling (`88d28b0`).

### Testing & Tooling
- Added `ScopeIntegrationTest` to exercise request/session/custom scopes, and `CircularDependencyTest` to validate diagnostic messaging (`2707492`, `696722e`).
- README highlights the new scoping capabilities, and the Gradle version bump tags the 1.5.0 release (`2707492`, `5cd7ec8`).

### Commit Details
- `5cd7ec8` – Bump `build.gradle` to version 1.5.0.
- `88d28b0` – Introduce `InfuseException`, `ProvisionException`, `ConfigurationException`, and switch injector flows to the new hierarchy.
- `d25d059` – Cache constructor argument metadata and reuse context objects via `ConstructorArgumentPlan`.
- `907dd66` – Add `ConstructorCache` for memoized constructor selection keyed by argument signatures.
- `696722e` – Enhance circular dependency detection with qualifier-aware tracking and add `CircularDependencyTest`.
- `b877d40` – Reuse cached injection plans when booting eager singletons and schedule `@PostInject` via priority-sorted invocation records.
- `728284e` – Mark reflective fields/methods accessible during plan creation to avoid per-injection `setAccessible` calls.
- `91a2600` – Replace mutable caches with concurrent structures and move resolution scopes to per-thread state.
- `2707492` – Introduce request/session scope infrastructure, scope providers, and lifecycle tests.
- `a5dd2d6` – Harden `SingletonProvider` with double-checked locking around instance creation.
- `6118584` – Track scoped instances for deterministic destruction ordering during `Injector#destroy()`.
- `2a70386` – Ensure `@PostConstruct` runs immediately after injection within the active scope.
- `938e69b` – Allow modules to `install` other modules safely by copying/clearing bindings.
- `72a7ee1` – Add package scanning rules, `@Scope` meta-annotation, and configurable scan options.
- `561083c` – Reset module bindings between configurations to prevent duplicate registrations.
- `0485adc` – Add qualifier annotations, `BindingKey`, registry updates, and qualifier-aware resolution.
- `a747d82` – Expand `BindingBuilder` DSL and centralize optional injection checks in `InjectionUtils`.
- `59f9d1d` – Tighten `Binding` equality/hashCode to include provider identity.
- `899275c` – Invoke method injection points as part of the injection plan lifecycle.
- `7978fbf` – Support optional injections with null-safe semantics across constructors/fields/methods.
- `6076c92` – Prefer annotated constructors and validate provided arguments before invocation.
- `77d36de` – Overhaul injector internals, laying groundwork for injection plans, lifecycle caches, and Gradle wrapper tweaks.

