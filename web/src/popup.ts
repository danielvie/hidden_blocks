let active:HTMLElement|null=null;
let restore:HTMLElement|null=null;
export function closePopup(focus=true){
 if(!active)return;
 active.hidden=true;active=null;
 if(focus&&restore?.isConnected)restore.focus();
 restore=null;
}
/** Nonmodal local editors keep the diagram available. Esc/outside restores the invoking control. */
export function openPopup(node:HTMLElement,anchor:HTMLElement|null){
 closePopup(false);active=node;restore=anchor??document.activeElement as HTMLElement;node.hidden=false;
 const rect=anchor?.getBoundingClientRect();
 const width=node.offsetWidth,height=node.offsetHeight;
 let left=rect?rect.right+16:innerWidth-width-16;
 if(left+width>innerWidth-12)left=rect?rect.left-width-16:12;
 node.style.left=Math.max(12,Math.min(left,innerWidth-width-12))+'px';
 node.style.top=Math.max(64,Math.min(rect?.top??72,innerHeight-height-12))+'px';
 (node.querySelector<HTMLElement>('input,select,button,[tabindex="0"]')??node).focus();
}
document.addEventListener('pointerdown',e=>{if(active&&!active.contains(e.target as Node))closePopup(false);});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&active){e.preventDefault();e.stopPropagation();closePopup();}},true);
window.addEventListener('resize',()=>closePopup(false));
