import {HighlightStyle,StreamLanguage,syntaxHighlighting} from '@codemirror/language';
import {tags} from '@lezer/highlight';
import {EditorView} from '@codemirror/view';

// Display-only tokens, not a SysML validator. The official local parser remains authoritative.
// StreamLanguage's token API: https://github.com/codemirror/language/blob/main/src/stream-parser.ts
const keywords=new Set(`package public private protected import all part def attribute port item connection connect to
 interface flow in out inout ref redefines specializes subsets default abstract variation variant individual
 occurrence action perform accept send assign then first merge decide if else for while loop until return
 state exhibit transition entry exit do terminate constraint assert require requirement satisfy verify
 analysis case use include actor objective subject view viewpoint render rendering expose metadata about
 alias for as enum enumeration constant readonly derived ordered nonunique end not and or xor implies
 select collect filter null new of at by from crosses through via succession binding bind`.split(/\s+/));
const primitives=new Set(['Boolean','Integer','Real','String','Natural','Rational','Complex','Number']);
type NameRole='typeName'|'variableName.definition'|'propertyName.definition'|null;
interface State {blockComment:boolean;quote:'"'|"'"|null;nameRole:NameRole}
export const sysmlLanguage=StreamLanguage.define<State>({
 name:'SysML v2',
 startState:()=>({blockComment:false,quote:null,nameRole:null}),
 token(stream,state){
  if(state.blockComment){if(stream.skipTo('*/')){stream.match('*/');state.blockComment=false;}else stream.skipToEnd();return 'comment';}
  if(stream.eatSpace())return null;
  if(!state.quote){
   if(stream.match('//')){stream.skipToEnd();return 'comment';}
   if(stream.match('/*')){state.blockComment=true;return 'comment';}
   if(stream.peek()==='"'||stream.peek()==="'")state.quote=stream.next() as State['quote'];
   else if(stream.match(/(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/))return 'number';
   else if(stream.match(/[A-Za-z_$][\w$]*/)){
    const word=stream.current();
    if(word==='true'||word==='false')return 'bool';
    if(keywords.has(word)){
     if(word==='def')state.nameRole='typeName';
     else if(word==='attribute')state.nameRole='propertyName.definition';
     else if(['part','port','item','package','connection','alias'].includes(word))state.nameRole='variableName.definition';
     else state.nameRole=null;
     return 'keyword';
    }
    const role=state.nameRole;state.nameRole=null;
    return role??(primitives.has(word)||/^[A-Z]/.test(word)?'typeName':'variableName');
   }else if(stream.match(/:>>|:>|::>|::|:|:=|=>|->|==|!=|<=|>=|[=+\-*\/<>~|&^!?]/)){
    const operator=stream.current();if([':','::',':>','::>'].includes(operator))state.nameRole='typeName';else state.nameRole=null;
    return 'operator';
   }else{state.nameRole=null;stream.next();return 'punctuation';}
  }
  const quote=state.quote;
  while(!stream.eol()){
   const character=stream.next();
   if(character==='\\')stream.next();
   else if(character===quote){state.quote=null;break;}
  }
  if(quote==='"')return 'string';
  const role=state.nameRole;state.nameRole=null;return role??'variableName';
 },
 languageData:{commentTokens:{line:'//',block:{open:'/*',close:'*/'}}},
});
export const sysmlHighlightStyle=HighlightStyle.define([
 {tag:tags.keyword,class:'sysml-keyword'},
 {tag:tags.typeName,class:'sysml-type'},
 {tag:[tags.number,tags.bool],class:'sysml-literal'},
 {tag:tags.string,class:'sysml-string'},
 {tag:tags.comment,class:'sysml-comment'},
 {tag:tags.operator,class:'sysml-operator'},
 {tag:[tags.definition(tags.variableName),tags.definition(tags.propertyName)],class:'sysml-definition'},
]);
export const sysmlHighlighting=[sysmlLanguage,syntaxHighlighting(sysmlHighlightStyle),EditorView.baseTheme({
 '.sysml-keyword':{color:'#235fa4'},
 '.sysml-type':{color:'#684294'},
 '.sysml-literal':{color:'#8a421c'},
 '.sysml-string':{color:'#25623c'},
 '.sysml-comment':{color:'#64717e',fontStyle:'italic'},
 '.sysml-operator':{color:'#52616f'},
 '.sysml-definition':{color:'#254654'},
})];
