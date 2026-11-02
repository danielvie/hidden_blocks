# Seven-step demonstration API

Branch-local implementation contract for the approved seven-step header design. Real model/change operations, no scripted screenshots.

- GET /api/demo/scenario -> ScenarioState.
- POST /api/demo/scenario/step {expectedGeneration:string,step:number,actor:"Alice"|"Bob"} -> ScenarioState. Steps 2 through 7 only. Existing POST /api/demo/reset is step 1.

ScenarioState = {generation:string,completedStep:number,actor:"Alice"|"Bob",message:string,conflicted:boolean,resetRequired:boolean}.

No changes to existing DemoState/WorkspaceState/ModelContracts fields are needed. The metadata is stored under scenario in existing demo_metadata. Reset clears it; GET after an explicit Reset returns completedStep=1. On a never-reset fresh generation it returns 0, so Reset is the first shortcut.

Progress is shared by all tabs. A completed-step retry is harmless and returns current progress; future/out-of-order steps fail409. Changing the model/history through manual controls after a scripted stage sets resetRequired so the next shortcut cannot silently overwrite that work. Actor switching/inspection does not invalidate progress. Preserve recorded work and ask for Reset to restart the fixed experiment rather than invent a general scenario planner.

2 Alice edits: select Alice, set pump.flowRate=12, autosave only.
3 Bob edits: select Bob, set pump.flowRate=15 from his independent base, autosave only.
4 Alice publishes: automatically Record+Deliver, accepted12.
5 Bob publishes: automatically Record+Deliver; needs-resolution is a successfully completed stage. Base10/Alice12/Bob15 conflict persists in Bob's pending change.
6 Bob adds alert: add Boolean alertEnabled=true to Bob's pump, Record as a distinct later change while earlier conflict remains.
7 Resolve: whichever actor is currently viewing may resolve Bob's shared pending origin to14, automatically replay descendants, Record/integrate as needed, Deliver, then refresh/update clean Alice as well. Assert alertEnabled=true survives. Result actor is the requested viewer; previous stages return the actor named by their button. Resolution provenance should identify the actual requested resolver, without transferring ownership of Bob's change.

Messages describe the actual effect with human names/values; no revision IDs. Stage7 message must say the alert was preserved. Expose failure or resetRequired rather than reporting a stage complete if a real underlying operation did not finish. This is a small disposable PoC; reuse existing service paths and publication idempotency instead of a new generic workflow framework.
