const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function load() {
  const ctx = vm.createContext({ window: {} });
  for (const name of ['i18n.js', 'weather.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/site/js', name), 'utf8'), ctx);
  return ctx.window;
}
test('Portuguese weather labels cover every known WMO code without altering weather behaviour', () => {
  const { TVI18n: lang, TVWeather: weather } = load();
  for (const code of [0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99,999]) {
    lang.setLanguage('en-GB'); const en = weather.describe(code);
    lang.setLanguage('pt-PT'); const pt = weather.describe(code);
    assert.notEqual(pt.label, en.label, `translation for ${code}`);
    assert.equal(pt.kind, en.kind);
    assert.equal(pt.intensity, en.intensity);
  }
  assert.equal(weather.describe(63).label, 'Chuva');
});
test('language fallback and substitutions preserve literal app and place names', () => {
  const { TVI18n: lang } = load();
  assert.equal(lang.setLanguage('pt-BR'), 'pt-PT');
  assert.equal(lang.text('Humidity'), 'Humidade');
  assert.equal(lang.text('{count} apps', { count: 5 }), '5 aplicações');
  assert.equal(lang.text('{name} is not connected', { name: 'Game console' }), 'Game console não está ligado');
  lang.setLanguage('en-GB');
  assert.equal(lang.text('{count} apps', { count: 5 }), '5 apps');
  assert.equal(lang.text('YouTube'), 'YouTube');
});


test('Arabic weather translations retain weather types and intensity', () => {
  const {TVI18n:lang, TVWeather:weather} = load();
  for (const code of [0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99,999]) {
    lang.setLanguage('en-GB'); const en = weather.describe(code);
    assert.equal(lang.setLanguage('ar-SA'), 'ar-SA'); const ar = weather.describe(code);
    assert.match(ar.label, /[\u0600-\u06ff]/);
    assert.equal(ar.kind,en.kind); assert.equal(ar.intensity,en.intensity);
  }
});
