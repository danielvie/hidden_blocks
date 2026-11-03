import {expect,type Page} from '@playwright/test';
import {readFileSync} from 'node:fs';
import AxeBuilder from '@axe-core/playwright';
import {test,source,savedText,openActor,editText,record,literal,pumpBlock,flowRow,flowValue,openText,openChanges,closeChanges,operation,replaceFlow,addAttribute} from './helpers';

async function expectClearRoutes(page:Page){
 const routes=await page.locator('.connections path').evaluateAll(paths=>paths.map(node=>{
  const path=node as SVGPathElement,matrix=path.getScreenCTM()!,numbers=path.getAttribute('d')!.match(/-?\d+(?:\.\d+)?(?:e[-+]?\d+)?/gi)!.map(Number);
  const points=Array.from({length:numbers.length/2},(_,i)=>new DOMPoint(numbers[i*2],numbers[i*2+1]).matrixTransform(matrix));
  const blocks=Array.from(document.querySelectorAll('.block'),n=>n.getBoundingClientRect());let hits=0,orthogonal=true;
  for(let i=1;i<points.length;i++){
   const a=points[i-1],b=points[i];orthogonal&&=Math.abs(a.x-b.x)<.01||Math.abs(a.y-b.y)<.01;
   for(const r of blocks)if(Math.min(a.x,b.x)<r.right-.5&&Math.max(a.x,b.x)>r.left+.5&&Math.min(a.y,b.y)<r.bottom-.5&&Math.max(a.y,b.y)>r.top+.5)hits++;
  }
  return {hits,orthogonal,leavesRight:points[1].x>points[0].x,entersFromLeft:points.at(-2)!.x<points.at(-1)!.x};
 }));
 expect(routes.length).toBeGreaterThan(0);
 for(const route of routes)expect(route).toEqual({hits:0,orthogonal:true,leavesRight:true,entersFromLeft:true});
}

