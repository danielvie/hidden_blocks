import {expect} from '@playwright/test';
import {test,source,savedText,openActor,record,literal,pumpBlock,flowValue,openText,openChanges,closeChanges,replaceFlow} from './helpers';
async function diagnostics(page:import('@playwright/test').Page){await page.getByRole('button',{name:'More options'}).click();await page.getByRole('menuitem',{name:'Diagnostics and recovery'}).click();}

test('shared drafts stay immutable and never replace the live buffer',async({page})=>{
 await openActor(page);await literal(page,'123456');await record(page,'Draft value');await openChanges(page);
 const description='Read-only draft '+Date.now();await page.getByPlaceholder('Describe your change').fill(description);
 let first=true;const operationIds:string[]=[];
 await page.route('**/api/workspaces/*/share',async route=>{operationIds.push(route.request().postDataJSON().operationId);const response=await route.fetch();if(first){first=false;await route.abort();}else await route.fulfill({response});});
 await page.getByRole('button',{name:'Share draft',exact:true}).click();await expect(page.getByRole('button',{name:'Retry share',exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'Record change',exact:true})).toBeDisabled();await closeChanges(page);await page.getByRole('button',{name:'Retry share',exact:true}).click();await expect.poll(()=>operationIds.length).toBe(2);expect(operationIds[0]).toBe(operationIds[1]);
 await openChanges(page);const draft=page.locator('.draft-item').filter({hasText:description});await expect(draft).toBeVisible();await closeChanges(page);
 await literal(page,'123457');await record(page,'Later local value');await openText(page);const live=await source(page).innerText();await openChanges(page);
 await draft.click();await expect(page.locator('#snapshot-dialog')).toBeVisible();await expect(page.locator('#snapshot-source')).toContainText('123456');await expect(page.locator('#snapshot-source')).not.toContainText('123457');await expect(page.locator('#snapshot-meta')).toContainText('immutable');
 await page.getByRole('button',{name:'Close snapshot'}).click();await closeChanges(page);await expect.poll(()=>source(page).innerText()).toBe(live);await expect(flowValue(page)).toHaveText('123457');
});
test('a stale workspace write retains source and is never silently resubmitted',async({page,context})=>{
 await openActor(page);const initial=await savedText(page);const other=await context.newPage();await other.goto(page.url());await expect(pumpBlock(other)).toBeVisible();await literal(other,'31');let saveRequests=0;page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/save'))saveRequests++;});
 await openText(page);await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(replaceFlow(initial,'32'));
 await expect(page.locator('#save-status')).toHaveText('Save failed');await expect(source(page)).toContainText('= 32;');
 await page.locator('#view-actor').click();await page.locator('#actor-bob').click();await expect(page.locator('#actor-alice')).toHaveAttribute('aria-pressed','true');await expect(source(page)).toContainText('= 32;');
 await diagnostics(page);await page.getByRole('button',{name:'Retry save',exact:true}).click();await expect(page.locator('#error')).toContainText('advanced elsewhere');
 expect(saveRequests).toBe(1);expect(await savedText(page)).toContain('= 31;');await expect(source(page)).toContainText('= 32;');
});
test('a delayed real save response cannot restore older typing',async({page})=>{
 await openActor(page);const initial=await savedText(page);const changed=(value:string)=>replaceFlow(initial,value);
 let release!:()=>void,arrived!:()=>void,first=true;const gate=new Promise<void>(r=>release=r),seen=new Promise<void>(r=>arrived=r);
 await page.route('**/api/workspaces/*/save',async route=>{const response=await route.fetch();if(first){first=false;arrived();await gate;}await route.fulfill({response});});
 await openText(page);await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(changed('20'));await seen;await expect(page.locator('#save-status')).toHaveText('Saving…');
 await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(changed('21'));release();await expect(page.locator('#save-status')).toHaveText('Saved');await expect(source(page)).toContainText('= 21;');await expect(flowValue(page)).toHaveText('21');expect(await savedText(page)).toContain('= 21;');
});
test('recovery stays local until an explicit retry and the source starts closed',async({page})=>{
 await openActor(page);const initial=await savedText(page),recovered=replaceFlow(initial,'8765');
 const id=new URL(page.url()).searchParams.get('workspace')!;const response=await page.request.get('/api/workspaces/'+id),state=await response.json();
 await page.evaluate(({id,text,revision})=>sessionStorage.setItem('sysml-buffer:'+id,JSON.stringify({text,revision})),{id,text:recovered,revision:state.revision});let saves=0;page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/save'))saves++;});
 await page.reload();await expect(page.locator('#error')).toContainText('Recovered an unsaved buffer');await expect(page.locator('#source-pane')).toBeHidden();await openText(page);await expect(source(page)).toContainText('= 8765;');expect(saves).toBe(0);expect(await savedText(page)).toBe(initial);
 await diagnostics(page);await page.getByRole('button',{name:'Retry save',exact:true}).click();await expect(page.locator('#save-status')).toHaveText('Saved');expect(await savedText(page)).toContain('= 8765;');
});
