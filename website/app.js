import { dictionaries, matchLanguage } from './locales.js';

const root = document.documentElement;
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* Private browsing can disable storage. */ } }
};
const languageSelect = document.querySelector('#language');
const motionButton = document.querySelector('#motion-toggle');
const motionQuery = matchMedia('(prefers-reduced-motion: reduce)');
const compactQuery = matchMedia('(max-height: 560px)');
let languagePreference = storage.get('lanlink.language') || 'auto';
if (languagePreference !== 'auto' && !dictionaries[languagePreference]) languagePreference = 'auto';
let locale = 'en';
let reduced = false;
let motionPreference = storage.get('lanlink.motion');
let frame = 0;
let lastTime = 0;
let currentProgress = null;
let activeChapter = -1;
let metrics = {};

function applyLanguage(preference) {
  languagePreference = preference;
  locale = preference === 'auto' ? matchLanguage(navigator.languages?.length ? navigator.languages : [navigator.language || 'en']) : preference;
  const text = dictionaries[locale] || dictionaries.en;
  root.lang = locale;
  document.title = text.metaTitle;
  document.querySelector('meta[name="description"]').content = text.metaDescription;
  document.querySelector('meta[property="og:title"]').content = text.metaTitle;
  document.querySelector('meta[property="og:description"]').content = text.metaDescription;
  document.querySelectorAll('[data-i18n]').forEach(element => {
    if (text[element.dataset.i18n] !== undefined) element.textContent = text[element.dataset.i18n];
  });
  document.querySelectorAll('[data-i18n-aria]').forEach(element => {
    if (text[element.dataset.i18nAria]) element.setAttribute('aria-label', text[element.dataset.i18nAria]);
  });
  languageSelect.value = preference;
  updateMotionLabel();
  scheduleMeasure();
}

function updateMotionLabel() {
  const key = reduced ? 'enableMotion' : 'reduceMotion';
  const span = motionButton.querySelector('span');
  span.dataset.i18n = key;
  span.textContent = dictionaries[locale][key];
  motionButton.setAttribute('aria-pressed', String(reduced));
  motionButton.querySelector('use').setAttribute('href', reduced ? '#i-play' : '#i-pause');
}

function applyMotion() {
  reduced = motionPreference === 'reduce' || (motionPreference !== 'full' && motionQuery.matches);
  root.classList.toggle('has-motion', !reduced && !compactQuery.matches);
  root.classList.toggle('is-compact', compactQuery.matches);
  root.classList.toggle('is-reduced', reduced);
  document.querySelectorAll('.story-copy').forEach(element => {
    element.style.removeProperty('opacity');
    element.style.removeProperty('transform');
    element.removeAttribute('aria-hidden');
  });
  activeChapter = -1;
  currentProgress = null;
  updateMotionLabel();
  scheduleMeasure();
}

languageSelect.addEventListener('change', () => {
  storage.set('lanlink.language', languageSelect.value);
  applyLanguage(languageSelect.value);
});
window.addEventListener('languagechange', () => { if (languagePreference === 'auto') applyLanguage('auto'); });
motionQuery.addEventListener('change', applyMotion);
compactQuery.addEventListener('change', applyMotion);
motionButton.addEventListener('click', () => {
  const top = motionButton.getBoundingClientRect().top;
  motionPreference = reduced ? 'full' : 'reduce';
  storage.set('lanlink.motion', motionPreference);
  applyMotion();
  // Keep the preference control in view when the long scroll story collapses.
  window.scrollBy({ top: motionButton.getBoundingClientRect().top - top, behavior: 'instant' });
});

const story = document.querySelector('.story');
const stage = document.querySelector('.story-stage');
const copies = [...document.querySelectorAll('.story-copy')];
const chapterControls = [...document.querySelectorAll('.chapter-control')];
const heroArt = document.querySelector('.hero-art');
const hero = document.querySelector('.hero');
const privacyArt = document.querySelector('.privacy-art');
const readingProgress = document.querySelector('.reading-progress');
const clamp = (value, low = 0, high = 1) => Math.max(low, Math.min(high, value));
const smooth = value => { const t = clamp(value); return t * t * (3 - 2 * t); };

