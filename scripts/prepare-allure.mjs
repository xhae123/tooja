import fs from 'node:fs';
import path from 'node:path';
const target='reports/raw/allure-prepared';fs.rmSync(target,{recursive:true,force:true});fs.mkdirSync(target,{recursive:true});let count=0;
const jvmOnly=process.argv.includes('--jvm-only');
for(const dir of (jvmOnly?['build/allure-results']:['build/allure-results','allure-results']))for(const file of fs.readdirSync(dir)){
 const source=path.join(dir,file),destination=path.join(target,file);
 if(!file.endsWith('-result.json')){fs.copyFileSync(source,destination);continue;}
 const result=JSON.parse(fs.readFileSync(source,'utf8'));const labels=result.labels||[];
 const hierarchy=dir==='allure-results'?{parentSuite:'E2E 브라우저 테스트',suite:labels.find(l=>l.name==='feature')?.value||'공통 흐름'}:{parentSuite:result.fullName.includes('PolicyTest')?'단위 테스트':'통합 테스트',suite:labels.filter(l=>l.name==='suite').at(-1)?.value||'서버 규칙'};
 // Adapters and custom labels can both add a suite. Normalize display grouping only;
 // leave status, timings, steps, assertions and attachments unchanged in the copied results.
 result.labels=labels.filter(l=>!['parentSuite','suite','subSuite'].includes(l.name)).concat(Object.entries(hierarchy).map(([name,value])=>({name,value})));
 fs.writeFileSync(destination,JSON.stringify(result));count++;
}
if(jvmOnly){
 fs.writeFileSync(target+'/environment.properties','Backend=Kotlin / Spring Boot / Java 17\nDatabase=SQLite (WAL)\nTests=Unit and integration only; E2E excluded\nCommit='+ (process.env.GITHUB_SHA||'local') +'\n');
 if(process.env.GITHUB_RUN_NUMBER) fs.writeFileSync(target+'/executor.json',JSON.stringify({name:'GitHub Actions',type:'github',buildOrder:Number(process.env.GITHUB_RUN_NUMBER),buildName:'main #'+process.env.GITHUB_RUN_NUMBER,buildUrl:process.env.GITHUB_SERVER_URL+'/'+process.env.GITHUB_REPOSITORY+'/actions/runs/'+process.env.GITHUB_RUN_ID}));
}else fs.writeFileSync(target+'/environment.properties','Backend=Kotlin 2.1.21 / Spring Boot 3.5.3 / Java 17\nDatabase=SQLite (WAL)\nDeployment=Docker Compose\nTest_URL=http://localhost:18081\nBrowser=Chromium\nRetries=0\nFixtures=Isolated test-only sample data\n');console.log('Prepared report grouping for',count,'executed tests; raw results preserved');
