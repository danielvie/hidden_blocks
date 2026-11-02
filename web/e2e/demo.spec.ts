import {expect} from '@playwright/test';
import {test,openActor,flowValue,flowRow,literal,record,operation,openChanges,closeChanges,openText,source,savedText,replaceFlow} from './helpers';
const active=(page:import('@playwright/test').Page,actor:'Alice'|'Bob')=>page.locator('#actor-'+actor.toLowerCase());
async function switchTo(page:import('@playwright/test').Page,actor:'Alice'|'Bob'){
 await closeChanges(page);await page.locator('#view-actor').click();await active(page,actor).click();await expect(active(page,actor)).toHaveAttribute('aria-pressed','true');await expect(active(page,actor)).toBeEnabled();
}
async function reset(page:import('@playwright/test').Page,accept:boolean){
 const dialog=page.waitForEvent('dialog');const click=page.getByRole('button',{name:'Reset',exact:true}).click();
 const confirmation=await dialog;expect(confirmation.message()).toBe('Reset demo? Discard both Alice and Bob work, history, shared drafts/conflicts');
 const resetResponse=accept?page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/api/demo/reset')):null;
 if(accept)await confirmation.accept();else await confirmation.dismiss();await click;
 if(resetResponse)expect((await resetResponse).ok()).toBe(true);
}

test('one disposable pair switches safely, works in parallel tabs, and resets all collaboration state',async({page,context})=>{
 await openActor(page);const initial=await savedText(page);await expect(page.getByLabel('Actor name')).toHaveCount(0);
 const oldAlice=new URL(page.url()).searchParams.get('workspace')!;
 // No Record or confirmation is required to leave a saved graphical draft.
 await literal(page,'12');await switchTo(page,'Bob');const oldBob=new URL(page.url()).searchParams.get('workspace')!;
 expect(oldBob).not.toBe(oldAlice);await expect(flowValue(page)).toHaveText('10');await literal(page,'15');await switchTo(page,'Alice');await expect(flowValue(page)).toHaveText('12');
 // A new tab defaults to Alice, never to the last actor selected in another tab.
 const other=await context.newPage();await other.goto('/');await expect(active(other,'Alice')).toHaveAttribute('aria-pressed','true');await expect(flowValue(other)).toHaveText('12');
 await switchTo(other,'Bob');await expect(flowValue(other)).toHaveText('15');await literal(other,'16');await expect(flowValue(page)).toHaveText('12');
 await record(page,'Alice persistent flow');await openChanges(page);await page.getByRole('button',{name:'Share draft',exact:true}).click();await expect(page.locator('.draft-item')).toHaveCount(1);await closeChanges(page);await operation(page,'Deliver');
 await record(other,'Bob independent flow');await operation(other,'Deliver');await expect(flowRow(other)).toHaveClass(/has-conflict/);
 const before=await (await page.request.get('/api/project')).json();expect(before.drafts).toHaveLength(1);expect(before.acceptedHistory.length).toBeGreaterThan(1);
 let resetPosts=0;other.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/api/demo/reset'))resetPosts++;});
 await reset(other,false);expect(resetPosts).toBe(0);expect((await (await page.request.get('/api/demo')).json()).generation).toBe(before.demo.generation);await expect(flowRow(other)).toHaveClass(/has-conflict/);
 await other.evaluate(id=>{sessionStorage.setItem('sysml-buffer:'+id,JSON.stringify({text:'retired source',revision:0}));sessionStorage.setItem('unrelated-test-key','keep');},oldBob);
 await reset(other,true);
 await expect.poll(()=>new URL(other.url()).searchParams.get('workspace')).not.toBe(oldBob);await expect.poll(()=>new URL(page.url()).searchParams.get('workspace')).not.toBe(oldAlice);
 await expect(active(other,'Bob')).toHaveAttribute('aria-pressed','true');await expect(active(page,'Alice')).toHaveAttribute('aria-pressed','true');
 await expect(flowValue(other)).toHaveText('10');await expect(flowValue(page)).toHaveText('10');
 const demo=await (await page.request.get('/api/demo')).json(),project=await (await page.request.get('/api/project')).json();
 expect(demo.generation).not.toBe(before.demo.generation);expect(project.acceptedHistory).toHaveLength(1);expect(project.acceptedHistory[0].parentRevisionId).toBeNull();expect(project.drafts).toEqual([]);expect(project.workspaces).toHaveLength(2);
 for(const state of [demo.alice,demo.bob]){expect(state.history).toEqual([]);expect(state.conflicts).toEqual([]);expect(state.dirty).toBe(false);expect(state.source.text).toBe(initial);expect(state.acceptedHead).toBe(project.acceptedHead);expect(state.acceptedBaseRevision).toBe(project.acceptedHead);}
 for(const id of [oldAlice,oldBob])expect((await page.request.get('/api/workspaces/'+id)).ok()).toBe(false);
 expect(await other.evaluate(id=>sessionStorage.getItem('sysml-buffer:'+id),oldBob)).toBeNull();expect(await other.evaluate(()=>sessionStorage.getItem('unrelated-test-key'))).toBe('keep');
 for(const [tab,actor] of [[page,'Alice'],[other,'Bob']] as const){await tab.reload();await expect(active(tab,actor)).toHaveAttribute('aria-pressed','true');await expect(flowValue(tab)).toHaveText('10');expect([oldAlice,oldBob]).not.toContain(new URL(tab.url()).searchParams.get('workspace'));await openText(tab);await expect(source(tab)).not.toContainText('retired source');}
 // Old deep links cannot reopen a retired environment.
 await other.goto('/?workspace='+oldBob);await expect(active(other,'Alice')).toHaveAttribute('aria-pressed','true');await expect(flowValue(other)).toHaveText('10');expect(new URL(other.url()).searchParams.get('workspace')).toBe(demo.alice.workspaceId);
});

