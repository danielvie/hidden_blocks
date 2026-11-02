import {api} from './api';
import {Session} from './session';
import type {Actor,DemoState,Project,WorkspaceState} from './types';

export const recoveryPrefix='sysml-buffer:';
/** The server generation and session identity fence every asynchronous writer. */
export class Demo {
 actor:Actor='Alice';
 generation:string|null=null;
 session:Session|null=null;
 private pair:DemoState|null=null;
 private epoch=0;
 private checking:Promise<void>|null=null;
 constructor(private opened:(session:Session)=>void,private changed:()=>void,private projectChanged:(project:Project)=>void,private transport=api,private storage:Storage=sessionStorage){}
 private install(demo:DemoState,state:WorkspaceState){
  this.session?.retire();++this.epoch;
  const previousGeneration=this.storage.getItem(recoveryPrefix+'generation');
  if(previousGeneration!==null&&previousGeneration!==demo.generation){
   for(const key of Object.keys(this.storage))if(key.startsWith(recoveryPrefix))this.storage.removeItem(key);
  }
  this.storage.setItem(recoveryPrefix+'generation',demo.generation);
  this.pair=demo;this.generation=demo.generation;
  let owner:Session;
  const assertCurrent=()=>{if(this.session!==owner)throw new Error('The demo was reset or the environment changed. Old requests were discarded.');};
  owner=new Session(state,()=>{if(this.session===owner)this.changed();},{
   open:async id=>{await this.check();assertCurrent();const result=await this.transport.open(id);assertCurrent();return result;},
   mutate:async(id,operation,body)=>{await this.check();assertCurrent();const result=await this.transport.mutate(id,operation,body);assertCurrent();return result;},
  });
  this.session=owner;this.opened(owner);
 }
 async start(params:URLSearchParams){
  const demo=await this.transport.demo();
  this.actor=params.get('actor')==='Bob'||params.get('workspace')===demo.bob.workspaceId?'Bob':'Alice';
  this.install(demo,this.actor==='Bob'?demo.bob:demo.alice);
  await this.check();
 }
 async check():Promise<void>{
  if(!this.session)return;
  if(this.checking)return this.checking;
  this.checking=(async()=>{
   const epoch=this.epoch,project=await this.transport.project();
   if(epoch!==this.epoch)return;
   if(project.demo?.generation!==this.generation){
    this.session?.retire();
    const demo=await this.transport.demo();
    if(epoch!==this.epoch)return;
    this.install(demo,this.actor==='Bob'?demo.bob:demo.alice);
    // Do not render an older project snapshot over the new pair.
    if(project.demo?.generation!==demo.generation)return;
   }
   this.projectChanged(project);
  })();
  try{await this.checking;}finally{this.checking=null;}
 }
 async switchActor(actor:Actor){
  await this.check();
  const owner=this.session;
  if(!owner||owner.locked)throw new Error('Wait for the current operation to finish.');
  if(this.actor===actor)return;
  await owner.flush();
  await this.check();
  if(this.session!==owner)throw new Error('The demo was reset. Switch again in the fresh environment.');
  const state=await this.transport.open(actor==='Bob'?this.pair!.bob.workspaceId:this.pair!.alice.workspaceId);
  if(this.session!==owner)throw new Error('The demo changed while opening the environment. Try again.');
  this.actor=actor;this.install(this.pair!,state);
 }
 async reload(){
  await this.check();
  const owner=this.session;
  if(!owner)return;
  const state=await this.transport.open(owner.state.workspaceId);
  if(owner!==this.session)throw new Error('The demo changed while reloading. Try again.');
  this.install(this.pair!,state);
 }
 async reset(expectedGeneration:string,operationId:string){
  await this.transport.reset(expectedGeneration,operationId);
  // Polling may already have opened the fresh epoch while this response was delayed.
  // Do not discard edits made there, or revive an even newer reset generation.
  if(this.generation!==expectedGeneration){await this.check();return;}
  // Fetch the current pair, not a possibly delayed or replayed reset response.
  const epoch=++this.epoch;
  this.session?.retire();
  const demo=await this.transport.demo();
  if(epoch!==this.epoch)return;
  this.install(demo,this.actor==='Bob'?demo.bob:demo.alice);
  await this.check();
 }
}
