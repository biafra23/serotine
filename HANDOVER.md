# Handover: simple UI, part 12 and the final PR

**Delete this file in your first commit.** It is a note from the desktop session, which hit repeated "Request failed" retries, to the cloud session taking over. It must never reach `feature/simple-ui` or `main`.

Branch: `claude/simple-ui-part12-ens-registries`, cut from Part 11's branch (`claude/simple-ui-part11-sync`) before #204 was merged.

## Where things stand (2026-10-29)

- `feature/simple-ui` is the multi-PR plan "simple mode, Detailed view opt-in" (docs/simple-ui.md, *Delivery*). Parts 0 to 11 are merged into it: #188–#197, #201 and #204 (Part 11, the sync line, merged by the owner).
- `main` has moved since the last merge into the feature branch. It has #205, and maybe more. Merge `main` into `feature/simple-ui` before the final PR. A clean merge may be pushed straight to the feature branch, after building it and running the affected tests. A merge that needs resolving goes through a part PR (`.claude/skills/feature-branch/SKILL.md`, step 4).
- #202 (trust.md's weak-subjectivity bound) and #203 (`gen_widget_icons.py`, which had turned main's CI red) are merged into `main`.

## Part 12: Watch address resolves a name on every registry that is on

This is the owner's request of 2026-10-29: myotis2.eth, registered on Sepolia only, showed "This name has no address" while Ethereum was on. The owner chose to land it as Part 12 of `feature/simple-ui`. docs/networks.md, *Names* ("Watch by name"), accepted by the owner's ruling of 2026-10-22, already specifies the behaviour, so this is a fix toward the accepted design, not a new rule:

- Resolve on Ethereum's and Sepolia's registries, whichever are on, never on Gnosis Chain.
- Each network's account takes its own registry's address. Gnosis Chain takes Ethereum's, never a testnet's.
- Different addresses are different accounts, and the "all networks" question is not offered for them.
- Every note names the registry that answered ("On Ethereum: no address\nOn Sepolia's test registry: resolves to 0x…"). A testnet's registry is always named as one, so a name never looks registered on mainnet.

**Done and committed on this branch (WIP commit):**
- `ui/.../watch/WatchViewModel.kt`:
  - `registries(on)` lists the registries; `lookups` keeps one `recipientStates` per registry;
  - `answerOn(network, text, lookups)` gives each network its registry and found address;
  - `detection` reads each `Card(network, address, detection)` at its own address;
  - `withCards` drops the question when the addresses differ;
  - `add()` resolves again per registry and watches each network at its own address (`watchOn(targets)`);
  - `addressNote(text, lookups, simple)` gives one line per registry, and a single registry's note names a testnet registry.
- Tests: `FakeNames.on[network to name]` gives per-registry answers. `WatchViewModelTest` covers:
  - one registry only (the myotis2.eth case);
  - the same address on both registries, which offers the question;
  - two addresses, where Gnosis takes Ethereum's.

  Three earlier tests had expectations that changed by design. All Watch tests pass.
- Previews: `WatchNameOnOneRegistryPreview` and `SimpleWatchNameOnOneRegistryPreview`. All previews render.

**Still to do in Part 12:**
1. **A single card does not name its network.** `WatchUiState.cardNetwork` is set but never drawn (grep it in `WatchScreen.kt`). With a Sepolia-only answer, the card should say it is Sepolia's account, in both views. Draw it, and cover it with a test and the previews.
2. **Docs:**
   - docs/simple-ui.md, *Delivery*: add item 12, "Watch by name on every registry that is on, as docs/networks.md has it (owner's request, 2026-10-29)".
   - docs/networks.md, *Names*: one line that each answer names the registry that gave it, and that a testnet's registry is named as one.
3. **Merge `origin/feature/simple-ui`** (Part 11's merge, c54cb725) into this branch, and `main`'s new commits once they are in the feature branch.
4. **Full local build:** `./gradlew build`. On a Mac with a locked session, `MacBiometricKeyTest` fails with -25308, which is unrelated. The desktop session excluded `-x :ios-app:linkReleaseFrameworkIosArm64 -x :ios-app:linkReleaseFrameworkIosSimulatorArm64 -x :data:linkDebugTestIosSimulatorArm64`, because the Myotis prebuilt lacks SystemConfiguration symbols there.
5. **Independent review** of `git diff origin/feature/simple-ui...HEAD`: a fresh agent or `/code-review`. Fix what it finds.
6. **Screenshots:** `./gradlew :ui:desktopTest --tests '*WatchScreenPreviewsTest' --tests '*SimpleWatchScreenPreviewsTest' --rerun -Pscreenshots=build/screenshots`. Before and after for the changed Watch states, in one private artifact if the session has the Artifact tool; otherwise follow `.claude/skills/screenshots/SKILL.md`. Never commit images.
7. **The PR** into `feature/simple-ui`: "Simple mode, part 12: Watch by name on every registry". Follow the PR rules (the `pr` skill): the five steps, screenshots, the session link last, never the "Generated with Claude Code" footer. Turn on monitoring, answer every review comment on its thread with Accepted, Disputed or Deferred, and drive CI to green.
8. **Merging this part PR into `feature/simple-ui` is allowed** once CI is green and every comment is answered (the owner said yes for this plan's parts on 2026-10-26).

## Then: the final PR `feature/simple-ui` → `main`

- Merge `main` into the feature branch first (see above).
- An independent review of the whole diff, `git diff origin/main...origin/feature/simple-ui`, has to be redone, because Parts 11 and 12 came after the last one.
- A description draft exists from the desktop session but was not committed. Rewrite it with:
  - a table of every part PR with its screenshots link: #189 Q4Eh52g5eaYH8rVu7cySaV, #191 AfeWLpGHxZsr4ZHSj6CmTG, #192 ADUSuC48kXiEM6PxtPSU7q, #193 QzPAn1ZoBwBiczVx11KkHF, #194 P9XjaCmTZnL22rmbNBUHPz, #195 Hbun6xrYyegQ8r7KuHv8Qk, #196 UCuzCkLXSqNt4HtwJXThjt, #197 ABKZn8Ke1cGZEpu3QHSmSX, #201 XPiXuchRWTBtgt95e1qtf5, each as `https://claude.ai/artifact/<id>`; #204 has none (the session could not upload);
  - what changed, along the five steps;
  - the as-built decisions open for the owner (docs/simple-ui.md, every "As built … open for the owner to decide");
  - what was kept: the chrome variants (`SendChrome`, `SwapChrome`, `AccountChrome`) and Home re-dating every second;
  - the rule changes for the owner to confirm (CLAUDE.md, `.claude/rules/trust.md`, `ui.md`, `architecture.md`).
- **Never merge a PR into `main`.** Report "ready to merge" and keep watching.

## Rules to keep in mind

- Everything on GitHub is in English. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Never resolve review threads yourself; reply on each with its verdict. End automated replies with "_🤖 Addressed by [Claude Code](https://claude.com/claude-code)_".
- Never use a bare `git stash`. Never poll CI.
- The session link for PR bodies: `claude://claude.ai/epitaxy/local_8909f25d-e651-4410-a321-182850a707e9`. Use the cloud session's own link once it has one.
