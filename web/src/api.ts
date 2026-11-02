import type { Mutation, WorkspaceState, Project, Draft, DemoState } from './types';
export class ApiError extends Error {
  constructor(public status:number, message:string) { super(message); }
}
export async function request<T>(path:string, body?:object):Promise<T> {
  let response:Response;
  try { response = await fetch('/api'+path, body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : undefined); }
  catch { throw new Error('Cannot reach the service. Check the backend, then retry. Your source is retained.'); }
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, data?.message ?? `Service returned HTTP ${response.status}. Check the backend and retry.`);
  if (!data) throw new Error('The service returned an unreadable response. Your source is retained.');
  return data as T;
}
export const api = {
  project:()=>request<Project>('/project'),
  demo:()=>request<DemoState>('/demo'),
  reset:(expectedGeneration:string,operationId:string)=>request<DemoState>('/demo/reset',{expectedGeneration,operationId}),
  create:(actor:string)=>request<Mutation>('/workspaces',{actor}),
  open:(id:string)=>request<WorkspaceState>('/workspaces/'+encodeURIComponent(id)),
  draft:(id:string)=>request<Draft>('/drafts/'+encodeURIComponent(id)),
  mutate:(id:string,operation:string,body:object)=>request<Mutation>('/workspaces/'+encodeURIComponent(id)+'/'+operation,body),
};
