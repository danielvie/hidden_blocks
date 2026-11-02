import './style.css';
import './scenario-header.css';
import {api,ApiError} from './api';
import {Session,currentAnalysis} from './session';
import {Projector} from './projection';
import {SourceEditor} from './source-editor';
import {Diagram} from './diagram';
import {Demo,recoveryPrefix} from './demo';
import {renderCollaboration,conflictView} from './collaboration';
import {button,el} from './dom';
import {diagramScope,analysisConnections,attributeSourceRange,shortName} from './model-view';
import {openPopup,closePopup} from './popup';
import {ScenarioHeader,scenarioApi} from './scenario-header';
import type {WorkspaceState,Project,Conflict,ModelElement,Attribute,HistoryEntry} from './types';
const $=<T extends HTMLElement=HTMLElement>(id:string)=>document.getElementById(id) as T;
let session:Session|null=null,project:Project|null=null,baseline:ModelElement[]=[],renderedState:WorkspaceState|null=null;
let queued=false,projectionGeneration=0,wasmReady=false,uiBusy=false,scenarioRunning=false;
let retryIntent:{operation:string;body:object}|null=null;
let projectionState:WorkspaceState|null=null;
const projector=new Projector();
const notice=(text:string)=>{$('notice').textContent=text;};
const fail=(error:unknown)=>{
 $('error').hidden=false;$('error').textContent=(error instanceof Error?error.message:String(error))+' Your current source has not been replaced.';
 if(session?.status==='failed')$('error').append(button('Review recovery',()=>{setTextOpen(true);$<HTMLDialogElement>('diagnostics-dialog').showModal();}));
 if(retryIntent){const intent=retryIntent;$('error').append(button('Retry '+intent.operation,()=>void run(async()=>{uiBusy=false;await mutate(intent.operation,intent.body);})));}
};
const clearError=()=>{$('error').hidden=true;};
async function run(action:()=>Promise<void>){const owner=session;try{clearError();await action();}catch(e){if(!owner||owner===session)fail(e);}}
const editor=new SourceEditor($('source-editor'),text=>session?.type(text),showProposal,offset=>{
 if(!session||!currentAnalysis(session))return;
 const a=attributes().find(a=>a.valueRange&&a.valueRange.start<=offset&&a.valueRange.end>=offset);
 if(a){const displayed=diagram.anchor(a.id)?a:attributes().find(row=>row.definitionAttributeId===a.id&&diagram.anchor(row.id));diagram.selectAt(displayed?.id??a.id);}
});
editor.setReadOnly(true);
const diagram=new Diagram($('canvas'),$('diagram'),$('properties'),{
 edit:(a,expression)=>void run(async()=>{if(!session||!graphEditable())throw new Error('Wait for current valid analysis before graphical editing.');await mutate('edit',{attributeId:a.id,expression});}),
 add:(element,name,typeName,expression)=>void run(async()=>{if(!session||!graphEditable())throw new Error('Wait for current valid analysis before adding an attribute.');await mutate('add',{elementId:element.id,name,typeName,expression});}),
 resolve:showProposal,
 select:a=>{if(session&&currentAnalysis(session))editor.highlight(attributeSourceRange(a,session.state.analysis.elements),false);},
 definition:showDefinition,
 source:range=>{setTextOpen(true);editor.highlight(range);},
},geometry=>projector.route(geometry));
const demo=new Demo(openWorkspace,()=>{persist();schedule();},fresh=>{project=fresh;renderPanel();});
const scenarioHeader=new ScenarioHeader($('demo-steps'),()=>demo.generation,()=>!session||session.locked||uiBusy,step=>void run(()=>runScenario(step)));
// Notifications only prompt a server check. The server generation remains authoritative.
// https://developer.mozilla.org/en-US/docs/Web/API/Broadcast_Channel_API
const resetChannel=typeof BroadcastChannel==='undefined'?null:new BroadcastChannel('sysml-demo-reset');
resetChannel?.addEventListener('message',()=>void refreshProject().catch(fail));
function attributes():Attribute[]{return session?.state.analysis?.elements.flatMap(e=>e.attributes)??[];}
function graphEditable(){return !!session&&currentAnalysis(session)&&!session.locked&&!uiBusy&&wasmReady&&projectionState===session.state;}
function schedule(){if(queued)return;queued=true;queueMicrotask(()=>{queued=false;render();});}
function recoveryKey(id:string){return recoveryPrefix+id;}
function persist(){if(!session)return;try{
 if(session.text===session.state.source.text)sessionStorage.removeItem(recoveryKey(session.state.workspaceId));
 else sessionStorage.setItem(recoveryKey(session.state.workspaceId),JSON.stringify({text:session.text,revision:session.recoveryRevision}));
}catch{fail(new Error('Browser recovery storage is unavailable. Keep this tab open until source is saved.'));}}
function openWorkspace(next:Session){
 const state=next.state;
 ++projectionGeneration;projectionState=null;diagram.setEditable(false);retryIntent=null;if(!scenarioRunning)uiBusy=false;
 $('scenario-feedback').hidden=true;
 session=next;clearError();closePopup(false);
 for(const dialog of document.querySelectorAll<HTMLDialogElement>('dialog[open]'))dialog.close();
 $<HTMLInputElement>('description').value='';$('notice').textContent='';
 try{const cached=JSON.parse(sessionStorage.getItem(recoveryKey(state.workspaceId))??'null');if(cached&&typeof cached.text==='string'&&cached.text!==state.source.text)session.recover(cached.text,Number.isSafeInteger(cached.revision)?cached.revision:-1);}catch{notice('Could not read the recovery buffer. The server source is open.');}
 baseline=state.lastValidAnalysis?.elements??(state.analysis?.state==='VALID'?state.analysis.elements:[]);
 renderedState=null;
 const url=new URL(location.href);url.search='';url.searchParams.set('workspace',state.workspaceId);history.replaceState(null,'',url);
 editor.setText(session.text);editor.annotate([],[],[]);$('proposal').hidden=true;
 void scenarioHeader.refresh().catch(fail);schedule();
}
function render(){
 const s=session;if(!s)return;
 editor.setText(s.text);editor.setReadOnly(s.locked||uiBusy);
 for(const actor of ['Alice','Bob']){
  const control=$<HTMLButtonElement>('actor-'+actor.toLowerCase());control.setAttribute('aria-pressed',String(s.state.actor===actor));control.disabled=s.locked||uiBusy;
 }
 $<HTMLButtonElement>('reset').disabled=s.locked||uiBusy;
 $('view-actor').textContent='Viewing '+s.state.actor+' ▾';
 $<HTMLButtonElement>('view-actor').disabled=s.locked||uiBusy||projectionState!==s.state;
 scenarioHeader.render();
 const displayAnalysis=s.state.analysis?.state==='VALID'?s.state.analysis:s.state.lastValidAnalysis;
 const scope=diagramScope(displayAnalysis?.elements??[]);
 $('model-title').textContent=scope.title;
 document.title=scope.title+' | Model workspace';
 $('toggle-panel').textContent='Changes'+(s.state.conflicts.length?' '+s.state.conflicts.length:'');
 $('save-status').textContent=({saved:'Saved',unsaved:'Unsaved',saving:'Saving…',failed:'Save failed'} as const)[s.status];
 $('save-status').dataset.state=s.status;$('retry').hidden=s.status!=='failed';
 $<HTMLButtonElement>('reload').disabled=s.locked||uiBusy;
 if(s.status==='failed')fail(new Error(s.error));
 $('source-version').textContent=s.state.source.documentId+' · v'+s.state.source.version;
 const valid=currentAnalysis(s);
 $('graph-state').textContent=valid?'Current analyzed model'+(s.state.conflicts.length?' · local values are provisional':''):'Outdated diagram · read-only until the source is valid and analysis is current';
 $('graph-state').classList.toggle('stale',!valid);$('graph-state').hidden=valid;
 if(!valid)$('graph-state').append(button('Review source',()=>setTextOpen(true)),button('Diagnostics',()=>$<HTMLDialogElement>('diagnostics-dialog').showModal()));
 $('analysis-status').textContent='Workspace r'+s.state.revision+' · analysis r'+(s.state.analysis?.workspaceRevision??'?')+' · '+(s.state.analysis?.state??'pending')+(s.state.conflicts.length?' candidate · '+s.state.conflicts.length+' unresolved conflict(s)':'');
 for(const id of ['record','share','deliver','update'])$<HTMLButtonElement>(id).disabled=s.locked||uiBusy||s.status==='failed';
 diagram.setEditable(graphEditable());
 const resolutionDisabled=s.locked||uiBusy||s.state.dirty||s.text!==s.state.source.text||s.status!=='saved';
 for(const control of $('proposal').querySelectorAll<HTMLInputElement|HTMLButtonElement>('[data-resolution]'))control.disabled=resolutionDisabled||control.dataset.absent==='true';
 if(renderedState!==s.state){
  renderedState=s.state;
  if(s.text===s.state.source.text)editor.annotate(s.state.analysis?.diagnostics??[],s.state.conflicts,attributes(),s.state.analysis?.state==='VALID');
  renderDiagnostics();renderTextConflicts();renderPanel();
  if(!$('proposal').hidden&&!s.state.conflicts.some(c=>c.id===$('proposal').dataset.conflictId))$('proposal').hidden=true;
  const analysis=s.state.analysis?.state==='VALID'?s.state.analysis:s.state.lastValidAnalysis;
  const generation=++projectionGeneration;$('wasm-status').textContent='Rust WASM projecting…';
  void projector.project(scope.elements,baseline,s.state.conflicts,false,scope.key).then(p=>{
   if(generation!==projectionGeneration)return;projectionState=s.state;wasmReady=true;$('wasm-status').textContent='Rust WASM worker ready';diagram.render(p,s.state.conflicts,graphEditable(),analysis?.elements??[],analysisConnections(analysis));schedule();
  }).catch(e=>{if(generation!==projectionGeneration)return;wasmReady=false;$('wasm-status').textContent='Rust WASM failed';diagram.setEditable(false);fail(e);});
 }
}
function renderDiagnostics(){
 $('diagnostics').replaceChildren();
 for(const d of session?.state.analysis?.diagnostics??[]){const b=button(d.severity+': '+d.message,()=>{setTextOpen(true);$<HTMLDialogElement>('diagnostics-dialog').close();if(session&&currentAnalysis(session))editor.highlight(d.range);else if(session?.text===session?.state.source.text)editor.highlight(d.range);},'diagnostic '+d.severity.toLowerCase());$('diagnostics').append(b);}
}
function renderTextConflicts(){
 $('text-conflicts').replaceChildren();for(const c of session?.state.conflicts??[]){const a=attributes().find(a=>a.id===c.target.attributeId);$('text-conflicts').append(button('Resolve '+(a?.name??'conflict')+' in text',()=>showProposal(c),'text-resolve'));}
}
function renderPanel(){if(!session)return;renderCollaboration($('collab-content'),session.state,project,{snapshot:showHistory,draft:id=>void run(async()=>{ensureRecorded();const owner=session;const draft=await api.draft(id);if(session!==owner)return;showSnapshot('Shared draft · '+draft.actor,draft.description+' · immutable snapshot '+draft.id,draft.state.source.text);}),resolve:showProposal});}
function ensureRecorded(){if(!session||session.text!==session.state.source.text||session.state.dirty||session.status!=='saved')throw new Error('Save and Record unrelated edits before inspecting history or resolving a conflict.');}
function showHistory(entry:HistoryEntry){void run(async()=>{ensureRecorded();const url=new URL(location.href);url.searchParams.set('revision',entry.revisionId);history.replaceState(null,'',url);showSnapshot(entry.changeId+' · read-only revision',entry.description+' · '+entry.revisionId+' · parent '+entry.parentRevisionId,entry.text);});}
function showSnapshot(title:string,meta:string,text:string){$('snapshot-title').textContent=title;$('snapshot-meta').textContent=meta;$('snapshot-source').textContent=text;$<HTMLDialogElement>('snapshot-dialog').showModal();}
function showProposal(conflict:Conflict,proposal?:string){
 if(!session)return;
 $<HTMLDialogElement>('collaboration').close();
 const node=$('proposal');node.dataset.conflictId=conflict.id;
 const a=attributes().find(a=>a.id===conflict.target.attributeId)||attributes().find(a=>a.definitionAttributeId===conflict.target.attributeId);
 const owner=session.state.analysis.elements.find(e=>e.id===conflict.target.elementId);
 node.replaceChildren(el('h3','','Resolve '+(owner?owner.name+'.':'')+(a?.name??'conflict')),el('p','meta','Choose a value, then Resolve to apply it.'));
 const disabled=session.state.dirty||session.text!==session.state.source.text||session.status!=='saved';
 const view=conflictView(conflict,expression=>void run(async()=>{ensureRecorded();await mutate('resolve',{originChangeId:conflict.originChangeId,conflictId:conflict.id,expression});node.hidden=true;}),disabled);
 if(proposal!==undefined){const custom=view.querySelector<HTMLInputElement>('input');if(custom)custom.value=proposal;}
 node.append(view,button('Show in source',()=>{closePopup(false);setTextOpen(true);if(a)editor.highlight(attributeSourceRange(a,session!.state.analysis.elements));}),button('Cancel proposal',()=>closePopup()));
 const displayed=attributes().find(row=>(row.id===conflict.target.attributeId||row.definitionAttributeId===conflict.target.attributeId)&&diagram.anchor(row.id));
 openPopup(node,displayed?diagram.anchor(displayed.id):null);node.querySelector<HTMLInputElement>('input')?.focus();
}
async function mutate(operation:string,body:object={}){
 if(!session)throw new Error('Open a workspace first.');
 const owner=session;
 await demo.check();
 if(session!==owner)throw new Error('The demo was reset. Review the fresh environment before editing.');
 const before=owner.state.history;
 let result;
 try {result=await owner.mutate(operation,body);if(session!==owner)return;retryIntent=null;uiBusy=false;}
 catch(error){
  if(session===owner&&['deliver','share'].includes(operation)&&owner.status!=='failed'&&!(error instanceof ApiError)){retryIntent={operation,body};uiBusy=true;schedule();}
  throw error;
 }
 notice(result.message);
 if(operation==='resolve'){
  const rewritten=result.state.history.filter(h=>before.some(b=>b.changeId===h.changeId&&b.revisionId!==h.revisionId));
  notice(result.message+' Rewritten/replayed: '+rewritten.length+' changes. Remaining conflicts: '+result.state.conflicts.length+'.');
 }
 await refreshProject();schedule();return result;
}
async function refreshProject(){await demo.check();await scenarioHeader.refresh();}
async function runScenario(step:number){
 if(!session||session.locked||uiBusy)return;
 const owner=session;scenarioRunning=true;uiBusy=true;schedule();
 try{
  await owner.flush();await demo.check();
  if(owner!==session)throw new Error('The demo was reset. Start from the current step.');
  const generation=demo.generation!;
  const result=await scenarioApi.step(generation,step,demo.actor);
  await demo.check();
  if(generation!==demo.generation)return;
  if(demo.actor===result.actor)await demo.reload();else await demo.switchActor(result.actor);
  await scenarioHeader.refresh();
  if(generation!==demo.generation)return;
  $('scenario-message').textContent=result.message;
  $('scenario-feedback').hidden=false;
 }finally{scenarioRunning=false;uiBusy=false;schedule();}
}
$('dismiss-scenario').onclick=()=>{$('scenario-feedback').hidden=true;};
for(const actor of ['Alice','Bob'] as const)$('actor-'+actor.toLowerCase()).onclick=()=>void run(async()=>{
 if(uiBusy)throw new Error('Wait for the current operation to finish.');
 uiBusy=true;schedule();
 try{closePopup(false);await demo.switchActor(actor);}finally{uiBusy=false;schedule();}
});
$('reset').onclick=()=>void run(async()=>{
 await demo.check();
 const generation=demo.generation;
 if(!generation||!confirm('Reset demo? Discard both Alice and Bob work, history, shared drafts/conflicts'))return;
 uiBusy=true;schedule();
 try{await demo.reset(generation,crypto.randomUUID());resetChannel?.postMessage('reset');await scenarioHeader.refresh();}finally{uiBusy=false;schedule();}
});
for(const operation of ['record','share','deliver','update'])$(operation).onclick=()=>void run(async()=>{
 const description=$<HTMLInputElement>('description').value.trim();
 const body=operation==='record'?{description:description||'Update model'}:operation==='share'?{description:description||'Shared draft',operationId:crypto.randomUUID()}:operation==='deliver'?{operationId:crypto.randomUUID()}:{};
 await mutate(operation,body);
});
$('retry').onclick=()=>void run(async()=>{if(session)await session.retry();});
$('reload').onclick=()=>void run(async()=>{await demo.check();if(session&&confirm('Reload server source? Copy any unsaved source first.')){sessionStorage.removeItem(recoveryKey(session.state.workspaceId));await demo.reload();}});
$('refresh-project').onclick=()=>void run(refreshProject);
$('close-snapshot').onclick=()=>$<HTMLDialogElement>('snapshot-dialog').close();
$('snapshot-dialog').addEventListener('close',()=>{const url=new URL(location.href);url.searchParams.delete('revision');history.replaceState(null,'',url);});
$('toggle-panel').onclick=()=>{closePopup(false);$<HTMLDialogElement>('collaboration').showModal();$('toggle-panel').setAttribute('aria-expanded','true');};
$('close-changes').onclick=()=>$<HTMLDialogElement>('collaboration').close();
$('collaboration').addEventListener('close',()=>$('toggle-panel').setAttribute('aria-expanded','false'));
function setTextOpen(open:boolean){
 closePopup(false);$('source-pane').hidden=!open;$('splitter').hidden=!open;$('workbench').classList.toggle('text-open',open);$('toggle-text').setAttribute('aria-expanded',String(open));
 // CodeMirror requests a fresh layout after its previously hidden host is revealed.
 // https://codemirror.net/docs/ref/#view.EditorView.requestMeasure
 if(open){editor.view.requestMeasure();requestAnimationFrame(()=>{editor.view.requestMeasure();editor.view.focus();});}else $('toggle-text').focus();
}
$('toggle-text').onclick=()=>setTextOpen($('source-pane').hidden!==false);
$('close-text').onclick=()=>setTextOpen(false);
$('overflow').onclick=()=>openPopup($('overflow-menu'),$('overflow'));
$('view-actor').onclick=()=>openPopup($('view-menu'),$('view-actor'));
$('view-menu').onkeydown=e=>{if(!['ArrowDown','ArrowUp','Home','End'].includes(e.key))return;e.preventDefault();const items=Array.from($('view-menu').querySelectorAll<HTMLButtonElement>('button'));const index=items.indexOf(document.activeElement as HTMLButtonElement);items[e.key==='Home'?0:e.key==='End'?items.length-1:(index+(e.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();};
for(const name of ['diagnostics','help']){
 $('open-'+name).onclick=()=>{closePopup(false);$<HTMLDialogElement>(name+'-dialog').showModal();};
 $('close-'+name).onclick=()=>$<HTMLDialogElement>(name+'-dialog').close();
}
$('overflow-menu').onkeydown=e=>{if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();const items=Array.from($('overflow-menu').querySelectorAll('button'));items[(items.indexOf(document.activeElement as HTMLButtonElement)+1)%items.length]?.focus();}};
$('close-definition').onclick=()=>$<HTMLDialogElement>('definition-dialog').close();
function showDefinition(element:ModelElement){
 closePopup(false);$('definition-title').textContent=element.name+' definition';
 const content=$('definition-content');content.replaceChildren();
 for(const id of element.superTypeIds??[]){const base=session?.state.analysis.elements.find(e=>e.id===id);if(base)content.append(button(':> '+base.name,()=>showDefinition(base),'specialization'));}
 for(const a of element.attributes){content.append(el('p','definition-feature',a.name+' : '+(a.value?.typeName?shortName(a.value.typeName):'unknown')+' = '+(a.value?.expression??'No value')+(a.inherited?' · inherited':'')));}
 for(const port of element.ports??[])content.append(el('p','definition-feature',port.name+' : '+(port.conjugated?'~':'')+(port.typeName?shortName(port.typeName):'Port')));
 const range=element.declarationRange,text=session?.state.source.text??'';
 if(range)content.append(el('pre','definition-source',text.slice(range.start,range.end)));
 content.append(button('Show definition in source',()=>{$<HTMLDialogElement>('definition-dialog').close();setTextOpen(true);editor.highlight(range);}));
 if(!$<HTMLDialogElement>('definition-dialog').open)$<HTMLDialogElement>('definition-dialog').showModal();
}
let split=46;
function resize(value:number){split=Math.max(25,Math.min(70,value));$('workbench').style.setProperty('--split-factor',String(split/100));$('splitter').setAttribute('aria-valuenow',String(Math.round(split)));}
$('splitter').onkeydown=e=>{if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();resize(split+(e.key==='ArrowRight'?2:-2));}};
$('splitter').onpointerdown=e=>{const splitter=$('splitter');splitter.setPointerCapture(e.pointerId);splitter.onpointermove=event=>{const rect=$('workbench').getBoundingClientRect();const usable=rect.width;resize((event.clientX-rect.left)/usable*100);};splitter.onpointerup=()=>{splitter.onpointermove=null;};};
window.addEventListener('beforeunload',e=>{if(session&&(session.text!==session.state.source.text||session.locked)){persist();e.preventDefault();}});
void run(async()=>{
 try{await projector.project([],[],[]);wasmReady=true;$('wasm-status').textContent='Rust WASM worker ready';}
 catch(error){wasmReady=false;$('wasm-status').textContent='Rust WASM failed';$('graph-state').hidden=false;$('graph-state').textContent='Diagram unavailable. Rust WASM initialization failed.';throw error;}
 const params=new URL(location.href).searchParams,revision=params.get('revision');
 await demo.start(params);
 if(revision){const entry=session?.state.history.find(h=>h.revisionId===revision);if(entry)showHistory(entry);}
});
// https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API
window.addEventListener('focus',()=>void refreshProject().catch(fail));
document.addEventListener('visibilitychange',()=>{if(!document.hidden)void refreshProject().catch(fail);});
setInterval(()=>{if(!document.hidden)void refreshProject().catch(fail);},2000);
