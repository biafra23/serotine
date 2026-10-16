# Architecture

Serotine is laid out as a clean architecture with MVVM in the presentation
layer. This page is the contract every change is held to (the reviewer reads
it through CLAUDE.md); the skeleton's two vertical slices — the Home screen
listing the managed accounts, and the seed-phrase screen that adds one — show
each rule in code.

## The modules and the dependency rule

```
                 ┌──────────────┐        ┌──────────────┐
                 │ android-app  │        │ desktop-app  │      hosts: composition roots
                 └──────┬───────┘        └──────┬───────┘
                        │  build AppGraph from   │
                        ▼  :data, render :ui     ▼
        ┌──────────────────────┐        ┌──────────────────────┐
        │         ui           │        │         data         │
        │ screens, ViewModels, │        │ adapters behind the  │
        │ UiState, AppGraph    │        │ ports (Myotis, …)    │
        └──────────┬───────────┘        └──────────┬───────────┘
                   │                               │
                   ▼                               ▼
               ┌──────────────────────────────────────┐
               │                domain                │
               │  entities, value objects, ports,     │
               │  use cases — pure Kotlin             │
               └──────────────────────────────────────┘
```

**Dependencies point inward, and only inward.**

- `:domain` depends on nothing of ours and on no framework (coroutines' `Flow`
  is the one library in it). It has no Android target on purpose: Android
  consumers resolve its JVM variant, as they do kotlinx-coroutines, so nothing
  in it can ever import `android.*` (and that variant emits Java 17 class
  files, since Android dexes it).
- `:data` depends on `:domain`. It implements the ports (`AccountRepository`,
  `KeyStore`, and `NativeBalances` and `LightClient` over the embedded Myotis
  engine) and owns every data source.
- `:ui` depends on `:domain`. A ViewModel takes **use cases**, never a
  repository, and never sees `:data`.
- The hosts depend on `:ui` and `:data` and are the **only** places that know
  which adapter implements which port. Each has one composition root
  (`SerotineApplication` on Android, `main()` on desktop) that builds an
  `AppGraph` and hands it to `App`.

Anything that violates the arrows — a screen reaching for a repository, a use
case importing a Compose or Android type, `:data` depending on `:ui` — is a
review finding, whatever it saves.

## Packages

| package | contents |
|---|---|
| `eth.serotine.domain.model` | entities and value objects (`Account`, `Address`, `AccountKind`) — immutable, validated in `init`/factory |
| `eth.serotine.domain.repository` | the ports, as interfaces named after what they manage |
| `eth.serotine.domain.usecase` | one class per user-visible capability, `operator fun invoke` |
| `eth.serotine.data.<area>` | adapters, one package per area (`account`, `keys`, `engine`; later `safe`) |
| `eth.serotine.ui.<feature>` | one package per screen: `<Feature>Screen`, `<Feature>ViewModel`, `<Feature>UiState` |
| `eth.serotine.ui.platform` | the shared UI's `expect`/`actual` seams (keeping a window out of screen capture) |
| `eth.serotine.ui.theme` | Material 3 theme |
| `eth.serotine.android`, `eth.serotine.desktop` | the hosts |

## MVVM, the contract

- **A screen is a pure function of its UiState.** `HomeScreen(state)` renders
  any `HomeUiState` and holds no state of its own beyond transient UI details
  (scroll position, an expanded row). A UiState is a sealed interface or an
  immutable data class; its leaves are display-ready values (strings, enums),
  never a `Flow`, a repository or a platform type.
- **One `<Feature>Route` binds the screen to its ViewModel** —
  `collectAsStateWithLifecycle()`, plus the glue between the screen's lifecycle
  and its ViewModel: a state that ends the screen (an import that succeeded)
  becomes its navigation callback, and a screen that stops being shown becomes
  an intent (the seed screen drops its words); nothing else. It is the only
  composable that knows the ViewModel exists, which keeps every other screen
  renderable in a test with a hand-made state.
