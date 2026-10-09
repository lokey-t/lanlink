import {readFileSync} from 'node:fs';

// All clients package this same catalogue. The operating system language is
// resolved by the native UI; the service stores only this device's preference.
export const languageIds = Object.freeze(JSON.parse(readFileSync(new URL('./locales.json', import.meta.url), 'utf8')).languages.map(language => language.id));
export const languagePreference = value => languageIds.includes(value) ? value : 'system';
