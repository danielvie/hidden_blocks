package app;

import com.sun.net.httpserver.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class LocalServer implements AutoCloseable {
 private final HttpServer server;private final WorkspaceService service;private final Path web;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
 public LocalServer(WorkspaceService service,Path web,int port) throws IOException {
  this.service=service;this.web=web.toAbsolutePath().normalize();
  server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),0);
  server.createContext("/",this::handle);server.setExecutor(executor);
 }
 public void start(){server.start();}
 public int port(){return server.getAddress().getPort();}
 private void handle(HttpExchange exchange) throws IOException {
  try{
   int port=exchange.getLocalAddress().getPort();String host=exchange.getRequestHeaders().getFirst("Host");
   if(!Set.of("127.0.0.1:"+port,"localhost:"+port).contains(host==null?"":host))throw new Problem(403,"forbidden","Unexpected Host header");
   String origin=exchange.getRequestHeaders().getFirst("Origin");
   if(origin!=null&&!Set.of("http://127.0.0.1:"+port,"http://localhost:"+port).contains(origin))throw new Problem(403,"forbidden","Cross-origin requests are not allowed");
   if("cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site")))throw new Problem(403,"forbidden","Cross-site requests are not allowed");
   String path=exchange.getRequestURI().getPath();
   if(path.contains("\\")||path.indexOf('\0')>=0)throw new Problem(400,"invalid","Invalid path");
   if(!path.startsWith("/api/")){staticFile(exchange,path);return;}
   if(exchange.getRequestMethod().equals("GET")){
    if(path.equals("/api/health")){json(exchange,200,Map.of("status","ok"));return;}
    if(path.equals("/api/project")){json(exchange,200,service.project());return;}
    if(path.equals("/api/demo")){json(exchange,200,service.demo());return;}
    if(path.equals("/api/demo/scenario")){json(exchange,200,service.scenario());return;}
    String[] parts=path.split("/");
    if(parts.length==4&&parts[2].equals("workspaces")){json(exchange,200,service.state(parts[3]));return;}
    if(parts.length==4&&parts[2].equals("drafts")){json(exchange,200,service.draft(parts[3]));return;}
    throw new Problem(404,"missing","Route not found");
   }
   if(!exchange.getRequestMethod().equals("POST"))throw new Problem(405,"invalid","Only GET and POST are supported");
   String content=exchange.getRequestHeaders().getFirst("Content-Type");
   if(content==null||!content.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new Problem(415,"invalid","Use application/json");
   byte[] bytes=exchange.getRequestBody().readNBytes(1_048_577);
   if(bytes.length>1_048_576)throw new Problem(413,"invalid","Request exceeds the 1 MiB demo limit");
   JsonObject request;
   try{request=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
   catch(Exception e){throw new Problem(400,"invalid","Expected a JSON object");}
   if(path.equals("/api/workspaces")){json(exchange,200,service.create(WorkspaceService.string(request,"actor")));return;}
   if(path.equals("/api/demo/reset")){json(exchange,200,service.resetDemo(WorkspaceService.string(request,"expectedGeneration"),WorkspaceService.string(request,"operationId")));return;}
   if(path.equals("/api/demo/scenario/step")){json(exchange,200,service.scenarioStep(WorkspaceService.string(request,"expectedGeneration"),WorkspaceService.number(request,"step"),WorkspaceService.string(request,"actor")));return;}
   String[] parts=path.split("/");
   if(parts.length==5&&parts[2].equals("workspaces")){json(exchange,200,service.execute(parts[3],parts[4],request));return;}
   throw new Problem(404,"missing","Route not found");
  }catch(Problem e){json(exchange,e.http,Map.of("status",e.status,"message",e.getMessage()));}
   catch(IllegalArgumentException e){json(exchange,400,Map.of("status","invalid","message",Objects.toString(e.getMessage(),"Invalid request")));}
   catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName()+": "+e.getMessage());json(exchange,500,Map.of("status","error","message","Local operation failed; saved work is preserved. Check the backend log."));}
  finally{exchange.close();}
 }
 private void staticFile(HttpExchange exchange,String route) throws IOException {
  if(!exchange.getRequestMethod().equals("GET")&&!exchange.getRequestMethod().equals("HEAD"))throw new Problem(405,"invalid","Static content is read-only");
  Path path=web.resolve(route.equals("/")?"index.html":route.substring(1)).normalize();
  if(!path.startsWith(web)||!Files.isRegularFile(path))throw new Problem(404,"missing","Build the web application before opening this URL");
  if(!path.toRealPath().startsWith(web.toRealPath()))throw new Problem(403,"forbidden","Static path escapes the web directory");
  String name=path.getFileName().toString(),type=name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".wasm")?"application/wasm":name.endsWith(".svg")?"image/svg+xml":name.endsWith(".html")?"text/html":"application/octet-stream";
  exchange.getResponseHeaders().set("Content-Type",type);exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
  exchange.getResponseHeaders().set("Cache-Control","no-store");
  if(exchange.getRequestMethod().equals("HEAD")){exchange.sendResponseHeaders(200,-1);return;}
  byte[] body=Files.readAllBytes(path);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
 }
 private static void json(HttpExchange exchange,int status,Object value) throws IOException {
  byte[] bytes=WorkspaceService.JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
  exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
 }
 @Override public void close() throws Exception {server.stop(1);executor.close();service.close();}
 public static void main(String[] args) throws Exception {
  var options=new HashMap<String,String>();
  for(int i=0;i<args.length;i+=2){if(i+1>=args.length)throw new IllegalArgumentException("Missing option value");options.put(args[i],args[i+1]);}
  Path root=Path.of(System.getProperty("sysml.root", ".")).toAbsolutePath().normalize();
  Path data=Path.of(options.getOrDefault("--data",root.resolve(".local/demo").toString()));
  Path library=Path.of(options.getOrDefault("--libraries",System.getProperty("sysml.pilot.library")));
  var service=new WorkspaceService(data,library,Path.of(options.getOrDefault("--fixture",root.resolve("fixtures/water-tank.sysml").toString())));
  var server=new LocalServer(service,Path.of(options.getOrDefault("--web",root.resolve("web/dist").toString())),Integer.parseInt(options.getOrDefault("--port","4317")));
  Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.close();}catch(Exception e){System.err.println("Shutdown: "+e.getMessage());}}));
  server.start();System.out.println("SysML collaboration: http://127.0.0.1:"+server.port());System.out.println("DATA="+data.toAbsolutePath());System.out.println("PID="+ProcessHandle.current().pid());
 }
}
