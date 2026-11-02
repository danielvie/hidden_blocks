import {el,button,field,input,select} from './dom';
import {conflictFor,definitionFor,attributeSourceRange,polylinePath,polylineMidpoint,blockColors,visibleProperties,shortName} from './model-view';
import {openPopup,closePopup} from './popup';
import type {Card,Projection,Attribute,Conflict,ModelElement,Connection,Point,RoutingRequest,RoutingResult} from './types';
interface Actions { edit:(a:Attribute,expression:string)=>void; add:(e:ModelElement,name:string,typeName:string,expression:string)=>void; resolve:(c:Conflict,proposal?:string)=>void; select:(a:Attribute)=>void; definition:(e:ModelElement)=>void; source:(range:ModelElement['declarationRange'])=>void }
export class Diagram {
 private projection:Projection={cards:[],positions:{}};
 private conflicts:Conflict[]=[];
 private elements:ModelElement[]=[];
 private connections:Connection[]=[];
 private editable=false;
 private zoom=1;
 private panX=0;
 private panY=0;
 private automaticViewport=true;
 private selected:string|null=null;
 private expanded=new Set<string>();
 private routingGeneration=0;
 private bounds={left:0,top:0,right:0,bottom:0};
 private routingStatus=el('p','routing-status');
 constructor(private canvas:HTMLElement,private root:HTMLElement,private inspector:HTMLElement,private actions:Actions,private route:(geometry:RoutingRequest)=>Promise<RoutingResult>) {
  this.routingStatus.setAttribute('role','status');this.routingStatus.hidden=true;canvas.parentElement!.append(this.routingStatus);
  document.getElementById('zoom-in')!.onclick=()=>{this.automaticViewport=false;this.scale(this.zoom+.1);};
  document.getElementById('zoom-out')!.onclick=()=>{this.automaticViewport=false;this.scale(this.zoom-.1);};
  document.getElementById('fit')!.onclick=()=>{this.automaticViewport=false;this.fit();};
  new ResizeObserver(()=>this.responsiveViewport()).observe(canvas);
 }
 private scale(value:number){this.zoom=Math.max(.25,Math.min(1.8,value));this.root.style.transform=`translate(${this.panX}px,${this.panY}px) scale(${this.zoom})`;document.getElementById('zoom')!.textContent=Math.round(this.zoom*100)+'%';}
 private fit(){
  if(!this.projection.cards.length)return;
  const {left,top,right,bottom}=this.bounds;
  const zoom=Math.max(.25,Math.min(1,(this.canvas.clientWidth-48)/(right-left),(this.canvas.clientHeight-64)/(bottom-top)));
  this.panX=24-left*zoom;this.panY=32-top*zoom;this.scale(zoom);
 }
 private responsiveViewport(){if(this.automaticViewport)this.fit();}
 setEditable(editable:boolean){this.editable=editable;for(const control of this.inspector.querySelectorAll<HTMLButtonElement|HTMLInputElement|HTMLSelectElement>('[data-mutation]'))control.disabled=!editable;}
 render(projection:Projection,conflicts:Conflict[],editable:boolean,elements:ModelElement[]=[],connections:Connection[]=[]) {
  closePopup(false);this.projection=projection;this.conflicts=conflicts;this.editable=editable;this.elements=elements;this.connections=connections;this.root.replaceChildren();
  for(const card of projection.cards)this.root.append(this.card(card));
  this.extent();
  if(!projection.cards.length)this.root.append(el('p','canvas-empty','No blocks in the analyzed model. Open Text to add a part definition.'));
  void this.wires();if(this.selected)this.selectAt(this.selected);this.responsiveViewport();
 }
 private choose(card:Card,a?:Attribute){this.selected=a?.id??card.element.id;this.selectAt(this.selected);if(a)this.actions.select(a);}
 private card(card:Card):HTMLElement {
  const article=el('article','block');article.dataset.elementId=card.element.id;article.setAttribute('aria-label',card.element.name+' block');article.tabIndex=0;
  article.style.left=card.position.x+'px';article.style.top=card.position.y+'px';
  const header=el('div','block-header');header.append(el('small','block-kind',card.element.kind==='PartUsage'?'«part»':'«part def»'),el('h3','',card.element.name+(card.element.typeName?' : '+shortName(card.element.typeName):'')));
  const definition=this.elements.find(e=>e.id===card.element.definitionId)??card.element;
  for(const id of definition.superTypeIds??[]){const base=this.elements.find(e=>e.id===id);if(base)header.append(button(':> '+base.name,()=>{closePopup(false);this.actions.definition(base);},'specialization'));}
  if(card.conflicted){const count=card.properties.filter(p=>p.conflictId).length;const badge=el('span','conflict-badge',String(count));badge.setAttribute('aria-label',count+' unresolved feature conflicts');header.append(badge);}
  article.append(header);
  const list=el('div','block-properties');const collapsed=card.properties.length>6&&!this.expanded.has(card.element.id);
  for(const property of visibleProperties(card.properties,!collapsed)){
   const a=property.attribute;
   const row=button('',()=>{this.choose(card,a);const conflict=conflictFor(a,this.conflicts);if(conflict)this.actions.resolve(conflict);},'property-row'+(property.conflictId?' has-conflict':''));row.dataset.attributeId=a.id;
   row.append(el('span','property-name',a.name+(a.value?.typeName?' : '+shortName(a.value.typeName):'')),el('code','',a.value?.expression??'No value'));
   if(a.inherited)row.append(el('small','inherited-origin','from '+this.origin(a.declaredBy)));
   if(property.conflictId)row.append(el('small','conflict-label','Conflict'));
   row.setAttribute('aria-label','Inspect '+a.name+(property.conflictId?', unresolved':''));
   row.ondblclick=e=>{e.stopPropagation();this.inspect(card,a,row);};
   row.onkeydown=e=>{if(e.key==='Enter'||e.key==='F2'){e.preventDefault();e.stopPropagation();this.inspect(card,a,row);}else this.contextKey(e,card,a,row);};
   row.oncontextmenu=e=>{e.preventDefault();e.stopPropagation();this.menu(card,row,a);};list.append(row);
  }
  if(card.properties.length>6)list.append(button(collapsed?'Show all features':'Show fewer features',()=>{this.expanded.has(card.element.id)?this.expanded.delete(card.element.id):this.expanded.add(card.element.id);this.render(this.projection,this.conflicts,this.editable,this.elements,this.connections);},'show-features'));
  article.append(list);
  const ports=el('div','block-ports');let inputs=0,outputs=0;
  for(const port of card.element.ports??[]){
   const incoming=this.connections.find(c=>c.targetElementId===card.element.id&&c.targetPortId===port.id);
   const outgoing=this.connections.some(c=>c.sourceElementId===card.element.id&&c.sourcePortId===port.id);
   const input=!!incoming||!outgoing&&port.conjugated;
   const inspect=()=>{closePopup(false);this.actions.definition(definitionFor(card.element,{declaredBy:port.declaredBy},this.elements)??definition);};
   const row=button('',()=>this.selectAt(port.id),'port-row '+(input?'port-input':'port-output'));row.dataset.featureId=port.id;row.setAttribute('aria-label','Inspect port '+port.name);
   row.style.gridRow=String(input?++inputs:++outputs);row.style.gridColumn=input?'1':'2';
   row.title=port.name+' : '+(port.conjugated?'~':'')+(port.typeName?shortName(port.typeName):'Port')+(port.inherited?' · from '+this.origin(port.declaredBy):'');
   row.ondblclick=e=>{e.stopPropagation();inspect();};row.onkeydown=e=>{if(e.key==='Enter'||e.key==='F2'){e.preventDefault();e.stopPropagation();inspect();}else this.contextKey(e,card,undefined,row);};
   row.append(el('span','port-name',port.name));
   const terminal=el('span','port-terminal '+(input?'terminal-left':'terminal-right')+(!input||incoming?' terminal-filled':''));terminal.dataset.portId=port.id;terminal.setAttribute('aria-hidden','true');
   terminal.style.setProperty('--port-color',this.color(incoming?.sourceElementId??card.element.id));row.append(terminal);ports.append(row);
  }
  if(ports.childElementCount)article.append(ports);
  article.onclick=e=>{if((e.target as HTMLElement).closest('button'))return;this.choose(card);};
  article.ondblclick=e=>{if((e.target as HTMLElement).closest('button'))return;this.inspect(card,undefined,article);};
  article.oncontextmenu=e=>{if(e.defaultPrevented)return;e.preventDefault();this.menu(card,article);};
  article.onkeydown=e=>{if(e.target!==article)return;if(e.key==='Enter'||e.key==='F2'){e.preventDefault();this.inspect(card,undefined,article);}else this.contextKey(e,card,undefined,article);};
  return article;
 }
 private origin(name:string|null|undefined){const definition=this.elements.find(e=>e.id===name);return definition?.name??(name?shortName(name):'definition');}
 private contextKey(e:KeyboardEvent,card:Card,a:Attribute|undefined,anchor:HTMLElement){if(e.key==='ContextMenu'||e.shiftKey&&e.key==='F10'){e.preventDefault();e.stopPropagation();this.menu(card,anchor,a);}}
 private heading(title:string){this.inspector.onkeydown=null;const heading=el('div','dialog-heading');heading.append(el('h2','',title),button('Close',()=>closePopup()));this.inspector.replaceChildren(heading);this.inspector.setAttribute('role','dialog');this.inspector.setAttribute('aria-label',title);}
 private inspect(card:Card,a:Attribute|undefined,anchor:HTMLElement){
  this.choose(card,a);const conflict=a&&conflictFor(a,this.conflicts);if(conflict){this.actions.resolve(conflict);return;}
  this.heading(a?card.element.name+'.'+a.name:card.element.name);
  if(a){
   if(a.inherited){this.inspector.append(el('p','readonly-reason','Inherited from '+this.origin(a.declaredBy)+'. Inspect its definition before editing.'),button('Inspect definition',()=>{closePopup(false);this.actions.definition(definitionFor(card.element,a,this.elements)??card.element);}));}
   else if(a.editable){
    const form=el('form','inline-editor');const expression=input(a.value?.expression??'');expression.dataset.mutation='true';expression.required=true;
    const save=el('button','primary','Apply literal');save.dataset.mutation='true';form.append(field('Literal expression',expression),save);
    form.onsubmit=e=>{e.preventDefault();this.actions.edit(a,expression.value);};this.inspector.append(form);
   }else this.inspector.append(el('p','readonly-reason',a.readOnlyReason??'This expression is read-only. Edit the source instead.'));
   this.inspector.append(button('Show in source',()=>{closePopup(false);this.actions.source(attributeSourceRange(a,this.elements));}));
  }else{
   this.inspector.append(button('Add attribute',()=>this.addForm(card,anchor)),button('Inspect definition',()=>{closePopup(false);this.actions.definition(definitionFor(card.element,undefined,this.elements)??card.element);}),button('Show in source',()=>{closePopup(false);this.actions.source(card.element.declarationRange);}));
  }
  this.setEditable(this.editable);openPopup(this.inspector,anchor);if(a?.editable&&!a.inherited){const input=this.inspector.querySelector('input');input?.focus();input?.select();}
 }
 private addForm(card:Card,anchor:HTMLElement){
  this.heading('Add attribute to '+card.element.name);
  if(card.element.insertionOffset===null){this.inspector.append(el('p','readonly-reason','No safe attribute insertion point. Add it in Text.'));openPopup(this.inspector,anchor);return;}
  const form=el('form','add-attribute');const name=input(''),type=select(['Boolean','Real','Integer','String']),expression=input('true');
  for(const control of [name,type,expression]){control.required=true;control.dataset.mutation='true';}name.pattern='[A-Za-z_][A-Za-z0-9_]*';
  const submit=el('button','primary','Add attribute');submit.dataset.mutation='true';
  form.append(field('Attribute name',name),field('Scalar type',type),field('Initial expression',expression),submit);
  form.onsubmit=e=>{e.preventDefault();this.actions.add(card.element,name.value,type.value,expression.value);};this.inspector.append(form);this.setEditable(this.editable);openPopup(this.inspector,anchor);name.focus();
 }
 private menu(card:Card,anchor:HTMLElement,a?:Attribute){
  this.inspector.replaceChildren();this.inspector.setAttribute('role','menu');this.inspector.setAttribute('aria-label',card.element.name+' actions');
  const entries:[string,()=>void][]=[['Add attribute',()=>this.addForm(card,anchor)],['Inspect definition',()=>{closePopup(false);this.actions.definition(definitionFor(card.element,a,this.elements)??card.element);}],['Show in source',()=>{closePopup(false);this.actions.source(a?attributeSourceRange(a,this.elements):card.element.declarationRange);}]];
  if(a)entries.unshift([a.inherited?'Inspect feature':'Edit feature',()=>this.inspect(card,a,anchor)]);
  for(const [label,action] of entries){const item=button(label,action);item.setAttribute('role','menuitem');this.inspector.append(item);}
  // Menu keyboard behavior follows https://www.w3.org/WAI/ARIA/apg/patterns/menu/.
  this.inspector.onkeydown=e=>{const items=Array.from(this.inspector.querySelectorAll<HTMLButtonElement>('[role=menuitem]'));const index=items.indexOf(document.activeElement as HTMLButtonElement);if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();items[e.key==='Home'?0:e.key==='End'?items.length-1:(index+(e.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();}};
  openPopup(this.inspector,anchor);
 }
 private color(id:string){return blockColors[Math.max(0,this.projection.cards.findIndex(c=>c.element.id===id))%blockColors.length];}
 private extent(points:Point[]=[]){
  const blocks=Array.from(this.root.querySelectorAll<HTMLElement>('.block'));if(!blocks.length){this.bounds={left:0,top:0,right:0,bottom:0};return;}
  this.bounds={left:Math.min(...blocks.map(b=>b.offsetLeft),...points.map(p=>p.x)),top:Math.min(...blocks.map(b=>b.offsetTop),...points.map(p=>p.y)),right:Math.max(...blocks.map(b=>b.offsetLeft+b.offsetWidth),...points.map(p=>p.x)),bottom:Math.max(...blocks.map(b=>b.offsetTop+b.offsetHeight),...points.map(p=>p.y))};
  this.root.style.width=Math.max(440,this.bounds.right+48)+'px';this.root.style.height=Math.max(380,this.bounds.bottom+48)+'px';
 }
 private async wires(){
  const generation=++this.routingGeneration,origin=this.root.getBoundingClientRect();
  // Measure rendered border boxes and terminal centers in unscaled diagram coordinates.
  // https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
  const box=(node:HTMLElement)=>{const r=node.getBoundingClientRect();return {x:(r.left-origin.left)/this.zoom,y:(r.top-origin.top)/this.zoom,width:r.width/this.zoom,height:r.height/this.zoom};};
  const blocks=Array.from(this.root.querySelectorAll<HTMLElement>('.block'),node=>({id:node.dataset.elementId!,...box(node)}));
  const endpoints=new Map(Array.from(this.root.querySelectorAll<HTMLElement>('.port-terminal'),node=>{const b=box(node);return [node,{x:b.x+b.width/2,y:b.y+b.height/2}] as const;}));
  const visible=new Set(blocks.map(b=>b.id));
  const connections=this.connections.filter(c=>visible.has(c.sourceElementId)||visible.has(c.targetElementId));
  const wires:RoutingRequest['wires']=[];
  for(const c of connections){
   const source=this.terminal(c.sourceElementId,c.sourcePortId),target=this.terminal(c.targetElementId,c.targetPortId);if(!source||!target)continue;
   wires.push({id:c.id,source:{owner:c.sourceElementId,...endpoints.get(source)!},target:{owner:c.targetElementId,...endpoints.get(target)!}});
  }
  this.root.dataset.routingState='pending';this.routingStatus.textContent='Routing connections…';this.routingStatus.hidden=!connections.length;
  try{
   const result=wires.length?await this.route({blocks,wires}):{routes:[]};if(generation!==this.routingGeneration)return;
   const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.classList.add('connections');svg.setAttribute('aria-label','Structural port connections');svg.setAttribute('role','img');
   const points:Point[]=[];let failures=0;
   for(const c of connections){
    const route=result.routes.find(r=>r.id===c.id);
    if(!route?.points.length){
     failures++;
     for(const [owner,id] of [[c.sourceElementId,c.sourcePortId],[c.targetElementId,c.targetPortId]]){
      const terminal=this.terminal(owner,id);if(!terminal)continue;terminal.classList.add('unroutable');
      const p=endpoints.get(terminal)!;for(const dy of [-6,6]){const cross=document.createElementNS(svg.namespaceURI,'line');cross.classList.add('route-failure');cross.setAttribute('x1',String(p.x-6));cross.setAttribute('x2',String(p.x+6));cross.setAttribute('y1',String(p.y+dy));cross.setAttribute('y2',String(p.y-dy));svg.append(cross);}
     }
     continue;
    }
    points.push(...route.points);
    const underlay=document.createElementNS(svg.namespaceURI,'polyline');underlay.classList.add('wire-underlay');underlay.setAttribute('points',route.points.map(p=>`${p.x},${p.y}`).join(' '));svg.append(underlay);
    const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',polylinePath(route.points));path.setAttribute('stroke',this.color(c.sourceElementId));path.setAttribute('data-connection-id',c.id);
    const title=document.createElementNS(svg.namespaceURI,'title');title.textContent=c.name??'Port connection';path.append(title);svg.append(path);
    if(c.name){const label=document.createElementNS(svg.namespaceURI,'text'),middle=polylineMidpoint(route.points);label.setAttribute('x',String(middle.x));label.setAttribute('y',String(middle.y-10));label.textContent=c.name;svg.append(label);}
   }
   this.root.prepend(svg);this.extent(points);this.responsiveViewport();
   this.root.dataset.routingState=failures?'failed':'ready';this.routingStatus.hidden=!failures;this.routingStatus.textContent=failures+` connection${failures===1?'':'s'} could not be routed. Inspect the marked endpoints.`;
  }catch(error){if(generation!==this.routingGeneration)return;this.root.dataset.routingState='error';this.routingStatus.hidden=false;this.routingStatus.textContent='Connection routing failed. '+String(error);}
 }
 private terminal(elementId:string,portId:string){return Array.from(this.root.querySelectorAll<HTMLElement>('.block')).find(n=>n.dataset.elementId===elementId)?.querySelector<HTMLElement>(`[data-port-id="${CSS.escape(portId)}"]`);}
 anchor(attributeId:string){return Array.from(this.root.querySelectorAll<HTMLElement>('.property-row')).find(n=>n.dataset.attributeId===attributeId)??null;}
 selectAt(attributeId:string){this.selected=attributeId;for(const row of this.root.querySelectorAll<HTMLElement>('.property-row,.port-row,.block'))row.classList.toggle('selected',row.dataset.attributeId===attributeId||row.dataset.featureId===attributeId||row.dataset.elementId===attributeId);}
}
