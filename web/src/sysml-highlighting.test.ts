import {describe,it,expect} from 'vitest';
import {EditorState} from '@codemirror/state';
import {highlightTree} from '@lezer/highlight';
import {syntaxTree} from '@codemirror/language';
import {readFileSync} from 'node:fs';
import {sysmlLanguage,sysmlHighlightStyle,sysmlHighlighting} from './sysml-highlighting';

function tokens(source:string){
 const output:{text:string;style:string}[]=[];
 highlightTree(sysmlLanguage.parser.parse(source),sysmlHighlightStyle,(from,to,style)=>output.push({text:source.slice(from,to),style}));
 return output;
}
describe('display-only SysML syntax coloring',()=>{
 it('colors the reference keywords, types, specialization and override operators, and literals',()=>{
  const source=readFileSync(new URL('../../fixtures/connected-pump.sysml',import.meta.url),'utf8');
  const result=tokens(source);
  const has=(text:string,style:string)=>result.some(t=>t.text.trim()===text&&t.style.includes(style));
  expect(has('package','sysml-keyword')).toBe(true);
  expect(has('VariableSpeedPump','sysml-type')).toBe(true);
  expect(has('Real','sysml-type')).toBe(true);
  expect(has(':>','sysml-operator')).toBe(true);
  expect(has(':>>','sysml-operator')).toBe(true);
  expect(has('10','sysml-literal')).toBe(true);
 });
 it('keeps line and multiline comments separate from code',()=>{
  const result=tokens('// part fake = 12;\n/* first\n part Fake { }\n */ part def RealType;');
  expect(result.filter(t=>t.style==='sysml-comment').map(t=>t.text).join('')).toContain('part Fake');
  expect(result.filter(t=>t.style==='sysml-keyword').map(t=>t.text.trim())).toEqual(['part','def']);
 });
 it('keeps escaped strings and comment-like contents inside string tokens',()=>{
  const result=tokens('attribute label : String = "pump \\"A\\" // not a comment"; // actual comment');
  expect(result.filter(t=>t.style==='sysml-string').map(t=>t.text)).toEqual(['"pump \\"A\\" // not a comment"']);
  expect(result.filter(t=>t.style==='sysml-comment').map(t=>t.text)).toEqual(['// actual comment']);
 });
 it('colors Boolean/exponent literals and quoted names without changing source',()=>{
  const source="part def 'Pump type' { attribute enabled : Boolean = true; attribute rate : Real = -1.25e+3; }";
  const result=tokens(source);
  expect(result).toContainEqual({text:"'Pump type'",style:'sysml-type'});
  expect(result).toContainEqual({text:'true',style:'sysml-literal'});
  expect(result).toContainEqual({text:'1.25e+3',style:'sysml-literal'});
  const state=EditorState.create({doc:source,extensions:sysmlHighlighting});
  expect(state.doc.toString()).toBe(source);expect(syntaxTree(state).length).toBe(source.length);
 });
 it('keeps tokenization useful during incomplete edits and updates on changes',()=>{
  const source='part def Pump { attribute label : String = "unfinished';
  expect(tokens(source).some(t=>t.style==='sysml-string')).toBe(true);
  let state=EditorState.create({doc:'part def Pump {',extensions:sysmlHighlighting});
  state=state.update({changes:{from:state.doc.length,insert:' attribute enabled : Boolean = true; }'}}).state;
  const highlighted:{text:string;style:string}[]=[];
  highlightTree(syntaxTree(state),sysmlHighlightStyle,(from,to,style)=>highlighted.push({text:state.doc.sliceString(from,to),style}));
  expect(highlighted).toContainEqual({text:'true',style:'sysml-literal'});
 });
});
