# Browser/backend implementation contract

Version 1. Base URL /api. Local server defaults to http://127.0.0.1:4317.

All JSON uses the existing ModelContracts.java record field names and explicit nulls. Scalar canonical values are strings. Every mutation except workspace creation requires expectedRevision. Source mutations also require documentVersion. Errors return HTTP 400/409/422 with {status,message,state?}; clients must not replace unsaved text on error. Successful mutations return {status:"ok"|"needs-resolution",message,state}.

## Workspace state

```
{
 workspaceId, actor, revision, acceptedBaseRevision, acceptedHead,
 source: {documentId:"model.sysml",version,text},
 analysis: Analysis,
 lastValidAnalysis: Analysis|null,
 conflicts: Conflict[],
 dirty: boolean,
 history: [{changeId,revisionId,parentRevisionId,actor,description,text,
            conflicts:Conflict[],inheritedConflictIds:string[],accepted:boolean}]
}
```

Analysis, Conflict, Attribute, PropertyValue and ranges follow contracts/model.schema.json. An unresolved property uses local provisional source; conflicts remain authoritative. Pending/invalid analysis uses lastValidAnalysis for a visibly stale read-only diagram.

## Disposable demo entry point

Normal UI uses [poc-demo.md](poc-demo.md): GET /api/demo creates/opens the fixed Alice/Bob pair, POST /api/demo/reset explicitly starts a fresh generation. The generic workspace routes below remain for internal compatibility tests; they are not an extra user workflow. Project.demo exposes the active generation and actor workspace IDs.

## Routes

- GET /api/health -> {status:"ok"}
- GET /api/project -> {acceptedHead,acceptedHistory:[{revisionId,parentRevisionId,actor,description}],workspaces:[{workspaceId,actor}],drafts:[{id,actor,description,conflictCount}]}. acceptedHistory is newest-first real Git history; the root parentRevisionId is null.
- POST /api/workspaces {actor} -> mutation response containing a new independent workspace from the current accepted head.
- GET /api/workspaces/{id} -> WorkspaceState directly.
- POST /api/workspaces/{id}/save {expectedRevision,documentVersion,text}
- POST /api/workspaces/{id}/edit {expectedRevision,documentVersion,attributeId,expression}
- POST /api/workspaces/{id}/add {expectedRevision,documentVersion,elementId,name,typeName,expression}. typeName is Real, Integer, Boolean, or String.
- POST /api/workspaces/{id}/record {expectedRevision,description}
- POST /api/workspaces/{id}/deliver {expectedRevision,operationId}
- POST /api/workspaces/{id}/update {expectedRevision}
- POST /api/workspaces/{id}/resolve {expectedRevision,originChangeId,conflictId,expression}
- POST /api/workspaces/{id}/share {expectedRevision,operationId,description}
- GET /api/drafts/{id} -> {id,actor,description,state:WorkspaceState}, immutable read-only shared snapshot.

operationId is a fresh UUID per user intent, reused for retrying that same intent. Resolve receives expression text, never a browser-authoritative scalar. Incoming/local buttons submit the corresponding stored expression. Source edits are reanalyzed by the official parser.

History selection is read-only and does not replace the live buffer. Explicit resolution always targets the origin change in the current workspace chain. Unrelated unsaved/unrecorded text blocks navigation/resolution; a conflict proposal belongs in the resolution controls and needs explicit Resolve. A newer acceptedHead notification never overwrites local text.
