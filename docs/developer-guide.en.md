# Developer Guide

[简体中文](developer-guide.zh-CN.md) · [Documentation](README.md) · [User guide](user-guide.en.md) · [Development reference index](development/README.md)

This guide helps contributors set up the project, locate responsibilities, and preserve safety boundaries. It links to detailed designs, implementation notes, and acceptance records in the development references.

## 1. Environment and build

- A Java 21 JDK.
- This checkout targets Minecraft 1.21.1 and NeoForge 21.1.235; [gradle.properties](../gradle.properties) is the version source of truth.
- Use the bundled Gradle Wrapper instead of installing Gradle separately. Its version is pinned in the [wrapper configuration](../gradle/wrapper/gradle-wrapper.properties); [build.gradle](../build.gradle) defines the toolchain, dependencies, and source sets.

Build from the repository root:

```text
# Windows PowerShell
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

Artifacts are written to `build/libs/`. Initial dependency and game-asset setup needs network access; use offline mode only with a complete cache. Do not commit generated artifacts, development worlds, or caches into the source tree.

Import the repository as a Gradle project in your IDE and use Java 21.

## 2. Source layout and module responsibilities

| Location | Responsibility | Boundary |
| --- | --- | --- |
| `src/main/java/` | Mod implementation and GameTests discovered through NeoForge | Must not depend on integrationTest; dedicated servers must not load client rendering classes |
| `src/main/resources/` | Mod metadata, languages, and narrowly scoped Mixin configurations | Do not declare the whole production package root as a Mixin package |
| `src/test/` | JUnit unit tests | Pure-model checks do not replace real menu, transaction, or network acceptance |
| `src/integrationTest/` | Real-client and dedicated-server cache harnesses | A separate source set, excluded from the release JAR |
| `tests/` | Regression entry point, coverage boundaries, and datapack instructions | Maintain test commands and groups here only |

The main entry points are below. Full relationships are documented in the [architecture](development/02-architecture.md) and [production behavior audit](development/17-production-behavior-audit.md), currently in Chinese.

| Responsibility | Entry points | State ownership and threading |
| --- | --- | --- |
| Environment discovery | [EnvironmentScanner](../src/main/java/org/craftablemc/craftable/environment/EnvironmentScanner.java) | The server game thread discovers loaded, usable, authorized sources; live endpoints are never given to clients |
| Recipe knowledge | [CraftingRecipes](../src/main/java/org/craftablemc/craftable/recipe/CraftingRecipes.java) | Each side owns its generation of the static catalog, and only the server persists static relations; asynchronous knowledge I/O receives only immutable snapshots and rejects stale-generation writes |
| Shared planning core | [CraftSearch](../src/main/java/org/craftablemc/craftable/planner/CraftSearch.java), [ResourceLedger](../src/main/java/org/craftablemc/craftable/planner/ResourceLedger.java) | Computes against frozen input and conserves inputs and outputs; the core must not reference client classes |
| Client computation and presentation | [ClientBrowsePlanner](../src/main/java/org/craftablemc/craftable/client/recipebook/ClientBrowsePlanner.java), [ClientRecipeStatusStore](../src/main/java/org/craftablemc/craftable/client/recipebook/ClientRecipeStatusStore.java) | The client thread owns tasks, accumulated allowances, and dynamic results; caches and graph projections are not execution authorization |
| Authoritative review and commit | [CraftingService](../src/main/java/org/craftablemc/craftable/execution/CraftingService.java), [CraftWitnessValidator](../src/main/java/org/craftablemc/craftable/execution/CraftWitnessValidator.java), [CraftingTransaction](../src/main/java/org/craftablemc/craftable/execution/CraftingTransaction.java) | The server game thread freshly revalidates; all material extraction and delivery converge on one transaction |
| Short-lived sessions and networking | [CraftingSessions](../src/main/java/org/craftablemc/craftable/execution/CraftingSessions.java), [CraftablePayloadHandlers](../src/main/java/org/craftablemc/craftable/network/CraftablePayloadHandlers.java) | Own sequence/token/source bindings and network handoff respectively; world reads and writes return to the server game thread |
| Configuration and UI | [ConfigSettings](../src/main/java/org/craftablemc/craftable/config/ConfigSettings.java), [CraftableSettingsScreen](../src/main/java/org/craftablemc/craftable/client/CraftableSettingsScreen.java) | Registered specs are the only source of truth; ordinary screens own bounded drafts, and remote rules come only from the current authoritative snapshot |

<a id="identity"></a>

### Project identity and compatibility

CraftableMC maintains the project. The Maven group is `org.craftablemc`, and the Java package root is `org.craftablemc.craftable`. The mod ID and resource namespace are still `craftable`, and the metadata name is Craftable. In-game features use Recipe Book in English and 配方书 in Chinese.

The two Mixin configurations are confined to `client.mixin` and `menu.mixin`. When changing packages, check the mod entry class, Mixin declarations, reflection strings, test discovery, and source links together. Check the clean-build JAR and IDE output for stale classes.

Development builds do not retain compatibility layers for old Java packages, configuration formats, or calling signatures. If you depend on an old package name, update your imports and recompile. When structures change, update the callers and current tests together rather than hiding missing rule inputs behind defaults. A Java package change should not also change the mod ID, saved worlds, or recipe IDs.

## 3. Safety contracts to preserve

- Clients submit intent and read-only witnesses, never world writes, slot mutations, or authorized output counts. The server rechecks real sources, permissions, recipes, material freshness, remainders, and combined capacity. Local feasibility cannot replace these checks.
- Keep complete success, sufficient negative evidence, and unknown separate. Truncation, exhausted budgets, or a client's failure claim cannot prove missing materials or authorize partial execution. An unproven maximum crafting quantity is only a lower bound, not execution authorization.
- A normal single craft must not automatically consume partial materials after failure. Partial crafting requires explicit intent and the intersection of world rules with stricter personal choices. Complete-witness requests cannot bypass validation by downgrading or omitting fields.
- A review token is a single-use, short-lived decision record, not reserved materials or a plan executable across time. Review-time and pre-commit checks protect different time boundaries and must not be merged merely to remove repetition.
- One transaction synchronously rechecks, extracts, and publishes. Failure compensates its own inputs and dropped entities; notification or statistics failures after commit must not refund already delivered items. Arbitrary external side effects of third-party hooks are not covered by the rollback guarantee.
- Time, state, step, byte, and memory limits are independent. Raising a representable step limit must not increase computation allowances. Pausing, renewing a lease, or cancelling and reopening must not reset accumulated consumption. Cooperative slices are not preemptive hard-real-time guarantees.
- Recipe-book displays, detail graphs, and material grouping are projections only. They must not change real requirement paths, exact components, sources, or consumption. Do not automatically enable third-party sources, machines, or portable contents without explicit execution contracts.

The underlying references are [planning and execution semantics](development/03-planner-semantics.md), the [client-planning contract](development/14-m4-implementation.md#browsing), and the [configuration contract](development/04-compatibility-and-config.md), currently in Chinese. Numbered documents contain both current constraints and scope-labeled history; they are not all obsolete archives.

## 4. Configuration, cancellation, and invalidation

Ordinary and professional screens must share registered definitions, defaults, validation, and persistence: they are two presentations of the same configuration. The server owns the world partial-crafting mode and its single recipe-ID whitelist. Personal modes can only tighten world rules and refer to that same whitelist; do not copy it or add implicit permissions.

Done on an ordinary screen validates and saves only changed fields. Cancel discards that draft but does not undo changes already saved through the professional screen.

World-rule writes must recheck the actual context at the write boundary, including a local single-player world not opened to LAN and a loaded SERVER spec. Disabled controls are not substitutes for guards on saving, Undo/Redo/Reset, or delayed callbacks. Professional screens retain native immediate registered values and undo semantics.

Never substitute local SERVER values while remote rules are unsynchronized.

Leases and browse snapshots must come from the same rule capture. Keep static knowledge separate from player resources, permissions, and execution tokens.

On reload, rule/material/capacity changes, menu changes, disconnection, or world changes, each owner clears its own derived evidence. Menu changes clear derived state. Cancellation discards obsolete queued intent and late replies; it does not retroactively cancel a commit already sent to the server.

See [settings UI maintenance](development/04-compatibility-and-config.md#settings-ui-maintenance), currently in Chinese, for widget identity, delayed slider submission, layout, and save guards.

## 5. Validation and diagnosis

The single recommended entry point for local automated regression and explicit diagnostics is `tests/run.ps1`. Groups, arguments, execution evidence, and local log locations are maintained in the [test instructions](../tests/README.md). See the [coverage boundaries](../tests/coverage.md) for the risks assertions protect and [datapack instructions](../tests/datapacks/README.md) for fixture operations.

CI uses Gradle's standard `build` lifecycle, including standard unit tests. It does not implicitly start real-client, GameTest, or dedicated-server cache suites. A cache hit or successful build does not prove that every suite actually ran. Filtered tests and diagnostics must not report a full-suite pass.

Choose regressions based on what the change affects. For example, changing the chain-step limit requires checking client and server planning, witnesses, display projections, decoding, and memory limits—not just configuration I/O. Timing diagnostics add overhead, so a diagnostic pass does not replace a regular run with diagnostics off. For performance changes, distinguish first-use work from reuse of existing results.

For a change, report the groups actually run, their results, and uncovered scope. Separate real behavior fixes from equivalent refactors and add a counterexample that would fail without the fix. Record build identity, failures, and manual results in the relevant acceptance record, with the scope of each run. The [Pre-M5 acceptance record](development/18-pre-m5-acceptance.md) is an example. Local evidence under `run/` and `logs/` is not automatically a repository attachment. Do not upload player worlds or private information without authorization.

## 6. Contributions and documentation maintenance

- Read the relevant design basis before changing code. Keep responsibilities and state owners explicit. Do not merge safety checks across different time boundaries for deduplication, expose private production entry points for tests, or introduce a large generic framework.
- Preserve other people's working-tree changes. Event subscriptions, Mixins, and network handlers are framework entry points; the absence of an ordinary Java caller does not justify deletion. Dead-code cleanup needs evidence from real call chains and consumers.
- Update specifications and current contracts in place. Do not prefill plan success. Acceptance records retain real failures and build identities; ADRs and history retain their original context. Establish real execution contracts before adding capabilities, and do not describe future designs as implemented features.
- Maintain both public guide languages together. Change test commands only in the test index. The [development reference index](development/README.md) classifies detailed material and its current/historical status; do not expand the root README back into an engineering archive index.
- Code, assets, and dependencies need applicable permissions and retained notices. This project uses the custom [CraftableMC Community License](../LICENSE) with a [Chinese translation](../LICENSE.zh-CN).

<a id="code-comments"></a>

### Code and test comments

- Place important Chinese and English comments together with the same meaning. Explain responsibility, side effects, threads, ownership, budgets, cancellation, and failure handling. This applies within methods as well as at the class level; obvious code and ordinary getters do not need line-by-line explanations.
- Explain why a test uses its counterexample, what error makes it fail, and what its inputs, clock, or isolation conditions cannot prove. Simple input/output assertions need not be described twice. Do not rename English methods, failure messages, or stable log markers merely for bilingual presentation.
- For a comment-only change, compare against the working tree as it was at the start. Check that code outside comments, including literals, is unchanged, and do not count pre-existing uncommitted behavior changes as part of this edit.