test('real Rust WASM initializes in a browser worker',async({page})=>{
 const wasmResponses:string[]=[];page.on('response',r=>{if(r.url().includes('.wasm'))wasmResponses.push(r.url());});
 await page.goto('/');await expect(page.locator('#wasm-status')).toHaveText('Rust WASM worker ready');expect(wasmResponses.length).toBeGreaterThan(0);expect(page.workers().length).toBe(1);
});
test('a failed WASM load is explicit and never falls back to a fake diagram',async({page})=>{
 await page.route('**/*.wasm',route=>route.abort());await page.goto('/');await expect(page.locator('#wasm-status')).toHaveText('Rust WASM failed');await expect(page.locator('#error')).toBeVisible();await expect(page.locator('#graph-state')).toContainText('initialization failed');await expect(page.locator('.block')).toHaveCount(0);await expect(page.locator('#record')).toBeDisabled();
});
for(const mode of ['text-first','diagram-first'] as const){
 test('two workspaces complete conflict, descendant replay and explicit update · '+mode,async({browser,baseURL})=>{
  const aliceContext=await browser.newContext({baseURL,viewport:{width:1440,height:1000}}),bobContext=await browser.newContext({baseURL,viewport:{width:1440,height:1000}});
  const alice=await aliceContext.newPage(),bob=await bobContext.newPage();const errors:string[]=[];for(const p of [alice,bob])p.on('pageerror',e=>errors.push(e.message));
  await openActor(alice,'Alice');await openActor(bob,'Bob');expect(new URL(alice.url()).searchParams.get('workspace')).not.toBe(new URL(bob.url()).searchParams.get('workspace'));
  const fixed=await pumpBlock(bob).evaluate(el=>({left:(el as HTMLElement).style.left,top:(el as HTMLElement).style.top}));
  const initial=await savedText(alice),alertName='alertEnabledE2e'+Date.now();
  const current=(await flowValue(alice).innerText()).trim(),aliceValue=current==='12'?'13':'12',bobValue=current==='15'?'16':'15';
  if(mode==='text-first')await editText(alice,replaceFlow(initial,aliceValue));else await literal(alice,aliceValue);
  await expect(flowValue(alice)).toHaveText(aliceValue);await record(alice,'Alice flow');await operation(alice,'Deliver');await expect(alice.locator('#notice')).toContainText(/deliver|accept|publish/i);
  await openChanges(alice);await expect(alice.locator('.accepted-history').getByText('Deliver Alice changes',{exact:true}).first()).toBeVisible();await closeChanges(alice);
  if(mode==='text-first')await literal(bob,bobValue);else await editText(bob,replaceFlow(await savedText(bob),bobValue));
  await record(bob,'Bob flow');await operation(bob,'Deliver');
  await expect(flowRow(bob)).toHaveClass(/has-conflict/);await expect(pumpBlock(bob)).toHaveClass(/conflicted/);await expect(pumpBlock(bob).locator('.conflict-badge')).toHaveText('1');
  await openText(bob);await flowRow(bob).click();await expect(bob.locator('#proposal')).toBeVisible();await bob.locator('#proposal').getByRole('button',{name:'Show in source',exact:true}).click();
  await expect(source(bob)).toBeFocused();await expect.poll(()=>bob.evaluate(()=>window.getSelection()?.toString())).toBe(bobValue);await bob.keyboard.insertText('14');
  await expect(bob.locator('#proposal').getByLabel('Custom expression')).toHaveValue('14');await expect(flowValue(bob)).toHaveText(bobValue);await bob.getByRole('button',{name:'Cancel proposal'}).click();
  await addAttribute(bob,alertName);await expect(source(bob)).toContainText(alertName);await record(bob,'Add alert');const before=await source(bob).innerText();
  await openChanges(bob);await bob.locator('.history-item').filter({hasText:'Bob flow'}).click();await expect(bob.locator('#snapshot-dialog')).toBeVisible();await expect(bob.locator('#snapshot-source')).not.toContainText(alertName);
  const selectedRevision=new URL(bob.url()).searchParams.get('revision');expect(selectedRevision).toBeTruthy();await bob.reload();await expect(bob.locator('#snapshot-dialog')).toBeVisible();expect(new URL(bob.url()).searchParams.get('revision')).toBe(selectedRevision);
  await expect(bob.locator('#snapshot-source')).not.toContainText(alertName);await bob.getByRole('button',{name:'Close snapshot'}).click();await openText(bob);await expect.poll(()=>source(bob).innerText()).toBe(before);
  await bob.getByRole('button',{name:'Resolve flowRate in text',exact:true}).click();for(const text of ['Base value','Incoming accepted value','Local provisional value'])await expect(bob.locator('#proposal')).toContainText(text);
  const unmodified=await savedText(bob);await bob.locator('#proposal').getByRole('button',{name:'Use incoming',exact:true}).click();expect(await savedText(bob)).toBe(unmodified);await expect(flowValue(bob)).toHaveText(bobValue);
  const custom=bob.locator('#proposal').getByLabel('Custom expression');await custom.fill('14');await custom.press('Tab');await bob.keyboard.press('Enter');
  await expect(flowValue(bob)).toHaveText('14');await expect(source(bob)).toContainText(alertName);await expect(bob.locator('#notice')).toContainText('Rewritten/replayed');await expect(flowRow(bob)).not.toHaveClass(/has-conflict/);
  expect(await pumpBlock(bob).evaluate(el=>({left:(el as HTMLElement).style.left,top:(el as HTMLElement).style.top}))).toEqual(fixed);
  await operation(bob,'Deliver');await openText(alice);await expect(source(alice)).not.toContainText(alertName);await operation(alice,'Update');await expect(source(alice)).toContainText(alertName);await expect(flowValue(alice)).toHaveText('14');
  const bobUrl=bob.url();await bob.reload();await expect(bob.locator('#actor-bob')).toHaveAttribute('aria-pressed','true');expect(bob.url()).toBe(bobUrl);await expect(bob.locator('#source-pane')).toBeHidden();await openText(bob);await expect(source(bob)).toContainText(alertName);
  expect(await pumpBlock(bob).evaluate(el=>({left:(el as HTMLElement).style.left,top:(el as HTMLElement).style.top}))).toEqual(fixed);expect(errors).toEqual([]);await aliceContext.close();await bobContext.close();
 });
}
test('SysML syntax colors stay readable while editing the source',async({page})=>{
 await openActor(page);await openText(page);
 const text='// Syntax coloring example\n'+(await savedText(page)).replace('attribute flowRate : Real = 10;','attribute flowRate : Real = 10;\n            attribute label : String = "demo";');
 await editText(page,text);
 const keyword=source(page).locator('.sysml-keyword').filter({hasText:/^package$/}).first();
 await expect(keyword).toHaveCSS('color','rgb(35, 95, 164)');
 await expect(source(page).locator('.sysml-type').filter({hasText:/^Real$/}).first()).toHaveCSS('color','rgb(104, 66, 148)');
 await expect(source(page).locator('.sysml-literal').filter({hasText:/^10$/}).first()).toHaveCSS('color','rgb(138, 66, 28)');
 await expect(source(page).locator('.sysml-string').filter({hasText:/^"demo"$/})).toHaveCSS('color','rgb(37, 98, 60)');
 await expect(source(page).locator('.sysml-comment')).toHaveCSS('font-style','italic');
 expect(await savedText(page)).toBe(text);
});

