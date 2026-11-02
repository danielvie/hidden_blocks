import {describe,it,expect} from 'vitest';
import {forwardedOrigin} from './dev-proxy';
describe('development same-origin proxy',()=>{
 it('translates only exact same-origin loopback Vite requests',()=>{
  expect(forwardedOrigin('http://127.0.0.1:5173','127.0.0.1:5173')).toBe('http://127.0.0.1:4317');
  expect(forwardedOrigin('http://localhost:5173','localhost:5173')).toBe('http://127.0.0.1:4317');
 });
 it('does not turn foreign or missing origins into trusted requests',()=>{
  for(const origin of ['https://evil.example','null','http://127.0.0.1:5174','http://localhost.evil.example:5173',undefined])expect(forwardedOrigin(origin,'127.0.0.1:5173')).toBe(origin);
  expect(forwardedOrigin('http://127.0.0.1:5173','evil.example')).toBe('http://127.0.0.1:5173');
 });
});
