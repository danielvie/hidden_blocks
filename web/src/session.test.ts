import {describe,it,expect} from 'vitest';
import {Session,currentAnalysis} from './session';
import type {WorkspaceState,Mutation} from './types';
const state=(text='10',revision=0):WorkspaceState=>({workspaceId:'Bob',actor:'Bob',revision,acceptedBaseRevision:'R0',acceptedHead:'R0',source:{documentId:'model.sysml',version:revision,text},analysis:{workspaceId:'Bob',workspaceRevision:revision,documentVersions:{'model.sysml':revision},state:'VALID',elements:[],diagnostics:[]},lastValidAnalysis:null,conflicts:[],dirty:false,history:[]});
const result=(s:WorkspaceState):Mutation=>({status:'ok',message:'saved',state:s});
describe('serialized autosave',()=>{
 it('never resubmits a recovered buffer over an advanced server revision',async()=>{
  let calls=0;const transport={mutate:async()=>{calls++;return result(state('12',3));},open:async()=>state('15',2)};
  const s=new Session(state('15',2),()=>{},transport as never);s.recover('12',0);
  s.type('13');expect(s.recoveryRevision).toBe(0);await expect(s.retry()).rejects.toThrow('advanced');expect(calls).toBe(0);expect(s.text).toBe('13');
 });
 it('never restores an older buffer and uses the newest returned versions',async()=>{
  const calls:object[]=[]; let resolve!:(m:Mutation)=>void;
  const transport={mutate:async(_id:string,_op:string,body:object)=>{calls.push(body);if(calls.length===1)return new Promise<Mutation>(r=>resolve=r);return result(state('14',2));},open:async()=>state()};
  const s=new Session(state(),()=>{},transport as never);
  s.type('12');const flush=s.flush();s.type('14');
  expect(currentAnalysis(s)).toBe(false);
  resolve(result(state('12',1)));await flush;
  expect(s.text).toBe('14');expect(calls).toEqual([{expectedRevision:0,documentVersion:0,text:'12'},{expectedRevision:1,documentVersion:1,text:'14'}]);
 });
 it('serializes a mutation even when an autosave is already in flight',async()=>{
  const calls:{operation:string;body:object}[]=[];let resolve!:(m:Mutation)=>void;
  const transport={mutate:async(_id:string,operation:string,body:object)=>{calls.push({operation,body});if(calls.length===1)return new Promise<Mutation>(r=>resolve=r);return result(state('14',calls.length));},open:async()=>state()};
  const s=new Session(state(),()=>{},transport as never);s.type('12');const save=s.flush();s.type('14');const record=s.mutate('record',{description:'flow'});resolve(result(state('12',1)));await Promise.all([save,record]);
  expect(calls).toEqual([{operation:'save',body:{expectedRevision:0,documentVersion:0,text:'12'}},{operation:'save',body:{expectedRevision:1,documentVersion:1,text:'14'}},{operation:'record',body:{description:'flow',expectedRevision:2}}]);expect(s.text).toBe('14');
 });
 it('flushes source before a mutation',async()=>{
  const operations:string[]=[];
  const transport={mutate:async(_id:string,op:string)=>{operations.push(op);return result(state('12',operations.length));},open:async()=>state()};
  const s=new Session(state(),()=>{},transport as never);s.type('12');await s.mutate('record');expect(operations).toEqual(['save','record']);
 });
 it('does not silently retry failed or stale writes',async()=>{
  let calls=0;
  const transport={mutate:async()=>{calls++;throw new Error('stale');},open:async()=>state('15',2)};
  const s=new Session(state(),()=>{},transport as never);s.type('12');await expect(s.flush()).rejects.toThrow('stale');
  s.type('14');await expect(s.flush()).rejects.toThrow('stale');await expect(s.retry()).rejects.toThrow('advanced');
  expect(calls).toBe(1);expect(s.text).toBe('14');
 });
});