test('invalid text persists and leaves a noticeable read-only graph',async({page})=>{
 await openActor(page);await editText(page,(await savedText(page))+'\npart def Broken {');await expect(page.locator('#graph-state')).toBeVisible();await expect(page.locator('#graph-state')).toContainText('Outdated diagram');await expect(pumpBlock(page)).toBeVisible();
 await flowRow(page).dblclick();await expect(page.getByRole('button',{name:'Apply literal'})).toBeDisabled();await page.keyboard.press('Escape');await page.reload();await openText(page);await expect(source(page)).toContainText('part def Broken {');await expect(page.locator('#graph-state')).toBeVisible();
});
test('diagram defaults, local editors and explicit source toggle work by keyboard',async({page})=>{
 await openActor(page);await expect(page.locator('#source-pane')).toBeHidden();await expect(page.locator('#properties')).toBeHidden();await expect(page.locator('#collaboration')).toBeHidden();await expect(page.locator('#diagnostics-dialog')).toBeHidden();await expect(page.locator('footer')).toHaveCount(0);await expect(page.locator('#graph-state')).toBeHidden();
 await flowRow(page).click();await expect(page.locator('#properties')).toBeHidden();await flowRow(page).press('Enter');await expect(page.getByLabel('Literal expression')).toBeFocused();await page.keyboard.press('Escape');await expect(page.locator('#properties')).toBeHidden();await expect(flowRow(page)).toBeFocused();
 await flowRow(page).dblclick();await page.locator('#model-title').click();await expect(page.locator('#properties')).toBeHidden();
 await pumpBlock(page).focus();await page.keyboard.press('Shift+F10');await expect(page.getByRole('menu',{name:/actions$/})).toBeVisible();await page.keyboard.press('ArrowDown');await page.keyboard.press('Escape');await expect(pumpBlock(page)).toBeFocused();
 await flowRow(page).click();await openText(page);await expect(source(page)).toBeFocused();await expect.poll(()=>page.evaluate(()=>window.getSelection()?.toString())).toBe((await flowValue(page).innerText()).trim());
 const lines=await source(page).locator('.cm-line').count();expect(lines).toBeGreaterThan(5);await page.getByRole('button',{name:'Close source',exact:true}).click();await expect(page.locator('#source-pane')).toBeHidden();await expect(page.getByRole('button',{name:'Text',exact:true})).toBeFocused();
 await openChanges(page);await expect(page.getByRole('button',{name:'Record change',exact:true})).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('#collaboration')).toBeHidden();
});
test('water tank renders three parts, six terminals and the complete port loop',async({page})=>{
 await openActor(page);await expect(page.locator('.block')).toHaveCount(3);
 for(const name of ['controller : Controller','pump : Pump','tank : Tank'])await expect(page.getByRole('heading',{name,exact:true})).toBeVisible();
 await expect(page.locator('.specialization')).toHaveCount(0);
 await expect(page.locator('.port-terminal')).toHaveCount(6);
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');
 await expect(page.locator('.connections path')).toHaveCount(3);
 await expect(pumpBlock(page)).toHaveCSS('background-color','rgb(255, 252, 233)');
 await expect(pumpBlock(page)).toHaveCSS('width','240px');
 for(const side of ['left','right'])await expect(page.locator('.terminal-'+side)).toHaveCount(3);
 await expect(page.locator('.terminal-filled')).toHaveCount(6);
 await expect(page.locator('.routing-status')).toBeHidden();
 await expectClearRoutes(page);
 const state=await (await page.request.get('/api/workspaces/'+new URL(page.url()).searchParams.get('workspace'))).json();
 expect(state.analysis.connections).toHaveLength(3);
 for(const connection of state.analysis.connections){
  const path=page.locator(`.connections path[data-connection-id="${connection.id}"]`);
  await expect(path).toBeVisible();
  const endpoints=await path.evaluate((node,connection)=>{
   const path=node as SVGPathElement,matrix=path.getScreenCTM()!;
   const start=path.getPointAtLength(0).matrixTransform(matrix),end=path.getPointAtLength(path.getTotalLength()).matrixTransform(matrix);
   return [[connection.sourceElementId,connection.sourcePortId,start],[connection.targetElementId,connection.targetPortId,end]].map(([owner,id,point])=>{
    const terminal=document.querySelector(`[data-element-id="${owner}"] [data-port-id="${id}"]`)!,rect=terminal.getBoundingClientRect(),p=point as DOMPoint;
    return Math.hypot(p.x-rect.x-rect.width/2,p.y-rect.y-rect.height/2);
   });
  },connection);
  for(const distance of endpoints)expect(distance).toBeLessThan(2);
  const output=page.locator(`[data-port-id="${connection.sourcePortId}"]`),input=page.locator(`[data-port-id="${connection.targetPortId}"]`);
  expect(await output.evaluate(n=>getComputedStyle(n).backgroundColor)).toBe(await input.evaluate(n=>getComputedStyle(n).backgroundColor));
  expect(await path.evaluate(n=>getComputedStyle(n).stroke)).toBe(await output.evaluate(n=>getComputedStyle(n).backgroundColor));
 }
 await page.getByRole('button',{name:'Inspect port waterOut',exact:true}).dblclick();
 await expect(page.locator('#definition-dialog')).toContainText('part def Pump');
 await expect(page.locator('#definition-dialog')).toContainText('waterOut');
});
test('growing feature lists reroute measured blocks without moving their fixed slots',async({page})=>{
 await openActor(page);await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');
 const positions=await page.locator('.block').evaluateAll(nodes=>nodes.map(n=>[(n as HTMLElement).style.left,(n as HTMLElement).style.top]));
 const before=await page.locator('.connections path').evaluateAll(paths=>paths.map(p=>p.getAttribute('d')));
 await addAttribute(page,'alertEnabled');await expect(pumpBlock(page)).toContainText('alertEnabled');
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');await expectClearRoutes(page);
 expect(await page.locator('.connections path').evaluateAll(paths=>paths.map(p=>p.getAttribute('d')))).not.toEqual(before);
 const text=(await savedText(page)).replace('attribute flowRate : Real = 10;', 'attribute flowRate : Real = 10;\n'+Array.from({length:7},(_,i)=>`            attribute option${i} : Boolean = true;`).join('\n'));
 await editText(page,text);await expect(pumpBlock(page).getByRole('button',{name:'Show all features',exact:true})).toBeVisible();
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');await expectClearRoutes(page);
 const height=await pumpBlock(page).evaluate(n=>n.getBoundingClientRect().height);
 await pumpBlock(page).getByRole('button',{name:'Show all features',exact:true}).click();
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');await expectClearRoutes(page);
 expect(await pumpBlock(page).evaluate(n=>n.getBoundingClientRect().height)).toBeGreaterThan(height);
 expect(await page.locator('.block').evaluateAll(nodes=>nodes.map(n=>[(n as HTMLElement).style.left,(n as HTMLElement).style.top]))).toEqual(positions);
 await page.getByRole('button',{name:'Zoom in',exact:true}).click();await expectClearRoutes(page);
 await page.getByRole('button',{name:'Fit',exact:true}).click();await expectClearRoutes(page);
});