- **Navigation is one `NavHost`, in `App`**, with a destination per screen. A
  screen's ViewModel is created by `viewModel {}` inside its destination, so
  the destination's back-stack entry owns it: it survives configuration
  changes while the screen is on the back stack and is cleared when the
  screen is popped. Routes say where the user wants to go through callbacks
  (`onAddAccount`, `onBack`); only `App` holds the `NavController`.
- **A ViewModel exposes `StateFlow<UiState>` and intent methods.** It derives
  state from use cases with `stateIn(viewModelScope, WhileSubscribed(5s), …)`,
  handles user intents by calling use cases, and holds no platform reference
  (no `Context`, no `Window`). It is the multiplatform
  `androidx.lifecycle.ViewModel`, created through `viewModel { graph.… }` so
  the host's `ViewModelStoreOwner` scopes it.
- **Use cases are the UI's only door into the domain.** A ViewModel's
  constructor lists what the screen can do; adding a capability adds a use
  case, not a repository call.
- **Errors are state.** A failure the user must see becomes a UiState variant
  (`Error(message)`), not an exception that escapes a coroutine.

## SOLID, as it applies here

- **Single responsibility** — a ViewModel per screen, a use case per
  capability, an adapter per data source. A class whose name needs "and" is
  two classes.
- **Open/closed** — behaviour varies through new implementations of a port or
  new UiState variants, not through flags threaded through existing code.
- **Liskov** — an adapter honours its port's contract in full, including the
  documented failure behaviour (`observeAccounts()` never fails; a port that
  can fail says so in its KDoc and its callers handle it).
- **Interface segregation** — ports are small and named for one concern.
  `AccountRepository` manages accounts; signing, balances and Safe
  transactions get ports of their own rather than one `WalletRepository`.
- **Dependency inversion** — `:domain` defines the ports; `:data` depends on
  them, never the other way round. No service locator, no static singletons:
  constructor injection from the composition root. (Manual wiring, no DI
  framework, until the graph is too large to read in one file — then a
  decision for the owner, not a drive-by dependency.)

## Kotlin conventions

- Immutable by default: `val`, `data class`, `List` not `MutableList` in any
  signature; `copy` for change.
- Value classes for domain identifiers (`Address`), validated at construction
  so an invalid one cannot exist.
- Structured concurrency only: `viewModelScope`, `runTest` in tests, never
  `GlobalScope`; `Flow` for streams, `suspend` for one-shot calls; no blocking
  on the main thread.
- `expect`/`actual` for the few platform seams, in the smallest source set
  that needs them; the common code never branches on the platform.
- KDoc on every public type: what it is, what it guarantees, what it never
  does. Comments say why; the code says what.
- Formatting: the official Kotlin style (`.editorconfig`), 120 columns,
  trailing commas on multi-line declarations and calls.

## Tests, per layer

| layer | kind | where |
|---|---|---|
| `:domain` | pure unit tests of value objects and use cases with fakes | `domain/src/commonTest` |
| `:data` | adapter tests against the port's contract | `data/src/commonTest` |
| `:ui` ViewModels | state-machine tests with `runTest` + a fake use case input, `Dispatchers.setMain` | `ui/src/commonTest` |
| `:ui` screens | headless Compose UI tests of the stateless screens (`createComposeRule`) | `ui/src/desktopTest` |
| hosts | the composition root wires and the app boots (an instrumented smoke test once there is an engine to boot) | per host |

`./gradlew build` runs all of them on the desktop JVM and as Android unit
tests. A test that needs a device or a network is `@Ignore`d by default and
documented.

## Adding a feature — the checklist

1. **Domain first.** The entity or value object, the port it needs (or an
   extension of one), the use case. Tests with a fake port.
2. **Adapter.** Implement the port in `:data`; test it against the port's
   contract.
3. **UiState + ViewModel.** The sealed state, the ViewModel mapping use-case
   output to it, the intents. Test the state transitions.
4. **Screen + Route.** The stateless screen, a UI test per state, the Route.
5. **Wire it.** `AppGraph` gets the ViewModel factory; the hosts supply the
   new adapter in their composition roots.
6. The PR describes the feature along these five lines, so a reviewer can
   check each layer against this page.
