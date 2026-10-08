import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { dictionaries, matchLanguage } from '../locales.js';

test('every supported locale has the same complete, nonempty copy', () => {
  assert.deepEqual(Object.keys(dictionaries).sort(), ['de', 'en', 'fr', 'ja', 'zh-CN', 'zh-TW']);
  const expectedKeys = Object.keys(dictionaries.en).sort();
  for (const [locale, dictionary] of Object.entries(dictionaries)) {
    assert.deepEqual(Object.keys(dictionary).sort(), expectedKeys, `${locale} must have every translation key`);
    for (const [key, value] of Object.entries(dictionary)) {
      assert.equal(typeof value, 'string', `${locale}.${key} must be text`);
      assert.ok(value.trim(), `${locale}.${key} must not be empty`);
    }
  }
});

test('system language selection respects preference order and falls back to English', () => {
  for (const [languages, expected] of [
    [['es-ES', 'fr-CA', 'de-DE'], 'fr'],
    [['de-AT', 'ja-JP'], 'de'],
    [['en-GB', 'zh-CN'], 'en'],
    [['es-ES', 'pt-BR'], 'en'],
    [[], 'en'],
  ]) {
    assert.equal(matchLanguage(languages), expected, languages.join(', '));
  }
  assert.equal(matchLanguage(), 'en');
});

test('Chinese script takes precedence over region, with regional and default fallbacks', () => {
  for (const [language, expected] of [
    ['zh-Hans-HK', 'zh-CN'],
    ['zh-Hans-TW', 'zh-CN'],
    ['zh-Hans-MO', 'zh-CN'],
    ['zh-Hant-CN', 'zh-TW'],
    ['zh-Hant-SG', 'zh-TW'],
    ['zh-Hans', 'zh-CN'],
    ['zh-Hant', 'zh-TW'],
    ['zh-TW', 'zh-TW'],
    ['zh-HK', 'zh-TW'],
    ['zh-MO', 'zh-TW'],
    ['zh-CN', 'zh-CN'],
    ['zh-SG', 'zh-CN'],
    ['zh', 'zh-CN'],
  ]) {
    assert.equal(matchLanguage([language]), expected, language);
  }
});

test('BCP 47 language tags are matched without case sensitivity', () => {
  for (const [language, expected] of [
    ['ZH-hAnS-hK', 'zh-CN'],
    ['zH-HANT-cn', 'zh-TW'],
    ['Zh-tW', 'zh-TW'],
    ['JA-jp', 'ja'],
    ['dE-Latn-DE', 'de'],
    ['fR-ca', 'fr'],
    ['EN-us', 'en'],
  ]) {
    assert.equal(matchLanguage([language]), expected, language);
  }
});

test('every translated content and accessible label in the page exists in every locale', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  const attributes = [...html.matchAll(/\b(data-i18n(?:-aria)?)\s*=\s*(['"])(.*?)\2/g)];
  assert.ok(attributes.some(match => match[1] === 'data-i18n'), 'page has translated content');
  assert.ok(attributes.some(match => match[1] === 'data-i18n-aria'), 'page has translated accessible labels');
  for (const key of new Set(attributes.map(match => match[3]))) {
    for (const [locale, dictionary] of Object.entries(dictionaries)) {
      assert.ok(Object.hasOwn(dictionary, key), `${locale} is missing the page key ${key}`);
    }
  }
});
