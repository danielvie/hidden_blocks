export function el<K extends keyof HTMLElementTagNameMap>(tag:K,className='',text=''):HTMLElementTagNameMap[K] {
 const node=document.createElement(tag);node.className=className;if(text)node.textContent=text;return node;
}
export function button(text:string,action:()=>void,className=''):HTMLButtonElement {
 const node=el('button',className,text);node.type='button';node.onclick=action;return node;
}
export function field(label:string,input:HTMLInputElement|HTMLSelectElement|HTMLTextAreaElement):HTMLLabelElement {
 const node=el('label','field');node.append(el('span','',label),input);return node;
}
export function input(value='',name=''):HTMLInputElement {
 const node=el('input');node.value=value;node.name=name;node.autocomplete='off';return node;
}
export function select(values:string[]):HTMLSelectElement {const node=el('select');for(const value of values)node.append(new Option(value,value));return node;}
