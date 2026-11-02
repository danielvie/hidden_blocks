import init, { project, route } from './wasm-pkg/sysml_diagram';
import wasmUrl from './wasm-pkg/sysml_diagram_bg.wasm?url';
let positions:Record<string,{x:number;y:number}>={};
// https://rustwasm.github.io/docs/wasm-bindgen/examples/without-a-bundler.html
const ready=init({module_or_path:wasmUrl});
self.onmessage=async(event:MessageEvent)=>{
 const {id,elements,baseline,conflicts,reset,scopeKey,geometry}=event.data;
 try {
  await ready;
  if(geometry){self.postMessage({id,result:JSON.parse(route(JSON.stringify(geometry)))});return;}
  if(reset) positions={};
  const result=JSON.parse(project(JSON.stringify({elements,baseline,conflicts,positions,scopeKey})));
  positions=result.positions;
  self.postMessage({id,result});
 } catch(error) { self.postMessage({id,error:'Rust WASM initialization/projection/routing failed. Rebuild the browser assets and reload. If it persists, check the service model contracts. Details: '+String(error)}); }
};
