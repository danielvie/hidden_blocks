const developmentOrigins=new Set(['http://127.0.0.1:5173','http://localhost:5173']);
/** Only the exact local Vite origin is translated; foreign Origins reach the API unchanged. */
export function forwardedOrigin(origin:string|undefined,host:string|undefined):string|undefined {
 if(origin&&developmentOrigins.has(origin)&&new URL(origin).host===host)return 'http://127.0.0.1:4317';
 return origin;
}