function measure() {
  const y = window.scrollY;
  metrics = {
    storyTop: story.getBoundingClientRect().top + y,
    storyRange: Math.max(1, story.offsetHeight - window.innerHeight),
    heroHeight: hero.offsetHeight,
    privacyTop: privacyArt.getBoundingClientRect().top + y,
    documentRange: Math.max(1, document.documentElement.scrollHeight - window.innerHeight),
    height: window.innerHeight,
  };
  scheduleFrame();
}
let measureFrame = 0;
function scheduleMeasure() {
  if (measureFrame) return;
  measureFrame = requestAnimationFrame(() => { measureFrame = 0; measure(); });
}
function scheduleFrame() {
  if (!frame && !document.hidden) frame = requestAnimationFrame(renderFrame);
}
function renderFrame(time) {
  frame = 0;
  const y = window.scrollY;
  readingProgress.style.transform = `scaleX(${clamp(y / metrics.documentRange)})`;
  if (reduced || compactQuery.matches) return;
  heroArt.style.setProperty('--hero-p', clamp(y / metrics.heroHeight).toFixed(4));
  const orbitProgress = clamp((y + metrics.height - metrics.privacyTop) / (metrics.height * 1.6));
  privacyArt.style.setProperty('--orbit-angle', `${orbitProgress * 100}deg`);
  const target = clamp((y - metrics.storyTop) / metrics.storyRange);
  const dt = Math.min(64, time - (lastTime || time - 16));
  lastTime = time;
  if (currentProgress === null) currentProgress = target;
  currentProgress += (target - currentProgress) * (1 - Math.exp(-dt / 65));
  if (Math.abs(target - currentProgress) < .0001) currentProgress = target;
  const p = currentProgress;
  const phase = p * 3;
  const chapter = Math.min(2, Math.floor(phase));
  const fade1 = smooth((phase - .87) / .26);
  const fade2 = smooth((phase - 1.87) / .26);
  const opacities = [1 - fade1, fade1 * (1 - fade2), fade2];
  copies.forEach((copy, index) => {
    copy.style.opacity = opacities[index].toFixed(3);
    copy.style.transform = `translateY(${(1 - opacities[index]) * 20}px)`;
  });
  if (chapter !== activeChapter) {
    activeChapter = chapter;
    chapterControls.forEach((button, i) => {
      if (i === chapter) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
  }
  chapterControls.forEach((button, i) => button.style.setProperty('--chapter-p', clamp(phase - i).toFixed(3)));
  const mouseP = smooth(phase / .95);
  stage.style.setProperty('--cursor-x', `${25 + mouseP * 51}%`);
  stage.style.setProperty('--cursor-y', `${48 - Math.sin(mouseP * Math.PI) * 18}%`);
  stage.style.setProperty('--cursor-opacity', (1 - fade1).toFixed(3));
  stage.style.setProperty('--gather', smooth(phase / .8).toFixed(3));
  stage.style.setProperty('--link-p', smooth(p * 1.5).toFixed(3));
  const clipboardP = smooth((phase - 1.08) / .74);
  stage.style.setProperty('--clip-opacity', (fade1 * (1 - fade2)).toFixed(3));
  stage.style.setProperty('--clip-x', `${30 + clipboardP * 43}%`);
  stage.style.setProperty('--clip-rise', (-Math.sin(clipboardP * Math.PI) * 48).toFixed(2));
  stage.style.setProperty('--clip-tilt', (-5 + clipboardP * 10).toFixed(2));
  const fileP = smooth((phase - 2.08) / .74);
  stage.style.setProperty('--file-opacity', fade2.toFixed(3));
  stage.style.setProperty('--file-x', `${30 + fileP * 35}%`);
  stage.style.setProperty('--file-rise', (-Math.sin(fileP * Math.PI) * 42).toFixed(2));
  stage.style.setProperty('--file-tilt', (-4 + fileP * 7).toFixed(2));
  if (currentProgress !== target) scheduleFrame();
}

chapterControls.forEach((button, index) => button.addEventListener('click', () => {
  measure();
  window.scrollTo({ top: metrics.storyTop + metrics.storyRange * ((index + .18) / 3), behavior: reduced ? 'instant' : 'smooth' });
}));
window.addEventListener('scroll', scheduleFrame, { passive: true });
window.addEventListener('resize', scheduleMeasure, { passive: true });
window.addEventListener('pageshow', scheduleMeasure);
document.addEventListener('visibilitychange', () => { if (!document.hidden) { lastTime = 0; scheduleMeasure(); } });
if ('ResizeObserver' in window) new ResizeObserver(scheduleMeasure).observe(document.body);
if ('IntersectionObserver' in window) {
  root.classList.add('enhanced');
  const observer = new IntersectionObserver(entries => entries.forEach(entry => {
    if (entry.isIntersecting) { entry.target.classList.add('is-visible'); observer.unobserve(entry.target); }
  }), { threshold: .08, rootMargin: '0px 0px -25px 0px' });
  document.querySelectorAll('.reveal').forEach(element => observer.observe(element));
}

const userAgent = navigator.userAgent.toLowerCase();
const platform = /android/.test(userAgent) ? 'android' : /windows/.test(userAgent) ? 'windows' : /macintosh|mac os x/.test(userAgent) && !/iphone|ipad/.test(userAgent) ? 'mac' : null;
if (platform) document.querySelector(`[data-platform="${platform}"]`)?.classList.add('is-platform');
document.querySelector('#year').textContent = new Date().getFullYear();
applyLanguage(languagePreference);
applyMotion();
