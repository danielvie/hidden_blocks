import {describe,it,expect} from 'vitest';
import {shortcutState,type ScenarioState} from './scenario-header';
const state=(completedStep:number,extra:Partial<ScenarioState>={}):ScenarioState=>({generation:'g',completedStep,actor:'Bob',message:'',conflicted:false,resetRequired:false,...extra});
describe('seven header shortcuts',()=>{
 it('starts with Reset and enables only the next numbered action',()=>{
  expect(shortcutState(state(0),1,false)).toMatchObject({next:true,disabled:false});
  expect(shortcutState(state(1),2,false)).toMatchObject({next:true,disabled:false});
  for(const step of [3,4,5,6,7])expect(shortcutState(state(1),step,false).disabled).toBe(true);
 });
 it('keeps a conflicted publication complete and allows Bob to continue',()=>{
  expect(shortcutState(state(5,{conflicted:true}),5,false)).toMatchObject({done:true,conflict:true,disabled:true});
  expect(shortcutState(state(5,{conflicted:true}),6,false)).toMatchObject({next:true,disabled:false});
 });
 it('locks scripted mutations after manual divergence but leaves Reset available',()=>{
  for(const step of [2,3,4,5,6,7])expect(shortcutState(state(3,{resetRequired:true}),step,false).disabled).toBe(true);
  expect(shortcutState(state(3,{resetRequired:true}),1,false).disabled).toBe(false);
 });
 it('does not offer future or completed steps during an operation',()=>{
  expect(shortcutState(state(6),7,true).disabled).toBe(true);
  expect(shortcutState(state(7),7,false)).toMatchObject({done:true,next:false,disabled:true});
  expect(shortcutState(null,2,false).disabled).toBe(true);
 });
});
