# Seven-step header shortcut proposal

Status: Implemented and verified on isolated branch poc/seven-step-header. The parallel water-model/renderer/routing work was merged into this branch and tested together.

The header contains a narrow model/view strip and one row of seven numbered buttons. Everything below stays a diagram. The Text toggle reveals the existing source editor; history and optional manual controls stay behind the overflow menu. This replaces the separate actor-switch/Record/Deliver workflow for the scripted demonstration.

## Shortcut effects

| Header button | One-click effect |
|---|---|
| 1 Reset | Restore the reference and fresh Alice/Bob environments from the same base. Explicit destructive confirmation still applies. |
| 2 Alice edits | Select Alice, set pump.flowRate to 12, and keep the edit as Alice's local change. |
| 3 Bob edits | Select Bob, set pump.flowRate to 15 from his independent base, and keep his local change. |
| 4 Alice publishes | Record/integrate Alice's work automatically; shared flowRate becomes 12. |
| 5 Bob publishes | Record/rebase Bob's work; expose base 10/Alice 12/Bob 15 as a shared unresolved conflict. Continue the experiment rather than stopping it. |
| 6 Bob adds alert | Select Bob, add alertEnabled = true as a later independent change while flowRate remains conflicted. No separate Record action. |
| 7 Resolve | Apply the scripted resolution flowRate = 14, replay/preserve the alert change, and complete integration. No extra Publish or Update action. |

Resolve is named without an actor because either person may resolve the shared pending change. The optional Viewing dropdown chooses whose perspective is visible; normal shortcut clicks select the needed actor automatically. Resolution changes the owning pending change, not a copy in an unrelated environment.

The deterministic Resolve shortcut uses 14 to keep this demonstration seven actions. Manual alternative/custom selection remains available by opening the affected feature, but is not another required step in the scripted run.

## States

The large sketch shows step 5 completed with a conflict. Step 6 is the next action. Completed steps are subdued, future steps disabled, and Reset remains available. Step 5 is amber because publishing completed with an unresolved disagreement, not because the entire operation failed.

The lower two sketches show the experiment's key observation: adding another feature does not clear the earlier conflict, and resolving that earlier conflict does not discard the later feature. A short result beside the pump says the alert was preserved. This feedback must be visible, not hidden in a closed history dialog.

Hover/focus descriptions explain the fixed values used by the shortcuts. On smaller windows, the step strip can scroll horizontally rather than adding permanent panels. Free editing remains optional; Reset restores the known starting conditions before replaying the scripted sequence.

## Experiment boundary

Every shortcut must execute actual model/change operations, not animate fabricated states. At the same time, a guided seven-button run demonstrates behavior, not proof that an unguided collaboration UX is intuitive. The existing real source/graph editing can be used separately to test that question.

The manual controls retain explicit Record change/Deliver/Update semantics. The seven shortcuts now orchestrate those real operations automatically through /api/demo/scenario. Either viewer can resolve the pending Bob-owned origin without transferring ownership, and the resulting later alert is preserved and integrated. Visible messages report actual outcomes; a failed or manually diverged scenario requires Reset rather than reporting fake completion.

## Artifacts

- [Editable Excalidraw](seven-step-header.excalidraw)
- [Preview](seven-step-header.png)
