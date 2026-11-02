import {defineConfig} from '@playwright/test';
const baseURL=process.env.E2E_BASE_URL;
if(!baseURL)throw new Error('Set E2E_BASE_URL to an isolated test server. Tests create and publish model changes.');
if(new URL(baseURL).port==='4317')throw new Error('Port 4317 may contain a running personal workspace. Use a separate ephemeral test server.');
export default defineConfig({
 testDir:'./e2e',fullyParallel:false,workers:1,timeout:120000,
 use:{baseURL,trace:'retain-on-failure',screenshot:'only-on-failure',viewport:{width:1440,height:1000}},
});
