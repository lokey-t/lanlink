import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {EventEmitter} from 'node:events';
import os from 'node:os';
import path from 'node:path';
import {loadConfig, saveConfig} from '../src/identity.js';
import {languageIds, languagePreference} from '../src/language.js';
import {Service} from '../src/service.js';

const catalog = JSON.parse(await readFile(new URL('../src/locales.json', import.meta.url), 'utf8'));

async function fixture(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-language-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  const config = await loadConfig(dir);
  const native = Object.assign(new EventEmitter(), {capabilities: {}, call: async () => ({})});
  return {dir, config, service: new Service(config, dir, native, {noQuic: true})};
}

test('first use follows the system and older configurations remain compatible', async t => {
  const {dir, config, service} = await fixture(t);
  assert.equal(config.language, 'system');
  assert.equal((await loadConfig(dir)).language, 'system');
  delete config.language;
  await saveConfig(dir, config);
  assert.equal(service.state().language, 'system');
  assert.equal(languagePreference((await loadConfig(dir)).language), 'system');
  for (const value of [null, '', 'unknown', {}, ['en']]) assert.equal(languagePreference(value), 'system');
});

test('every language choice persists locally and survives a service restart', async t => {
  const {dir, config, service} = await fixture(t);
  const identity = service.identity();
  for (const language of languageIds) {
    await service.settings({language});
    assert.equal(service.state().language, language);
    assert.equal((await loadConfig(dir)).language, language);
    assert.deepEqual(service.identity(), identity, 'language is not broadcast as part of device identity');
  }
  await service.settings({language: 'ja', name: '设置'});
  assert.equal(config.name, '设置', 'a user-defined name must never be translated');
  const restored = new Service(await loadConfig(dir), dir, Object.assign(new EventEmitter(), {capabilities: {}}), {noQuic: true});
  assert.equal(restored.state().language, 'ja');
  assert.equal(restored.state().name, '设置');
});

test('unsupported language rejects the complete edit without changing saved settings', async t => {
  const {dir, config, service} = await fixture(t);
  await service.settings({language: 'fr'});
  const previous = structuredClone(config), disk = await readFile(path.join(dir, 'config.json'), 'utf8');
  for (const language of ['xx', 'EN', 'en-US', null, false, {}, []]) {
    await assert.rejects(service.settings({language, name: 'must not save', theme: 'dark'}), /界面语言/);
    assert.deepEqual(config, previous);
    assert.equal(await readFile(path.join(dir, 'config.json'), 'utf8'), disk);
  }
});

test('the shared catalog has complete language options, settings translations and safe placeholders', () => {
  assert.deepEqual(languageIds, ['system', 'zh-Hans', 'zh-Hant', 'en', 'ja', 'ko', 'de', 'fr', 'es', 'pt', 'it', 'ru']);
  assert.equal(new Set(languageIds).size, languageIds.length);
  for (const option of catalog.languages) assert.ok(option.name.trim());
  const required = ['语言', '跟随系统', '设置', '设备名称', '外观', '深色', '浅色', '共享剪贴板', '保存设置', '取消', '键鼠主设备', '边缘停留时间 · {0} 毫秒', '权限使用模式', '登录 Mac 后自动启动 LanLink', '登录 Windows 后自动启动 LanLink'];
  const placeholders = value => [...value.matchAll(/\{\d+\}/g)].map(match => match[0]).sort();
  for (const key of required) {
    for (const language of languageIds.filter(id => !['system', 'zh-Hans'].includes(id))) {
      assert.ok(catalog.messages[key]?.[language]?.trim(), `${key}: ${language}`);
    }
  }
  for (const [key, translations] of Object.entries(catalog.messages)) {
    assert.ok(translations.en?.trim(), `English fallback: ${key}`);
    for (const [language, value] of Object.entries(translations)) {
      assert.ok(languageIds.includes(language), `Unsupported translation: ${language}`);
      assert.equal(typeof value, 'string');
      assert.deepEqual(placeholders(value), placeholders(key), `Placeholders: ${key} (${language})`);
    }
  }
});
