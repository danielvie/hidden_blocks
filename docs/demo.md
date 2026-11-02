# Repeatable PoC walkthrough

Run `task run` and open http://127.0.0.1:4317. Alice is ready immediately, and Bob already exists from the same reference revision. No workspace creation or example selection is required.

## Header

- Reset starts the entire demonstration over after confirmation.
- Bob opens Bob's independent environment in this tab.
- Alice opens Alice's independent environment in this tab.
- Text reveals the source beside the diagram.
- Changes reveals commit-like recording, delivery, update, sharing and history.

The selected actor button is pressed/highlighted. Switching saves pending text but does not Record it. Both valid and invalid drafts remain in their own environments. If a save fails or is stale, switching stops instead of dropping the text.

## Conflict sequence

1. In Alice, double-click pump.flowRate and set it to 12.
2. Open Changes, describe the work, Record change, then Deliver.
3. Select Bob in the header. His flowRate is still 10 because both people started independently.
4. Set Bob's flowRate to 15, Record change, and Deliver.
5. Only the flowRate row is marked conflicted. Its alternatives are base 10, incoming Alice 12, and local Bob 15.
6. Right-click pump, add Boolean alertEnabled = true, then Record that later work.
7. Click the conflicted row. Choose an alternative or enter custom 14, then explicitly Resolve.
8. The alert survives as the later change is reapplied. All six ports and three connections remain.
9. Bob Delivers the resolved result.
10. Select Alice, open Changes, and Update. Alice receives 14 plus the alert.
11. Press Reset, confirm, and repeat.

The incoming/local buttons stage a proposal; they do not resolve silently. Text-side editing of a conflicted range also stages an explicit proposal.

## Two tabs

Open the same app in two tabs. Select Alice in one and Bob in the other. They share the accepted Git repository but have separate buffers and recorded histories.

The same actor in multiple tabs is the same environment. Competing writes to one environment still require revision checks; a stale writer retains its buffer rather than overwriting another tab.

Reset affects the whole PoC, not just one tab. Both tabs detect the new generation and move to fresh actor environments. Old URLs, autosaves and delayed responses cannot restore the retired state.

## Reset meaning

Confirmation is destructive: both actors' edits, invalid drafts, histories, conflicts, shared drafts and active accepted Git history are discarded. Both are recreated from the fixed connected reference, with new environment IDs.

A repeated request for the same reset operation is idempotent. A stale reset from an earlier generation is rejected instead of clearing newer work. Interrupted resets finish on restart before the demo serves mutations.

Only the selected PoC data is affected. The source repository, reference projects, other data directories and downloaded dependencies are not deleted. Git may retain unreachable objects until garbage collection; active history starts at a fresh parentless reference commit.

## Connected reference

The canvas shows controller : Controller, pump : Pump, and tank : Tank from [the water-tank fixture](../fixtures/water-tank.sysml). Each part has one input port and one output port. Three connections form the loop:

```text
controller.pumpCommandOut -> pump.commandIn
pump.waterOut -> tank.waterIn
tank.levelOut -> controller.levelIn
```

The input ports use conjugated port types. flowRate is authored directly on the pump instance, with an initial value of 10. The model has no specialization or executable control behavior. It keeps the routing example small while retaining the editable value needed for collaboration.

Existing saved environments are not overwritten on startup. Restart the server and explicitly Reset to use this reference, discarding the old demo work and history.

Double-click edits a feature, single-click selects, and right-click or Shift+F10 opens secondary actions. Definitions and inherited origins are inspectable on demand; topology editing is not supported.

## Automated verification

`task test` runs the parser, engine, database/Git, process-crash and actual browser suites using test-owned data and ports. `task test:e2e` creates an isolated backend; it never resets the normal running PoC.

The browser suite resets its isolated demo before each scenario. Direct Playwright runs require an explicitly configured isolated E2E_BASE_URL and refuse port 4317.
