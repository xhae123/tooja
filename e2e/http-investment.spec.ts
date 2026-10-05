import { test, expect } from '@playwright/test';
import { given, login, invest, direct, capture, evidence } from './helpers';

test('P03-M-09 · HTTP에서 randomUUID가 없어도 투자 확정·단일 차감', async ({ page }) => {
  await given(page, 'P03-M', 'HTTP 투자 요청 키 생성');
  await test.step('Given · crypto.randomUUID가 없는 브라우저', async () => {
    await page.addInitScript(() => Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true }));
  });
  const errors: string[] = [], keys: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/investor/investments')) keys.push(r.headers()['idempotency-key']); });
  await login(page);
  await invest(page, 2, 700000);
  await test.step('Then · UUID v4 키로 한 건만 투자되고 잔액은 30만원', async () => {
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(errors).toEqual([]);
    const history = await direct(page, '/investor/investments');
    expect(history.data.count).toBe(1);
    expect(history.data.investor.balance).toBe(300000);
    await evidence({ keys, errors, history });
  });
  await capture(page, 'HTTP 투자 확정 성공', { 2: '70만원 투자 영수증', 3: '한 번 차감 · 잔액 30만원' });
});

test('P03-M-10 · 저장소 실패는 미전송 안내·버튼 잠금 해제', async ({ page }) => {
  await given(page, 'P03-M', '투자 요청 준비 실패');
  await login(page);
  await page.goto('/invest/teams/2');
  await page.locator('[data-amount="700000"]').click();
  await page.locator('#next').click();
  await test.step('Given · 투자 복구 정보를 저장할 수 없는 브라우저', () => page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key.startsWith('tooja.pending.')) throw new DOMException('Storage unavailable', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  }));
  let posts = 0;
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/investor/investments')) posts++; });
  await test.step('When · 투자 확정을 누른다', () => page.locator('#confirm-send').click());
  await test.step('Then · 투자 없이 안내하며 다시 조작할 수 있다', async () => {
    await expect(page.locator('#processing')).toContainText('투자 요청을 준비하지 못했습니다');
    await expect(page.locator('#confirm-send')).toBeEnabled();
    await expect(page.locator('#back')).toBeEnabled();
    expect(posts).toBe(0);
    expect(errors).toEqual([]);
    expect((await direct(page, '/investor/investments')).data.count).toBe(0);
    await evidence({ posts, errors });
  });
  await capture(page, '저장 실패 · 미전송 안내', { 4: '돌아가기·투자 확정 버튼 잠금 해제' });
});