test('trapped ports report failures instead of drawing through blocks and recover on a clear layout',async({page})=>{
 await openActor(page);const style=await page.addStyleTag({content:'.block{left:120px!important;top:120px!important}'});
 await editText(page,replaceFlow(await savedText(page),'12'));await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','failed');
 await expect(page.locator('.connections path')).toHaveCount(0);await expect(page.locator('.port-terminal.unroutable')).toHaveCount(6);
 await expect(page.locator('.routing-status')).toContainText('3 connections could not be routed');
 await style.evaluate(n=>(n as HTMLStyleElement).remove());await editText(page,replaceFlow(await savedText(page),'13'));
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');await expect(page.locator('.routing-status')).toBeHidden();
 await expect(page.locator('.port-terminal.unroutable')).toHaveCount(0);await expectClearRoutes(page);
});

test('failure markers stay on their measured terminals when zoom changes during routing',async({page})=>{
 await page.addInitScript(()=>{
  const NativeWorker=window.Worker,control=window as Window&{releaseRouting?:()=>void};
  window.Worker=class extends NativeWorker{
   constructor(url:string|URL,options?:WorkerOptions){
    super(url,options);let receiver:((this:Worker,event:MessageEvent)=>unknown)|null=null;
    Object.defineProperty(this,'onmessage',{get:()=>receiver,set:value=>{receiver=value;}});
    this.addEventListener('message',event=>{
     if(event.data.result?.routes){const data={...event.data,result:{routes:event.data.result.routes.map((r:{id:string})=>({id:r.id,points:[]}))}};control.releaseRouting=()=>receiver?.call(this,new MessageEvent('message',{data}));}
     else receiver?.call(this,event);
    });
   }
  };
 });
 await openActor(page);await expect.poll(()=>page.evaluate(()=>!!(window as Window&{releaseRouting?:()=>void}).releaseRouting)).toBe(true);
 await page.getByRole('button',{name:'Zoom in',exact:true}).click();await page.getByRole('button',{name:'Zoom in',exact:true}).click();
 await page.evaluate(()=>(window as Window&{releaseRouting?:()=>void}).releaseRouting!());
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','failed');
 const distances=await page.locator('.route-failure').evaluateAll(lines=>lines.map(node=>{
  const line=node as SVGLineElement,center=new DOMPoint((line.x1.baseVal.value+line.x2.baseVal.value)/2,(line.y1.baseVal.value+line.y2.baseVal.value)/2).matrixTransform(line.getScreenCTM()!);
  return Math.min(...Array.from(document.querySelectorAll('.port-terminal'),terminal=>{const r=terminal.getBoundingClientRect();return Math.hypot(center.x-r.x-r.width/2,center.y-r.y-r.height/2);}));
 }));
 expect(distances).toHaveLength(12);for(const distance of distances)expect(distance).toBeLessThan(.1);
});

