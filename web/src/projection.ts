import type { ModelElement, Conflict, Projection, RoutingRequest, RoutingResult } from './types';
export class Projector {
 private worker=new Worker(new URL('./diagram.worker.ts',import.meta.url),{type:'module'});
 private next=0;
 private pending=new Map<number,{resolve:(result:unknown)=>void;reject:(e:Error)=>void}>();
 constructor() {
  this.worker.onmessage=({data})=>{
   const request=this.pending.get(data.id);if(!request)return;this.pending.delete(data.id);
   data.error?request.reject(new Error(data.error)):request.resolve(data.result);
  };
  this.worker.onerror=(event)=>{for(const r of this.pending.values())r.reject(new Error('Rust WASM worker failed. '+event.message));this.pending.clear();};
 }
 project(elements:ModelElement[],baseline:ModelElement[],conflicts:Conflict[],reset=false,scopeKey='definitions'):Promise<Projection> {
  return this.request<Projection>({elements,baseline,conflicts,reset,scopeKey});
 }
 route(geometry:RoutingRequest):Promise<RoutingResult>{return this.request<RoutingResult>({geometry});}
 private request<T>(payload:object):Promise<T>{
  const id=++this.next;
  return new Promise((resolve,reject)=>{
   this.pending.set(id,{resolve:result=>resolve(result as T),reject});
   this.worker.postMessage({id,...payload});
  });
 }
}
