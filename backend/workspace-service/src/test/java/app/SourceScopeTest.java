package app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SourceScopeTest{
 @Test void commentsAndWhitespaceAreIgnoredWithoutErasingQuotedContent(){
  assertEquals(SourceScope.tokens("part Pump { // comment\n }"),SourceScope.tokens("part /* note */ Pump{}"));
  assertNotEquals(SourceScope.tokens("partPump{}"),SourceScope.tokens("part Pump{}"));
  assertNotEquals(SourceScope.tokens("\"//first\""),SourceScope.tokens("\"//second\""));
  assertEquals(java.util.List.of("\"/* text */\""),SourceScope.tokens("\"/* text */\""));
 }
}
