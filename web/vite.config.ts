import {defineConfig} from 'vitest/config';
import {forwardedOrigin} from './src/dev-proxy.ts';
// https://vite.dev/config/server-options.html#server-proxy
export default defineConfig({
 server:{
  host:'127.0.0.1',port:5173,strictPort:true,
  proxy:{'/api':{
   target:'http://127.0.0.1:4317',changeOrigin:true,
   configure(proxy){
    proxy.on('proxyReq',(proxyRequest,request)=>{
     const origin=forwardedOrigin(request.headers.origin,request.headers.host);
     if(origin)proxyRequest.setHeader('Origin',origin);
    });
   },
  }},
 },
 test:{include:['src/**/*.test.ts']},
});
