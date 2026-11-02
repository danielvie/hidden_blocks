import {readFileSync} from 'node:fs';
import {beforeAll,describe,it,expect} from 'vitest';
import {initSync,scalar_equal,project,route} from './wasm-pkg/sysml_diagram';
import fixtures from '../../contracts/golden/model-cases.json';
beforeAll(()=>{initSync({module:readFileSync(new URL('./wasm-pkg/sysml_diagram_bg.wasm',import.meta.url))});});
const value=(kind:string,canonical:string)=>JSON.stringify({typeName:kind,expression:canonical,scalar:{kind,canonical}});
describe('compiled Rust WASM contract fixtures',()=>{
 it('compares shared backend scalar fixtures without JS numeric coercion',()=>{
  for(const s of fixtures.scalars)expect(scalar_equal(value(s.kind,s.canonical),value(s.kind,s.canonical))).toBe(true);
  expect(scalar_equal(value('REAL','12.000'),value('REAL','1.2e1'))).toBe(true);
  expect(scalar_equal(value('REAL','0.123456789012345678901'),value('REAL','0.123456789012345678902'))).toBe(false);
  expect(scalar_equal(value('INTEGER','9007199254740993'),value('INTEGER','9007199254740992'))).toBe(false);
  expect(scalar_equal(JSON.stringify(fixtures.conflict.incoming.value),JSON.stringify(fixtures.conflict.local.value))).toBe(false);
  expect(scalar_equal(value('REAL','12'),value('INTEGER','12'))).toBe(false);
  expect(scalar_equal(value('REAL','12'),JSON.stringify({typeName:'OtherReal',expression:'12',scalar:{kind:'REAL',canonical:'12'}}))).toBe(false);
 });
 it('routes measured boxes through the real WASM export and preserves failed wire identities',()=>{
  const blocks=[{id:'a',x:120,y:120,width:240,height:100},{id:'obstacle',x:480,y:80,width:240,height:220},{id:'b',x:840,y:120,width:240,height:100}];
  const wires=[{id:'forward',source:{owner:'a',x:360,y:180},target:{owner:'b',x:840,y:180}},{id:'return',source:{owner:'b',x:1080,y:180},target:{owner:'a',x:120,y:180}}];
  const result=JSON.parse(route(JSON.stringify({blocks,wires})));
  expect(result.routes.map((r:{id:string})=>r.id)).toEqual(['forward','return']);
  for(let i=0;i<wires.length;i++){
   const points=result.routes[i].points;expect(points.length).toBeGreaterThan(2);
   expect(points[0]).toEqual({x:wires[i].source.x,y:180});expect(points.at(-1)).toEqual({x:wires[i].target.x,y:180});
   expect(points.some((p:{y:number})=>p.y<68||p.y>312)).toBe(true);
  }
  const trapped=JSON.parse(route(JSON.stringify({blocks:[...blocks,{id:'trap',x:350,y:160,width:40,height:40}],wires})));
  expect(trapped.routes[0]).toEqual({id:'forward',points:[]});
 });
 it('projects authored properties and attaches origin conflict badges',()=>{
  const element={id:'pump-1',documentId:'model.sysml',qualifiedName:'PumpModel::Pump',name:'Pump',kind:'PartDefinition',declarationRange:null,insertionOffset:null,attributes:[{id:'flow-1',ownerId:'pump-1',name:'flowRate',value:fixtures.conflict.local.value,valueRange:{start:1,end:3},editable:true,readOnlyReason:null},fixtures.readOnlyExpression]};
  const result=JSON.parse(project(JSON.stringify({elements:[element],baseline:[],positions:{},conflicts:[fixtures.conflict]})));
  expect(result.cards[0].properties[0].conflictId).toBe('conflict-flow');expect(result.cards[0].conflicted).toBe(true);
  expect(result.cards[0].properties[1].attribute.readOnlyReason).toBe(fixtures.readOnlyExpression.readOnlyReason);
  const after=JSON.parse(project(JSON.stringify({elements:[{...element,attributes:[]}],baseline:[],positions:result.positions,conflicts:[]})));
  expect(after.cards[0].position).toEqual(result.cards[0].position);
 });
});
