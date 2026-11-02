import {basicSetup} from 'codemirror';
import {Compartment,EditorState,Transaction,StateEffect,StateField, type Range} from '@codemirror/state';
import {Decoration,EditorView,type DecorationSet} from '@codemirror/view';
import {setDiagnostics} from '@codemirror/lint';
import {sysmlHighlighting} from './sysml-highlighting';
import type {Attribute,Conflict,Diagnostic,SourceRange} from './types';
interface Guard { conflict:Conflict; range:SourceRange; name:string }
export class SourceEditor {
 readonly view:EditorView;
 private readonly mode=new Compartment();
 private readonly setMarks=StateEffect.define<DecorationSet>();
 // Map decorations through typing, as in https://codemirror.net/examples/decoration/.
 private readonly marks=StateField.define<DecorationSet>({
  create:()=>Decoration.none,
  update:(marks,tr)=>{marks=marks.map(tr.changes);for(const effect of tr.effects)if(effect.is(this.setMarks))marks=effect.value;return marks;},
  provide:field=>EditorView.decorations.from(field),
 });
 private guards:Guard[]=[];
 private replacing=false;
 constructor(parent:HTMLElement,onChange:(text:string)=>void,onConflict:(conflict:Conflict,proposal?:string)=>void,onSelect:(offset:number)=>void) {
  this.view=new EditorView({parent,state:EditorState.create({extensions:[
   basicSetup,sysmlHighlighting,this.mode.of([]),this.marks,
   EditorView.contentAttributes.of({'aria-label':'SysML source editor','spellcheck':'false','tabindex':'0'}),
   EditorState.transactionFilter.of(tr=>{
    if(!tr.docChanged || this.replacing) return tr;
    for(const guard of this.guards) if(tr.changes.touchesRange(guard.range.start,guard.range.end)){
     const start=tr.changes.mapPos(guard.range.start,-1),end=tr.changes.mapPos(guard.range.end,1);
     const proposal=tr.newDoc.sliceString(start,end);
     queueMicrotask(()=>onConflict(guard.conflict,proposal));return [];
    }
    return tr;
   }),
   EditorView.updateListener.of(update=>{
    if(update.docChanged&&!this.replacing){
     this.guards=this.guards.map(g=>({...g,range:{start:update.changes.mapPos(g.range.start,1),end:update.changes.mapPos(g.range.end,-1)}}));
     onChange(update.state.doc.toString());
    }
    if(update.selectionSet)onSelect(update.state.selection.main.head);
   }),
   EditorView.theme({'&':{height:'100%'},'.cm-scroller':{overflow:'auto',fontFamily:'Consolas, "Cascadia Code", monospace',fontSize:'13px'},'.cm-content':{padding:'16px 0'},'.cm-gutters':{background:'#f6f8fb',color:'#56677c',borderRight:'1px solid #e0e6ee'},'&.cm-focused':{outline:'none'}})
  ]})});
 }
 setText(text:string) {
  if(this.view.state.doc.toString()===text)return;
  this.replacing=true;
  this.view.dispatch({changes:{from:0,to:this.view.state.doc.length,insert:text},annotations:Transaction.addToHistory.of(false)});
  this.replacing=false;
 }
 setReadOnly(value:boolean){this.view.dispatch({effects:this.mode.reconfigure([EditorState.readOnly.of(value),EditorView.editable.of(!value),EditorView.contentAttributes.of({'aria-readonly':String(value)})])});}
 annotate(diagnostics:Diagnostic[],conflicts:Conflict[],attributes:Attribute[],refreshGuards=true) {
  if(refreshGuards)this.guards=conflicts.flatMap(conflict=>{const a=attributes.find(a=>a.id===conflict.target.attributeId);return a?.valueRange?[{conflict,range:a.valueRange,name:a.name}]:[];});
  const ranges:Range<Decoration>[]=[];
  for(const g of this.guards)if(g.range.end<=this.view.state.doc.length && g.range.start<g.range.end) ranges.push(Decoration.mark({class:'source-conflict',attributes:{title:'Unresolved '+g.name+'. Use Resolve in text below the editor.'}}).range(g.range.start,g.range.end));
  ranges.sort((a,b)=>a.from-b.from);
  const decorations:DecorationSet=Decoration.set(ranges,true);
  this.view.dispatch({effects:this.setMarks.of(decorations)});
  this.view.dispatch(setDiagnostics(this.view.state,diagnostics.filter(d=>d.range && d.range.end<=this.view.state.doc.length).map(d=>({from:d.range!.start,to:d.range!.end,severity:d.severity==='ERROR'?'error':d.severity==='WARNING'?'warning':'info',message:d.message}))));
 }
 highlight(range:SourceRange|null,focus=true) {
  if(!range||range.end>this.view.state.doc.length)return;
  this.view.dispatch({selection:{anchor:range.start,head:range.end},scrollIntoView:true});if(focus)this.view.focus();
 }
}
