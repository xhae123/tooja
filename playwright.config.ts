import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, retries: 0, timeout: 45000,
  expect: { timeout: 8000 },
  reporter: [['list'],['html',{outputFolder:'reports/playwright',open:'never'}],['json',{outputFile:'reports/raw/e2e.json'}],['allure-playwright',{resultsDir:'allure-results',detail:true,suiteTitle:false}]],
  use: { baseURL: process.env.QA_URL || 'http://localhost:18081', headless:true,
    launchOptions: { executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' },
    viewport: {width:390,height:844}, trace:'on', screenshot:'only-on-failure', actionTimeout:10000 },
});
