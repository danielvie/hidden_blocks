import {describe,it,expect} from 'vitest';
import {api} from './api';
import {Demo,recoveryPrefix} from './demo';
import type {Actor,DemoState,Mutation,Project,WorkspaceState} from './types';
const state=(actor:Actor,generation='g1',text='10',revision=0):WorkspaceState=>({workspaceId:generation+actor,actor,revision,acceptedBaseRevision:'root',acceptedHead:'root',source:{documentId:'model.sysml',version:revision,text},analysis:{workspaceId:generation+actor,workspaceRevision:revision,documentVersions:{'model.sysml':revision},state:text==='invalid'?'INVALID':'VALID',elements:[],diagnostics:[]},lastValidAnalysis:null,conflicts:[],dirty:false,history:[]});
const pair=(generation='g1'):DemoState=>({generation,alice:state('Alice',generation),bob:state('Bob',generation)});
function storage():Storage{
 const data:Record<string,unknown>={};
 return Object.assign(data,{getItem:(key:string)=>data[key]??null,setItem:(key:string,value:string)=>{data[key]=value;},removeItem:(key:string)=>{delete data[key];}}) as unknown as Storage;
}
function fixture(){
 let current=pair();const calls:string[]=[];const store=storage();
 const project=():Project=>({demo:{generation:current.generation,aliceWorkspaceId:current.alice.workspaceId,bobWorkspaceId:current.bob.workspaceId},acceptedHead:'root',workspaces:[],drafts:[]});
 const transport={...api,demo:async()=>current,project:async()=>project(),open:async(id:string)=>id===current.alice.workspaceId?current.alice:current.bob,
  reset:async(_generation:string,_operationId:string)=>{current=pair('g2');return current;},
  mutate:async(id:string,operation:string,body:object):Promise<Mutation>=>{calls.push(operation);const key=id===current.alice.workspaceId?'alice':'bob';const before=current[key];const next=state(before.actor as Actor,current.generation,(body as {text:string}).text,before.revision+1);current={...current,[key]:next};return {status:'ok',message:'saved',state:next};},
 };
 let opened=0,changed=0;
 const demo=new Demo(()=>{opened++;},()=>{changed++;},()=>{},transport,store);
 return {demo,transport,store,calls,project,get current(){return current;},get opened(){return opened;},get changed(){return changed;}};
}
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve};}
describe('disposable demo sessions',()=>{
 it('defaults to Alice, recognizes only the current Bob ID or explicit actor',async()=>{
  for(const [params,actor] of [['','Alice'],['workspace=retiredBob','Alice'],['actor=Bob','Bob'],['workspace=g1Bob','Bob']] as const){const f=fixture();await f.demo.start(new URLSearchParams(params));expect(f.demo.actor).toBe(actor);expect(f.demo.session?.state.workspaceId).toBe('g1'+actor);}
 });
 it('does not erase a current-environment recovery buffer on first startup',async()=>{
  const f=fixture();f.store.setItem(recoveryPrefix+'g1Alice','unsaved current source');await f.demo.start(new URLSearchParams());expect(f.store.getItem(recoveryPrefix+'g1Alice')).toBe('unsaved current source');expect(f.store.getItem(recoveryPrefix+'generation')).toBe('g1');
 });
 for(const text of ['12','invalid'])it('flushes pending '+text+' text when switching without recording',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());f.demo.session!.type(text);
  await f.demo.switchActor('Bob');expect(f.current.alice.source.text).toBe(text);expect(f.calls).toEqual(['save']);
  f.demo.session!.type('15');await f.demo.switchActor('Alice');expect(f.demo.session!.text).toBe(text);expect(f.current.bob.source.text).toBe('15');expect(f.calls).toEqual(['save','save']);
 });
 it('blocks switching after a failed save and retains the exact buffer',async()=>{
  const f=fixture();f.transport.mutate=async()=>{throw new Error('stale');};await f.demo.start(new URLSearchParams());const owner=f.demo.session!;owner.type('invalid');
  await expect(f.demo.switchActor('Bob')).rejects.toThrow('stale');expect(f.demo.session).toBe(owner);expect(owner.text).toBe('invalid');expect(f.demo.actor).toBe('Alice');
 });
 it('ordinary polling does not replace unsaved text',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const owner=f.demo.session!;owner.type('unsaved');await f.demo.check();expect(f.demo.session).toBe(owner);expect(owner.text).toBe('unsaved');owner.retire();
 });
 it('retires a delayed old save across reset, keeps Bob, and clears only app buffers',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams('actor=Bob'));const old=f.demo.session!;
  f.store.setItem(recoveryPrefix+old.state.workspaceId,'old');f.store.setItem('unrelated','keep');
  const gate=deferred<Mutation>(),sent=deferred<void>();f.transport.mutate=async()=>{sent.resolve();return gate.promise;};old.type('old source');const saving=old.flush();await sent.promise;
  await f.demo.reset('g1','reset-one');const fresh=f.demo.session!;const changes=f.changed;
  gate.resolve({status:'ok',message:'saved',state:state('Bob','g1','old source',1)});await expect(saving).rejects.toThrow(/retired|discarded/);
  expect(f.demo.session).toBe(fresh);expect(fresh.state.workspaceId).toBe('g2Bob');expect(fresh.text).toBe('10');expect(f.changed).toBe(changes);expect(f.store.getItem(recoveryPrefix+'g1Bob')).toBeNull();expect(f.store.getItem('unrelated')).toBe('keep');
 });
 it('discards a delayed old project response after reset',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const oldProject=f.project(),gate=deferred<Project>();let first=true;
  f.transport.project=async()=>{if(first){first=false;return gate.promise;}return f.project();};
  const checking=f.demo.check();const resetting=f.demo.reset('g1','reset-two');
  // reset installs from /demo before its final project check joins this pending check.
  await Promise.resolve();await Promise.resolve();await Promise.resolve();
  gate.resolve(oldProject);await Promise.all([checking,resetting]);expect(f.demo.generation).toBe('g2');expect(f.demo.session!.state.workspaceId).toBe('g2Alice');
 });
 it('a delayed reset response preserves edits in the generation polling already opened',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const resetServer=f.transport.reset,gate=deferred<DemoState>();
  f.transport.reset=async()=>{const result=await resetServer('g1','reset-delayed');await gate.promise;return result;};
  const resetting=f.demo.reset('g1','reset-delayed');await f.demo.check();const fresh=f.demo.session!;expect(f.demo.generation).toBe('g2');fresh.type('new generation draft');
  gate.resolve(f.current);await resetting;expect(f.demo.session).toBe(fresh);expect(fresh.text).toBe('new generation draft');fresh.retire();
 });
 it('cannot install a delayed actor-open response over a reset session',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const gate=deferred<WorkspaceState>(),sent=deferred<void>();f.transport.open=async()=>{sent.resolve();return gate.promise;};
  const switching=f.demo.switchActor('Bob');await sent.promise;await f.demo.reset('g1','reset-three');const fresh=f.demo.session;
  gate.resolve(state('Bob'));await expect(switching).rejects.toThrow('changed');expect(f.demo.session).toBe(fresh);expect(f.demo.actor).toBe('Alice');expect(f.demo.session!.state.workspaceId).toBe('g2Alice');
 });
 it('cannot install a delayed recorded mutation over a reset session',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const gate=deferred<Mutation>(),sent=deferred<void>();f.transport.mutate=async()=>{sent.resolve();return gate.promise;};
  const recording=f.demo.session!.mutate('record');await sent.promise;await f.demo.reset('g1','reset-four');const fresh=f.demo.session;
  gate.resolve({status:'ok',message:'recorded',state:state('Alice','g1','old record',1)});await expect(recording).rejects.toThrow('discarded');expect(f.demo.session).toBe(fresh);expect(fresh!.text).toBe('10');
 });
 it('detects another tab reset before sending an old mutation',async()=>{
  const f=fixture();await f.demo.start(new URLSearchParams());const old=f.demo.session!;await f.transport.reset('g1','other-tab');
  await expect(old.mutate('record')).rejects.toThrow(/retired|discarded/);expect(f.calls).toEqual([]);expect(f.demo.session!.state.workspaceId).toBe('g2Alice');
 });
});