test('pending valid and invalid source flushes before switching without recording',async({page})=>{
 await openActor(page);const initial=await savedText(page);
 for(const text of [replaceFlow(initial,'22'),initial+'\npart def Broken {']){
  await openText(page);await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(text);
  await switchTo(page,'Bob');await switchTo(page,'Alice');expect(await savedText(page)).toBe(text);await expect(source(page)).toContainText(text.endsWith('{')?'part def Broken {':'= 22;');
 }
 const demo=await (await page.request.get('/api/demo')).json();expect(demo.alice.history).toEqual([]);expect(demo.bob.history).toEqual([]);
});

test('a delayed old save response cannot revive source after another tab resets',async({page,context})=>{
 await openActor(page);const initial=await savedText(page),oldId=new URL(page.url()).searchParams.get('workspace')!;
 const other=await context.newPage();await other.goto('/?actor=Bob');await expect(active(other,'Bob')).toHaveAttribute('aria-pressed','true');await expect(active(other,'Bob')).toBeEnabled();
 let release!:()=>void,arrived!:()=>void;const gate=new Promise<void>(r=>release=r),seen=new Promise<void>(r=>arrived=r);let saves=0;
 await page.route('**/api/workspaces/*/save',async route=>{saves++;const response=await route.fetch();arrived();await gate;await route.fulfill({response});});
 await openText(page);await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(replaceFlow(initial,'97'));await seen;
 await expect(page.locator('#save-status')).toHaveText('Saving…');await reset(other,true);
 await expect.poll(()=>new URL(page.url()).searchParams.get('workspace')).not.toBe(oldId);await expect(flowValue(page)).toHaveText('10');release();
 await expect(page.locator('#save-status')).toHaveText('Saved');await expect(source(page)).not.toContainText('= 97;');expect(await savedText(page)).toBe(initial);expect(saves).toBe(1);
 await page.reload();await expect(flowValue(page)).toHaveText('10');expect(await savedText(page)).toBe(initial);
});
