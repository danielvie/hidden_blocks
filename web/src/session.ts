import { api } from './api';
import type { WorkspaceState, Mutation } from './types';
export type SaveStatus = 'saved'|'unsaved'|'saving'|'failed';
/** One writer per window. Failed or stale requests stop the queue, never replay unknown edits. */
export class Session {
  text:string;
  status:SaveStatus='saved';
  error='';
  private pending:Promise<void>|null=null;
  private recoveredRevision:number|null=null;
  private timer:ReturnType<typeof setTimeout>|undefined;
  locked=false;
  private retired=false;
  retire(){this.retired=true;clearTimeout(this.timer);}
  private assertActive(){if(this.retired)throw new Error('This demo session was retired. Use the current environment.');}
  private notify(){if(!this.retired)this.changed();}
  get recoveryRevision(){return this.recoveredRevision??this.state.revision;}
  constructor(public state:WorkspaceState, private changed:()=>void, private transport:Pick<typeof api,'open'|'mutate'>=api) { this.text=state.source.text; }
  type(text:string) {
    this.assertActive();
    this.text=text; this.status=this.status==='failed'?'failed':'unsaved';
    clearTimeout(this.timer); this.notify();
    if (this.status!=='failed') this.timer=setTimeout(()=>{ void this.flush().catch(()=>{}); },400);
  }
  async flush():Promise<void> {
    this.assertActive();
    clearTimeout(this.timer);
    if (this.status==='failed') throw new Error(this.error || 'Save failed. Retry save before continuing.');
    if (this.pending) { await this.pending; if(this.text!==this.state.source.text) return this.flush(); return; }
    if(this.text===this.state.source.text) { this.status='saved'; return; }
    const text=this.text;
    this.status='saving'; this.notify();
    this.pending=(async()=>{
      try {
        const result=await this.transport.mutate(this.state.workspaceId,'save',{expectedRevision:this.state.revision,documentVersion:this.state.source.version,text});
        this.assertActive();
        this.state=result.state;
        // Never replace this.text: the user may have typed during this request.
        this.status=this.text===this.state.source.text?'saved':'unsaved';
      } catch(e) { this.status='failed'; this.error=String(e instanceof Error?e.message:e); throw e; }
      finally { this.pending=null; this.notify(); }
    })();
    await this.pending;
    if(this.text!==this.state.source.text) await this.flush();
  }
  async retry() {
    this.assertActive();
    const fresh=await this.transport.open(this.state.workspaceId);
    this.assertActive();
    if(fresh.source.text===this.text) { this.state=fresh; this.recoveredRevision=null; this.status='saved'; this.error=''; this.notify(); return; }
    if(fresh.revision!==(this.recoveredRevision??this.state.revision) || fresh.source.version!==this.state.source.version) {
      throw new Error('The workspace advanced elsewhere. Copy your source, then explicitly reload the workspace and reconcile it. The failed edit was not resubmitted.');
    }
    this.recoveredRevision=null; this.status='unsaved'; this.error=''; await this.flush();
  }
  recover(text:string,revision:number=this.state.revision) { this.text=text; this.recoveredRevision=revision; this.status='failed'; this.error='Recovered an unsaved buffer. Review the source, then Retry save. It will not be sent automatically.'; }
  async mutate(operation:string,body:object={}):Promise<Mutation> {
    this.assertActive();
    if(this.locked) throw new Error('Another operation is running.');
    this.locked=true; this.notify();
    try {
      await this.flush();
      const result=await this.transport.mutate(this.state.workspaceId,operation,{...body,expectedRevision:this.state.revision,...(['edit','add'].includes(operation)?{documentVersion:this.state.source.version}:{})});
      this.assertActive();
      this.state=result.state; this.text=result.state.source.text; this.status='saved'; this.error='';
      return result;
    } finally { this.locked=false; this.notify(); }
  }
}
export function currentAnalysis(s:Session):boolean {
  const a=s.state.analysis;
  return s.status==='saved' && s.text===s.state.source.text && a?.state==='VALID' && a.workspaceRevision===s.state.revision && a.documentVersions[s.state.source.documentId]===s.state.source.version;
}
