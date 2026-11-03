import type {Analysis,Attribute,Card,Conflict,ModelElement} from './types';

/** Prefer a system's immediate part usages, retaining all definitions for inspection. */
export function diagramScope(elements:ModelElement[]) {
 const roots=elements.filter(e=>e.kind==='PartUsage'&&elements.some(child=>child.kind==='PartUsage'&&child.containerId===e.id));
 const root=roots.find(e=>!e.containerId)||roots[0];
 return root?{key:'usage:'+root.id,title:root.name,elements:elements.filter(e=>e.kind==='PartUsage'&&e.containerId===root.id)}:
  {key:'definitions',title:elements[0]?.qualifiedName.split('::')[0]??'Model',elements:elements.filter(e=>e.kind!=='PartUsage')};
}
export function conflictFor(attribute:Attribute,conflicts:Conflict[]) {
 return conflicts.find(c=>c.target.attributeId===attribute.id||!!attribute.definitionAttributeId&&c.target.attributeId===attribute.definitionAttributeId);
}
export function cardConflictIds(card:Card,conflicts:Conflict[]) {
 const ids=card.properties.flatMap(p=>p.conflictId?[p.conflictId]:[]);
 return [...new Set([...ids,...conflicts.filter(c=>c.target.elementId===card.element.id).map(c=>c.id)])];
}
export function definitionFor(element:ModelElement,attribute:Pick<Attribute,'declaredBy'>|undefined,elements:ModelElement[]) {
 if(attribute?.declaredBy)return elements.find(e=>e.id===attribute.declaredBy||e.qualifiedName===attribute.declaredBy||e.name===attribute.declaredBy);
 return elements.find(e=>e.id===element.definitionId)??element;
}
export function attributeSourceRange(attribute:Attribute,elements:ModelElement[]){
 return attribute.valueRange??elements.flatMap(e=>e.attributes).find(a=>a.id===attribute.definitionAttributeId)?.valueRange??null;
}
export function analysisConnections(analysis:Analysis|null|undefined){return analysis?.connections??[];}
export function polylinePath(points:{x:number;y:number}[]) {
 return points.map((p,i)=>`${i?'L':'M'} ${p.x} ${p.y}`).join(' ');
}
export function polylineMidpoint(points:{x:number;y:number}[]) {
 const lengths=points.slice(1).map((p,i)=>Math.abs(p.x-points[i].x)+Math.abs(p.y-points[i].y));
 let remaining=lengths.reduce((sum,n)=>sum+n,0)/2;
 for(let i=0;i<lengths.length;i++){
  if(lengths[i]>0&&remaining<=lengths[i]){const a=points[i],b=points[i+1],fraction=remaining/lengths[i];return {x:a.x+(b.x-a.x)*fraction,y:a.y+(b.y-a.y)*fraction};}
  remaining-=lengths[i];
 }
 return points[0]??{x:0,y:0};
}
export const blockColors=['#315bd6','#128275','#c97b19','#a34bae','#d14b53','#62768c'];
export function visibleProperties(properties:Card['properties'],expanded:boolean){
 if(expanded||properties.length<=6)return properties;
 const inherited=properties.filter(p=>p.attribute.inherited).slice(0,2);
 const preferred=new Set([...inherited,...properties.filter(p=>!inherited.includes(p)).slice(0,5-inherited.length)]);
 return properties.filter(p=>preferred.has(p)||p.conflictId);
}
export function shortName(name:string){return name.split('::').at(-1)??name;}
