import {test,expect} from '@playwright/test';
import {given,login,invest,capture,direct,evidence} from './helpers';

test('A02-05 · 관리자 중지 후 투자자 화면 차단, 재개 후 투자 성공',async({page})=>{
  await given(page,'A02','전체 투자 중지·재개');
  await login(page);await login(page,'admin');
  await test.step('Given · 관리자 현황은 투자 실행 중',async()=>{await expect(page.locator('#investment-status')).toHaveText('투자 실행 중');});
  await test.step('When · 관리자가 투자 중지 버튼을 누른다',async()=>{await page.locator('#investment-toggle').click();await expect(page.locator('#investment-status')).toHaveText('투자 중지 중');});
  await capture(page,'관리자 중지 성공',{'6':'투자 중지 중 · 재개 버튼 표시'});
  await test.step('Then · 투자자 소개는 볼 수 있지만 금액과 확정 버튼은 숨긴다',async()=>{await page.goto('/invest/teams/2');await expect(page.locator('#blocked')).toContainText('투자가 중지');await expect(page.locator('#amounts')).toBeHidden();await expect(page.locator('#next')).toBeHidden();});
  await capture(page,'투자자 중지 안내',{'2':'소개 조회 가능','3':'잔액 100만원 유지','5':'금액 선택 차단','7':'확정 차단'});
  await test.step('When · 관리자가 투자 재개 버튼을 누른다',async()=>{await page.goto('/admin');await expect(page.locator('#investment-toggle')).toHaveText('투자 재개');await page.locator('#investment-toggle').click();await expect(page.locator('#investment-status')).toHaveText('투자 실행 중');});
  await invest(page,2,100000);await expect(page.locator('#success-balance')).toContainText('900,000원');await capture(page,'재개 후 10만원 투자 성공');
});

test('P03-M-11 · 확인 창을 연 뒤 중지하면 서버가 확정을 거절한다',async({page})=>{
  await given(page,'P03','열린 확인 창과 관리자 중지 경합');await login(page);await login(page,'admin');await page.goto('/invest/teams/2');
  await test.step('Given · 70만원을 선택해 확인 창을 연다',async()=>{await page.locator('[data-amount="700000"]').click();await page.locator('#next').click();await expect(page.locator('#confirm')).toBeVisible();});
  await test.step('When · 다른 관리자 요청으로 접수를 중지한 뒤 기존 확인 창에서 확정',async()=>{const r=await direct(page,'/admin/investment-status','PATCH',{status:'PAUSED'});expect(r.status).toBe(200);await page.locator('#confirm-send').click();});
  await test.step('Then · 창을 닫고 중지 안내, 차감과 거래 없음',async()=>{await expect(page.locator('#confirm')).not.toBeVisible();await expect(page.locator('#blocked')).toContainText('투자가 중지');await expect(page.locator('#error')).toContainText('투자가 중지');const me=await direct(page,'/investor/me');expect(me.data.balance).toBe(1000000);const history=await direct(page,'/investor/investments');expect(history.data.count).toBe(0);await evidence({balance:me.data.balance,trades:history.data.count});});
  await capture(page,'확정 직전 중지를 서버가 차단');
});

test('P03-06 · 중지 거절 키는 재개 후 사용하고 성공 영수증은 중지 중 복구한다',async({page})=>{
  await given(page,'P03','중지·재개와 멱등 요청 복구');await login(page);await login(page,'admin');const key='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  await test.step('Given · 관리자 중지 상태',async()=>{expect((await direct(page,'/admin/investment-status','PATCH',{status:'PAUSED'})).status).toBe(200);});
  await test.step('When · 중지 중 투자 후 재개해 같은 키로 투자',async()=>{const blocked=await direct(page,'/investor/investments','POST',{teamId:2,amount:700000},key);expect(blocked.status).toBe(409);expect(blocked.data.error.code).toBe('INVESTMENT_PAUSED');await direct(page,'/admin/investment-status','PATCH',{status:'RUNNING'});const receipt=await direct(page,'/investor/investments','POST',{teamId:2,amount:700000},key);expect(receipt.status).toBe(201);await direct(page,'/admin/investment-status','PATCH',{status:'PAUSED'});const replay=await direct(page,'/investor/investments','POST',{teamId:2,amount:700000},key);expect(replay.status).toBe(201);expect(replay.data).toEqual(receipt.data);});
  await test.step('Then · 70만원 한 번만 차감하고 거래 한 건',async()=>{const history=await direct(page,'/investor/investments');expect(history.data.count).toBe(1);expect(history.data.investor.balance).toBe(300000);await evidence({count:history.data.count,balance:history.data.investor.balance});});
  await page.goto('/invest/history');await expect(page.locator('#history-count')).toContainText('1건');await capture(page,'중지 중에도 거래 한 건과 잔액 조회');
});

test('A02-06 · 실제 서버 재시작 후에도 중지와 로그인 세션을 유지한다',async({page})=>{
  test.setTimeout(90000);await given(page,'A02','재기동 후 투자 중지 유지');await login(page);await login(page,'admin');
  await test.step('Given · 관리자가 투자 중지',async()=>{await page.locator('#investment-toggle').click();await expect(page.locator('#investment-status')).toHaveText('투자 중지 중');});
  await test.step('When · QA 컨테이너 실제 재시작',async()=>{const {execFileSync}=await import('node:child_process');execFileSync('docker',['compose','--profile','qa','restart','qa'],{timeout:45000});await expect.poll(async()=>{try{return(await page.request.get('/actuator/health')).ok();}catch{return false;}},{timeout:45000,intervals:[500,1000]}).toBeTruthy();});
  await test.step('Then · 관리자 중지 상태·투자자 세션 유지, 신규 투자 차단',async()=>{await page.goto('/admin');await expect(page.locator('#investment-status')).toHaveText('투자 중지 중');await page.goto('/invest/teams/2');await expect(page.locator('#blocked')).toContainText('투자가 중지');const r=await direct(page,'/investor/investments','POST',{teamId:2,amount:100000},'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');expect(r.status).toBe(409);expect(r.data.error.code).toBe('INVESTMENT_PAUSED');});await capture(page,'재기동 이후에도 신규 투자 차단');
});
