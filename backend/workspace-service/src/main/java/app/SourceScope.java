package app;
import static app.ModelContracts.*;
import java.util.*;

/** Only a residual-source scope guard. The official parser still owns SysML syntax and semantics. */
public final class SourceScope {
 private SourceScope(){}
 public static void requireOnlyTriviaOrLiteralSpelling(String expected,Analysis expectedModel,String authored,Analysis authoredModel){
  if(!tokens(mask(expected,expectedModel)).equals(tokens(mask(authored,authoredModel))))
   throw new IllegalArgumentException("Only scalar values, scalar additions, comments and formatting can be recorded in this version. Original source is preserved.");
 }
 private static String mask(String source,Analysis model){
  var replacements=new ArrayList<Attribute>();
  model.elements().forEach(e->e.attributes().stream().filter(a->a.editable()&&a.valueRange()!=null).forEach(replacements::add));
  replacements.sort(Comparator.comparingInt((Attribute a)->a.valueRange().start()).reversed());
  String result=source;
  for(var a:replacements)result=result.substring(0,a.valueRange().start())+"__scalar_"+a.id().replace("-","")+"__"+result.substring(a.valueRange().end());
  return result;
 }
 static List<String> tokens(String source){
  var result=new ArrayList<String>();int i=0;
  while(i<source.length()){
   char c=source.charAt(i);
   if(Character.isWhitespace(c)){i++;continue;}
   if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='/'){int end=source.indexOf('\n',i+2);i=end<0?source.length():end+1;continue;}
   if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='*'){int end=source.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Unclosed comment");i=end+2;continue;}
   int start=i++;
   if(c=='"'||c=='\''){
    boolean closed=false;
    while(i<source.length()){
     char next=source.charAt(i++);
     if(next=='\\'&&i<source.length()){i++;continue;}
     if(next==c){closed=true;break;}
    }
    if(!closed)throw new IllegalArgumentException("Unclosed quoted token");
   }else if(Character.isJavaIdentifierPart(c)){
    while(i<source.length()&&Character.isJavaIdentifierPart(source.charAt(i)))i++;
   }
   result.add(source.substring(start,i));
  }
  return result;
 }
}
