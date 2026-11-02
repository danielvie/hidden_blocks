import {request} from './api';
import type {Actor} from './types';

export interface ScenarioState {
 generation:string;
 completedStep:number;
 actor:Actor;
 message:string;
 conflicted:boolean;
 resetRequired:boolean;
}
export const scenarioApi={
 state:()=>request<ScenarioState>('/demo/scenario'),
 step:(expectedGeneration:string,step:number,actor:Actor)=>request<ScenarioState>('/demo/scenario/step',{expectedGeneration,step,actor}),
};
export function shortcutState(state:ScenarioState|null,step:number,busy:boolean){
 const completed=state?.completedStep??0;
 return {
  next:!state?.resetRequired&&step===completed+1,
  done:step>1&&step<=completed,
  conflict:step===5&&!!state?.conflicted,
  disabled:busy||!state||(step!==1&&(state.resetRequired||step!==completed+1)),
 };
}
/** Small fixed demonstration controller, not a second model/change engine. */
export class ScenarioHeader {
 state:ScenarioState|null=null;
 private sequence=0;
 constructor(private root:HTMLElement,private generation:()=>string|null,private busy:()=>boolean,
             run:(step:number)=>void){
  for(const button of root.querySelectorAll<HTMLButtonElement>('[data-demo-step]')){
   const step=Number(button.dataset.demoStep);
   if(step>1)button.onclick=()=>run(step);
  }
 }
 async refresh(){
  const sequence=++this.sequence;
  const state=await scenarioApi.state();
  if(sequence!==this.sequence||state.generation!==this.generation())return;
  this.state=state;this.render();
 }
 render(){
  for(const button of this.root.querySelectorAll<HTMLButtonElement>('[data-demo-step]')){
   const current=this.state?.generation===this.generation()?this.state:null;
   const step=Number(button.dataset.demoStep),status=shortcutState(current,step,this.busy());
   button.disabled=status.disabled;
   button.classList.toggle('is-next',status.next);
   button.classList.toggle('is-complete',status.done);
   button.classList.toggle('step-conflict',status.conflict);
   if(status.next)button.setAttribute('aria-current','step');else button.removeAttribute('aria-current');
  }
  const hint=document.getElementById('scenario-hint')!;
  hint.hidden=!this.state?.resetRequired;
  hint.textContent=this.state?.resetRequired?this.state.message:'';
 }
}
