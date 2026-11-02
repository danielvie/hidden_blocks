import {expect,type Page} from '@playwright/test';
import {test,flowRow,flowValue,pumpBlock,literal,chooseActor} from './helpers';

const step=(page:Page,number:number)=>page.locator('[data-demo-step="'+number+'"]');
async function runStep(page:Page,number:number){
 await expect(step(page,number)).toBeEnabled();
 await step(page,number).click();
 await expect(step(page,number)).toHaveClass(/is-complete/);
 await expect(page.locator('#scenario-feedback')).toBeVisible();
}
async function reset(page:Page,accept=true){
 const response=accept?page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/api/demo/reset')):null;
 const confirmation=page.waitForEvent('dialog');
 const click=step(page,1).click();
 const dialog=await confirmation;
 if(accept)await dialog.accept();else await dialog.dismiss();
 await click;
 if(response){expect((await response).ok()).toBe(true);await expect(step(page,2)).toBeEnabled();}
}
for(const resolver of ['Bob','Alice'] as const){
 test('seven shortcuts perform the real complete story with '+resolver+' resolving',async({page},testInfo)=>{
  await page.goto('/');await expect(step(page,1)).toBeEnabled();await reset(page);
  const before=await (await page.request.get('/api/demo')).json();
  const connections=before.alice.analysis.connections;
  const publications:string[]=[];
  page.on('request',request=>{if(request.method()==='POST')publications.push(new URL(request.url()).pathname);});
  await runStep(page,2);await expect(flowValue(page)).toHaveText('12');await expect(page.locator('#view-actor')).toContainText('Alice');
  await runStep(page,3);await expect(flowValue(page)).toHaveText('15');await expect(page.locator('#view-actor')).toContainText('Bob');
  await runStep(page,4);await expect(flowValue(page)).toHaveText('12');await expect(page.locator('#view-actor')).toContainText('Alice');
  await runStep(page,5);await expect(flowRow(page)).toHaveClass(/has-conflict/);await expect(step(page,5)).toHaveClass(/step-conflict/);
  const conflicted=await (await page.request.get('/api/demo')).json();
  expect(conflicted.bob.conflicts[0].base.value.scalar.canonical).toBe('10');
  expect(conflicted.bob.conflicts[0].incoming.value.scalar.canonical).toBe('12');
  expect(conflicted.bob.conflicts[0].local.value.scalar.canonical).toBe('15');
  expect(conflicted.alice.acceptedHead).toBe(conflicted.bob.acceptedHead);
  await expect(step(page,6)).toBeEnabled();await page.screenshot({path:testInfo.outputPath('step-5-conflict.png'),fullPage:true});
  await runStep(page,6);await expect(pumpBlock(page)).toContainText('alertEnabled');await expect(flowRow(page)).toHaveClass(/has-conflict/);
  const later=await (await page.request.get('/api/demo')).json();expect(later.bob.history).toHaveLength(2);
  await page.reload();await expect(step(page,7)).toBeEnabled();await expect(pumpBlock(page)).toContainText('alertEnabled');
  if(resolver==='Alice')await chooseActor(page,'Alice');
  await runStep(page,7);await expect(flowValue(page)).toHaveText('14');await expect(flowRow(page)).not.toHaveClass(/has-conflict/);
  await expect(page.locator('#scenario-feedback')).toContainText(/alertEnabled.*preserved/i);
  await expect(page.locator('#scenario-feedback')).toContainText(resolver);
  await expect(page.locator('#view-actor')).toContainText(resolver);
  const final=await (await page.request.get('/api/demo')).json();
  for(const actor of ['alice','bob']){
   expect(final[actor].conflicts).toEqual([]);
   expect(final[actor].source.text).toContain('= 14;');
   expect(final[actor].source.text).toContain('alertEnabled');
   expect(final[actor].analysis.connections).toEqual(connections);
   expect(final[actor].acceptedBaseRevision).toBe(final[actor].acceptedHead);
  }
  expect(final.bob.history.map((entry:{changeId:string})=>entry.changeId)).toEqual(later.bob.history.map((entry:{changeId:string})=>entry.changeId));
  expect(final.bob.history.map((entry:{revisionId:string})=>entry.revisionId)).not.toEqual(later.bob.history.map((entry:{revisionId:string})=>entry.revisionId));
  expect(publications).toEqual(Array(6).fill('/api/demo/scenario/step'));
  await expect(page.locator('#collaboration')).toBeHidden();await expect(page.locator('#source-pane')).toBeHidden();
  await expect(step(page,1)).toBeEnabled();await expect(step(page,7)).toBeDisabled();
  await page.screenshot({path:testInfo.outputPath('step-7-preserved-alert.png'),fullPage:true});
 });
}
test('shortcut progress is shared across tabs and Reset restarts it',async({page,context})=>{
 await page.goto('/');await expect(step(page,2)).toBeEnabled();
 const other=await context.newPage();await other.goto('/?actor=Bob');await expect(step(other,2)).toBeEnabled();
 await runStep(page,2);await expect(step(other,3)).toBeEnabled();
 await runStep(other,3);await expect(step(page,4)).toBeEnabled();
 const before=await (await page.request.get('/api/demo')).json();
 await reset(page,false);
 expect((await (await page.request.get('/api/demo')).json()).generation).toBe(before.generation);
 await expect(step(page,4)).toBeEnabled();
 await reset(page);
 await expect(step(other,2)).toBeEnabled();await expect(flowValue(page)).toHaveText('10');await expect(flowValue(other)).toHaveText('10');
 const progress=await (await page.request.get('/api/demo/scenario')).json();
 expect(progress.completedStep).toBe(1);expect(progress.generation).not.toBe(before.generation);
});
test('manual editing never gets overwritten by a later scripted shortcut',async({page})=>{
 await page.goto('/');await expect(step(page,2)).toBeEnabled();await runStep(page,2);
 await literal(page,'13');
 await expect(page.locator('#scenario-hint')).toBeVisible();await expect(page.locator('#scenario-hint')).toContainText(/Reset/);
 await expect(step(page,3)).toBeDisabled();await expect(step(page,1)).toBeEnabled();
 await expect(flowValue(page)).toHaveText('13');
 const state=await (await page.request.get('/api/demo/scenario')).json();
 expect(state.resetRequired).toBe(true);
 const rejected=await page.request.post('/api/demo/scenario/step',{data:{expectedGeneration:state.generation,step:3,actor:'Alice'}});
 expect(rejected.status()).toBe(409);
 await expect(flowValue(page)).toHaveText('13');
});
