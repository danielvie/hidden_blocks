export interface SourceRange { start: number; end: number }
export interface PropertyValue { typeName: string | null; expression: string; scalar: { kind: 'REAL'|'INTEGER'|'BOOLEAN'|'STRING'; canonical: string } | null }
export interface Attribute { id: string; ownerId: string; name: string; value: PropertyValue | null; valueRange: SourceRange | null; editable: boolean; readOnlyReason: string | null; declaredBy?: string | null; definitionAttributeId?: string | null; inherited?: boolean }
export interface ModelElement { id: string; documentId: string; qualifiedName: string; name: string; kind: string; declarationRange: SourceRange | null; insertionOffset: number | null; attributes: Attribute[]; typeName?: string | null; definitionId?: string | null; superTypeIds?: string[]; ports?: Port[]; containerId?: string | null }
export interface Port { id:string; ownerId:string; name:string; typeName:string|null; conjugated:boolean; inherited:boolean; declaredBy:string|null }
export interface Connection { id:string; name:string|null; sourceElementId:string; sourcePortId:string; targetElementId:string; targetPortId:string }
export interface Diagnostic { severity: 'ERROR'|'WARNING'|'INFO'; category: string; message: string; documentId: string | null; range: SourceRange | null }
export interface Analysis { workspaceId: string; workspaceRevision: number; documentVersions: Record<string,number>; state: 'VALID'|'INVALID'|'PARTIAL'; elements: ModelElement[]; diagnostics: Diagnostic[]; connections?: Connection[] }
export interface Alternative { revisionId: string; actor: string; value: PropertyValue | null }
export interface Conflict { id: string; originChangeId: string; target: {elementId: string; attributeId: string}; kind: 'VALUE'|'ADDITION'|'SOURCE'; base: Alternative; incoming: Alternative; local: Alternative }
export interface HistoryEntry { changeId: string; revisionId: string; parentRevisionId: string; actor: string; description: string; text: string; conflicts: Conflict[]; inheritedConflictIds: string[]; accepted?: boolean }
export interface WorkspaceState { workspaceId: string; actor: string; revision: number; acceptedBaseRevision: string; acceptedHead: string; source: { documentId: string; version: number; text: string }; analysis: Analysis; lastValidAnalysis: Analysis | null; conflicts: Conflict[]; dirty: boolean; history: HistoryEntry[] }
export type Actor = 'Alice'|'Bob';
export interface DemoState { generation:string; alice:WorkspaceState; bob:WorkspaceState }
export interface Project { demo: null|{generation:string;aliceWorkspaceId:string;bobWorkspaceId:string}; acceptedHead: string; acceptedHistory?: {revisionId:string;parentRevisionId:string|null;actor:string;description:string}[]; workspaces: {workspaceId:string;actor:string}[]; drafts:{id:string;actor:string;description:string;conflictCount:number}[] }
export interface Mutation { status:'ok'|'needs-resolution'; message:string; state:WorkspaceState }
export interface Draft { id:string; actor:string; description:string; state:WorkspaceState }
export interface Card { element:ModelElement; position:{x:number;y:number}; properties:{attribute:Attribute;changed:boolean;conflictId:string|null}[]; conflicted:boolean }
export interface Projection { cards:Card[]; positions:Record<string,{x:number;y:number}> }
export interface Point { x:number; y:number }
export interface RoutingRequest {
 blocks:{id:string;x:number;y:number;width:number;height:number}[];
 wires:{id:string;source:Point&{owner:string};target:Point&{owner:string}}[];
}
export interface RoutingResult { routes:{id:string;points:Point[]}[] }
