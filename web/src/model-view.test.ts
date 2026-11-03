import {describe,it,expect} from 'vitest';
import {diagramScope,conflictFor,cardConflictIds,definitionFor,attributeSourceRange,analysisConnections,polylinePath,polylineMidpoint,visibleProperties} from './model-view';
import type {ModelElement,Attribute,Conflict,Card} from './types';
const element=(id:string,kind='PartDefinition',extra:Partial<ModelElement>={}):ModelElement=>({id,name:id,kind,qualifiedName:'Example::'+id,documentId:'model.sysml',declarationRange:null,insertionOffset:null,attributes:[],...extra});
describe('diagram model scope and legacy contracts',()=>{
 it('shows child usages, not their definitions or containing system',()=>{
  const elements=[element('Pump'),element('system','PartUsage'),element('pump','PartUsage',{containerId:'system',definitionId:'Pump'}),element('tank','PartUsage',{containerId:'system'})];
  const scope=diagramScope(elements);expect(scope.key).toBe('usage:system');expect(scope.title).toBe('system');expect(scope.elements.map(e=>e.id)).toEqual(['pump','tank']);
 });
 it('falls back to definitions without newer metadata and connections',()=>{
  const elements=[element('Pump'),element('Tank')];expect(diagramScope(elements).elements).toEqual(elements);expect(diagramScope(elements).key).toBe('definitions');expect(analysisConnections(undefined)).toEqual([]);
 });
 it('maps definition conflicts to the inherited displayed row',()=>{
  const row={id:'pump-speed',definitionAttributeId:'base-speed',declaredBy:'VariableSpeedPump',inherited:true} as Attribute;
  const conflict={id:'conflict',target:{attributeId:'base-speed'}} as Conflict;
  expect(conflictFor(row,[conflict])).toBe(conflict);expect(conflictFor({...row,definitionAttributeId:undefined},[conflict])).toBeUndefined();
  const base=element('VariableSpeedPump');base.attributes=[{id:'base-speed',valueRange:{start:25,end:29}} as Attribute];expect(definitionFor(element('pump','PartUsage'),row,[base])).toBe(base);
  expect(attributeSourceRange(row,[base])).toEqual({start:25,end:29});
  expect(attributeSourceRange({...row,valueRange:{start:40,end:42}},[base])).toEqual({start:40,end:42});
 });
 it('maps all targeted conflict kinds to their blocks and preserves inherited conflicts',()=>{
  const card={element:element('pump'),properties:[{attribute:{id:'inherited'} as Attribute,changed:false,conflictId:'inherited-conflict'}]} as Card;
  const conflicts=[
   {id:'inherited-conflict',kind:'VALUE',target:{elementId:'base',attributeId:'base-flow'}},
   {id:'add-conflict',kind:'ADDITION',target:{elementId:'pump',attributeId:'missing-addition'}},
   {id:'source-conflict',kind:'SOURCE',target:{elementId:'pump',attributeId:'missing-source'}},
   {id:'other-conflict',kind:'VALUE',target:{elementId:'tank',attributeId:'tank-flow'}},
  ] as Conflict[];
  expect(cardConflictIds(card,conflicts)).toEqual(['inherited-conflict','add-conflict','source-conflict']);
  expect(cardConflictIds({...card,element:element('other'),properties:[]},conflicts)).toEqual([]);
  expect(cardConflictIds({...card,properties:[{...card.properties[0],conflictId:'add-conflict'}]},conflicts)).toEqual(['add-conflict','source-conflict']);
 });
 it('keeps inherited features and every conflict visible when later additions fill a card',()=>{
  const properties=Array.from({length:9},(_,i)=>({attribute:{id:String(i),inherited:i===8} as Attribute,changed:false,conflictId:i===7?'conflict':null}));
  expect(visibleProperties(properties,false).map(p=>p.attribute.id)).toEqual(['0','1','2','3','7','8']);
  expect(visibleProperties(properties,true)).toEqual(properties);
 });
 it('places connection labels along the path, not on the middle vertex or inside a return loop',()=>{
  expect(polylineMidpoint([{x:240,y:72},{x:480,y:72}])).toEqual({x:360,y:72});
  expect(polylineMidpoint([{x:840,y:72},{x:864,y:72},{x:864,y:24},{x:96,y:24},{x:96,y:72},{x:120,y:72}])).toEqual({x:480,y:24});
  expect(polylineMidpoint([{x:1,y:2},{x:1,y:2}])).toEqual({x:1,y:2});
 });
 it('renders the router points without introducing a midpoint shortcut',()=>{
  expect(polylinePath([{x:240,y:72},{x:264,y:72},{x:264,y:120},{x:96,y:120}])).toBe('M 240 72 L 264 72 L 264 120 L 96 120');
  expect(polylinePath([])).toBe('');
 });
});
