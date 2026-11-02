import {test as base,expect,type Page} from '@playwright/test';
import {randomUUID} from 'node:crypto';
// This fixture is destructive only on the explicitly configured isolated E2E server.
export const test=base.extend<{isolatedDemo:void}>({isolatedDemo:[async({request},use)=>{
 const current=await request.get('/api/demo');expect(current.ok()).toBe(true);
 const {generation}=await current.json();
 const reset=await request.post('/api/demo/reset',{data:{expectedGeneration:generation,operationId:randomUUID()}});expect(reset.ok()).toBe(true);
 await use();
},{auto:true}]});
export const source=(page:Page)=>page.locator('.cm-content');
export async function openText(page:Page){if(await page.locator('#source-pane').isHidden())await page.getByRole('button',{name:'Text',exact:true}).click();await expect(source(page)).toBeVisible();}
export async function closeChanges(page:Page){if(await page.locator('#collaboration').isVisible())await page.getByRole('button',{name:'Close Changes'}).click();}
export async function openChanges(page:Page){if(!await page.locator('#collaboration').isVisible()){if(!await page.locator('#toggle-panel').isVisible())await page.getByRole('button',{name:'More options'}).click();await page.locator('#toggle-panel').click();}}
export async function chooseActor(page:Page,actor:'Alice'|'Bob'){await page.locator('#view-actor').click();await page.locator('#actor-'+actor.toLowerCase()).click();}
export async function operation(page:Page,name:'Deliver'|'Update'){await openChanges(page);await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/'+name.toLowerCase())),page.getByRole('button',{name,exact:true}).click()]);await expect(page.getByRole('button',{name,exact:true})).toBeEnabled();await closeChanges(page);}
export async function savedText(page:Page):Promise<string>{const id=new URL(page.url()).searchParams.get('workspace');const response=await page.request.get('/api/workspaces/'+id);expect(response.ok()).toBe(true);return (await response.json()).source.text;}
export async function openActor(page:Page,actor:'Alice'|'Bob'='Alice'){
 await page.goto('/');await expect(page.locator('#view-actor')).toBeEnabled();
 await chooseActor(page,actor);
 await expect(page.locator('#actor-'+actor.toLowerCase())).toHaveAttribute('aria-pressed','true');await expect(page.locator('#view-actor')).toBeEnabled();await expect(pumpBlock(page)).toBeVisible();
 await expect(page.locator('#graph-state')).toContainText('Current analyzed model');
}
export function replaceFlow(text:string,value:string){
 const override=/(attribute\s+flowRate\s*:\s*>>\s*Pump::flowRate\s*=\s*)[^;]+/;
 const legacy=/(flowRate\s*:\s*Real\s*=\s*)[^;]+/;
 if(!override.test(text)&&!legacy.test(text))throw new Error('No authored flowRate override or legacy literal found');
 return text.replace(override.test(text)?override:legacy,(_,prefix:string)=>prefix+value);
}
export async function editText(page:Page,text:string){await closeChanges(page);await openText(page);await source(page).click();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.insertText(text);await expect(page.locator('#save-status')).toHaveText('Saved');}
export async function record(page:Page,description:string){await openChanges(page);await page.getByPlaceholder('Describe your change').fill(description);await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/record')),page.getByRole('button',{name:'Record change',exact:true}).click()]);await expect(page.locator('#save-status')).toHaveText('Saved');await expect(page.getByRole('button',{name:'Record change',exact:true})).toBeEnabled();await closeChanges(page);}
export const pumpBlock=(page:Page)=>page.locator('.block').filter({has:page.getByRole('heading',{name:/^pump\s*:\s*(?:Pump|VariableSpeedPump)$/})});
export const flowRow=(page:Page)=>pumpBlock(page).getByRole('button',{name:/^Inspect flowRate(?:, unresolved)?$/});
export const flowValue=(page:Page)=>flowRow(page).locator('code');
export async function literal(page:Page,value:string){await closeChanges(page);await flowRow(page).dblclick();await page.getByLabel('Literal expression').fill(value);await page.getByRole('button',{name:'Apply literal'}).click();await expect(flowValue(page)).toHaveText(value);await expect(page.locator('#graph-state')).toContainText('Current analyzed model');await expect(page.locator('#properties')).toBeHidden();}
export async function addAttribute(page:Page,name:string){await closeChanges(page);await pumpBlock(page).click({button:'right'});await page.getByRole('menuitem',{name:'Add attribute',exact:true}).click();await page.getByLabel('Attribute name').fill(name);await page.getByLabel('Scalar type').selectOption('Boolean');await page.getByLabel('Initial expression').fill('true');await page.locator('.add-attribute').getByRole('button',{name:'Add attribute',exact:true}).click();await expect(page.locator('#properties')).toBeHidden();}
