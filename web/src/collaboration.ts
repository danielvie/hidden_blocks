import {button,el,field,input} from './dom';
import type {Conflict,Project,WorkspaceState,HistoryEntry} from './types';
export function conflictView(c:Conflict,resolve:(expression:string)=>void,disabled:boolean):HTMLElement {
 const section=el('section','conflict-detail');section.append(el('h3','','Choose a value'));
 for(const [label,alternative] of [['Base value',c.base],['Incoming accepted value',c.incoming],['Local provisional value',c.local]] as const){
  const row=el('div','alternative');row.append(el('strong','',label),el('code','',alternative.value?.expression??'Absent'),el('small','',alternative.actor));section.append(row);
 }
 const actions=el('div','resolution-actions');
 for(const [label,alternative] of [['Use incoming',c.incoming],['Use local',c.local]] as const){const b=button(label,()=>{expression.value=alternative.value!.expression;expression.focus();});b.dataset.resolution='true';b.dataset.absent=String(!alternative.value);b.disabled=disabled||!alternative.value;actions.append(b);}
 section.append(actions);
 const form=el('form','custom-resolution');const expression=input(c.local.value?.expression??'');expression.required=true;expression.dataset.resolution='true';expression.disabled=disabled;
 const submit=el('button','primary','Resolve');submit.dataset.resolution='true';submit.disabled=disabled;
 form.append(field('Custom expression',expression),submit);form.onsubmit=e=>{e.preventDefault();resolve(expression.value);};section.append(form);
 if(disabled)section.append(el('p','readonly-reason','Record unrelated edits before resolving. Resolution rewrites the origin and replays its descendants.'));
 return section;
}
interface Actions {snapshot:(entry:HistoryEntry)=>void;draft:(id:string)=>void;resolve:(conflict:Conflict)=>void}
export function renderCollaboration(root:HTMLElement,state:WorkspaceState,project:Project|null,actions:Actions) {
 root.replaceChildren();
 const accepted=el('section','collab-section');accepted.append(el('h3','','Accepted history'));
 if((project?.acceptedHead??state.acceptedHead)!==state.acceptedBaseRevision)accepted.append(el('p','update-notice','Accepted history advanced. Update explicitly to bring it into this workspace.'));

 const acceptedList=el('ol','accepted-history');
 for(const revision of project?.acceptedHistory??[]){const item=el('li','');item.append(el('strong','',revision.description),el('small','meta',revision.actor));acceptedList.append(item);}
 accepted.append(acceptedList);root.append(accepted);
 const conflicts=el('section','collab-section');conflicts.append(el('h3','','Conflicts · '+state.conflicts.length));
 if(!state.conflicts.length)conflicts.append(el('p','empty','No unresolved conflicts.'));
 for(const conflict of state.conflicts){
  const attribute=state.analysis?.elements.flatMap(e=>e.attributes).find(a=>a.id===conflict.target.attributeId);
  const b=button((attribute?.name??'Source conflict')+' · resolve',()=>actions.resolve(conflict),'conflict-item');
  b.append(el('small','','Base '+(conflict.base.value?.expression??'absent')+' / incoming '+(conflict.incoming.value?.expression??'absent')+' / local '+(conflict.local.value?.expression??'absent')));conflicts.append(b);
 }
 root.append(conflicts);
 const history=el('section','collab-section');history.append(el('h3','','Changes and revisions'));
 for(const entry of [...state.history].reverse()){
  const row=button('',()=>actions.snapshot(entry),'history-item');
  row.append(el('strong','',entry.description||'Recorded change'),el('span','meta',entry.actor+' · '+(entry.accepted?'Accepted':'Local change')));
  if(entry.conflicts.length)row.append(el('span','conflict-label','Origin of '+entry.conflicts.length+' conflict(s)'));
  if(entry.inheritedConflictIds.length)row.append(el('span','conflict-label','Inherited conflicts · '+entry.inheritedConflictIds.length));
  history.append(row);
  for(const conflict of entry.conflicts.filter(c=>c.originChangeId===entry.changeId))history.append(button('Resolve this change’s conflict',()=>actions.resolve(conflict),'inherited-origin'));
  for(const conflict of state.conflicts.filter(c=>entry.inheritedConflictIds.includes(c.id)))history.append(button('Resolve earlier feature conflict',()=>actions.resolve(conflict),'inherited-origin'));
 }
 if(!state.history.length)history.append(el('p','empty','No recorded changes. Edit a value, then Record change.'));
 root.append(history);
 const drafts=el('section','collab-section');drafts.append(el('h3','','Shared drafts'));
 for(const draft of project?.drafts??[])drafts.append(button(draft.actor+' · '+(draft.description||draft.id)+' · '+draft.conflictCount+' conflict(s)',()=>actions.draft(draft.id),'draft-item'));
 if(!project?.drafts.length)drafts.append(el('p','empty','Share a recorded draft for read-only inspection.'));
 root.append(drafts);
}