test('a named straight connection keeps its label between blocks',async({page})=>{
 await openActor(page);
 const text=(await savedText(page)).replace('part controller : Controller;','part controller : Controller { attribute threshold : Real = 30; }').replace('connect controller.pumpCommandOut','connection commandLink connect controller.pumpCommandOut');
 await editText(page,text);await expect(page.locator('.connections text')).toHaveText('commandLink');
 await expect(page.locator('#diagram')).toHaveAttribute('data-routing-state','ready');
 const label=await page.locator('.connections text').boundingBox(),controller=await page.getByRole('article',{name:'controller block',exact:true}).boundingBox(),pump=await pumpBlock(page).boundingBox();
 expect(label!.x).toBeGreaterThan(controller!.x+controller!.width);expect(label!.x+label!.width).toBeLessThan(pump!.x);
 await expectClearRoutes(page);
});

test('connected usages render real terminals, subtype and inherited definition on demand',async({page})=>{
 await openActor(page);await editText(page,readFileSync(new URL('../../fixtures/connected-pump.sysml',import.meta.url),'utf8'));
 await expect(page.locator('.block')).toHaveCount(2);await expect(pumpBlock(page).getByRole('button',{name:':> Pump',exact:true})).toBeVisible();
 await expect(pumpBlock(page)).toContainText('speed : Real');await expect(pumpBlock(page)).toContainText('from VariableSpeedPump');await expect(pumpBlock(page).getByRole('button',{name:'Inspect port outlet',exact:true})).toHaveAttribute('title','outlet : FluidPort · from Pump');
 const paths=page.locator('.connections path');await expect(paths).toHaveCount(1);await expect(paths.first()).toHaveAttribute('d',/^M [-\d.]+ [-\d.]+(?: L [-\d.]+ [-\d.]+)+$/);
 const endpoints=await page.evaluate(()=>{const path=document.querySelector<SVGPathElement>('.connections path')!,matrix=path.getScreenCTM()!;const start=path.getPointAtLength(0).matrixTransform(matrix),end=path.getPointAtLength(path.getTotalLength()).matrixTransform(matrix);return [...document.querySelectorAll('.port-terminal')].map(n=>{const r=n.getBoundingClientRect();return Math.min(Math.hypot(start.x-r.x-r.width/2,start.y-r.y-r.height/2),Math.hypot(end.x-r.x-r.width/2,end.y-r.y-r.height/2));});});for(const distance of endpoints)expect(distance).toBeLessThan(2);
 await pumpBlock(page).getByRole('button',{name:'Inspect speed',exact:true}).dblclick();await expect(page.locator('#properties')).toContainText('Inherited from VariableSpeedPump');await expect(page.getByLabel('Literal expression')).toHaveCount(0);await page.getByRole('button',{name:'Inspect definition',exact:true}).click();await expect(page.locator('#definition-dialog')).toContainText('speed');await expect(page.locator('#definition-dialog')).toContainText('part def VariableSpeedPump');await page.keyboard.press('Escape');
 await pumpBlock(page).getByRole('button',{name:':> Pump',exact:true}).click();await expect(page.locator('#definition-dialog')).toContainText('outlet');await expect(page.locator('#definition-dialog')).toContainText('part def Pump');await page.keyboard.press('Escape');
});
test('independent tabs, accessible controls and unclipped responsive cards',async({context,page},testInfo)=>{
 await openActor(page,'Alice');const second=await context.newPage();await openActor(second,'Bob');expect(new URL(page.url()).searchParams.get('workspace')).not.toBe(new URL(second.url()).searchParams.get('workspace'));await page.reload();await expect(page.locator('#actor-alice')).toHaveAttribute('aria-pressed','true');
 const accessibility=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);
 for(const width of [320,768,1024,1440]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);for(const block of await page.locator('.block').all()){const rect=await block.boundingBox();expect(rect?.x).toBeGreaterThanOrEqual(0);expect((rect?.x??0)+(rect?.width??0)).toBeLessThanOrEqual(width);expect(await block.evaluate(el=>el.scrollHeight<=el.clientHeight+2)).toBe(true);}await page.screenshot({path:testInfo.outputPath('layout-'+width+'.png'),fullPage:true});}
});
