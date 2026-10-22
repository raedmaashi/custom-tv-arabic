const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../app/src/main/assets/site');

// A fixed lineup, so these tests do not depend on what js/config.js pins.
const HOME_APPS = [
  { id: 'example.iptv', name: 'IPTV' },
  { id: 'org.tizen.netflix-app', name: 'Netflix', icon: 'css/app-icons/netflix.png' },
  { id: 'input.hdmi1', name: 'Game console', icon: 'css/app-icons/hdmi.svg', source: { type: 'HDMI', number: 1 } },
  { id: 'input.hdmi2', name: 'HDMI 2', icon: 'css/app-icons/hdmi.svg', source: { type: 'HDMI', number: 2 } }
];

function app(tizen, homeApps = HOME_APPS) {
  const context = vm.createContext({ tizen, window: { tizen, TV_HOME_CONFIG: { homeApps } } });
  vm.runInContext(fs.readFileSync(path.join(root, 'js', 'apps.js'), 'utf8'), context);
  return context.window;
}

function tv(options = {}) {
  const calls = [];
  const device = {
    application: {
      getCurrentApplication: () => ({ appInfo: { id: 'TvHomeLnch.Home' }, hide: () => calls.push('hide') }),
      getAppsInfo: (ok, fail) => options.listError ? fail(options.listError) : ok(options.apps || []),
      launch: (id, ok) => { calls.push(id); ok(); }
    },
    systeminfo: {
      getPropertyValue: (key, ok, fail) => {
        assert.equal(key, 'VIDEOSOURCE');
        if (options.sourceError) return fail(options.sourceError);
        ok({ connected: options.sources || [{ type: 'HDMI', number: 1 }, { type: 'HDMI', number: 2 }] });
      }
    },
    tvwindow: {
      setSource: (source, ok, fail, window) => {
        assert.equal(window, 'MAIN');
        calls.push({ ...source });
        if (options.switchError) return fail(options.switchError);
        ok();
      }
    }
  };
  return { api: app(device).TVApps, calls };
}

test('shipped config loads and every icon it names is bundled', () => {
  const context = vm.createContext({ window: {} });
  vm.runInContext(fs.readFileSync(path.join(root, 'js', 'config.js'), 'utf8'), context);
  const config = context.window.TV_HOME_CONFIG;
  assert.ok(config.location.name && Number.isFinite(config.location.latitude) && Number.isFinite(config.location.longitude));
  for (const pinned of config.homeApps) if (pinned.icon) assert.ok(fs.statSync(path.join(root, pinned.icon)).size > 0, pinned.icon);
});

test('preview shows the pinned lineup in order', async () => {
  const window = app();
  const tiles = await window.TVApps.list();
  assert.deepEqual(Array.from(tiles, tile => tile.name), ['IPTV', 'Netflix', 'Game console', 'HDMI 2']);
  await assert.rejects(window.TVApps.launch(tiles[0].id), /Preview mode/);
});

test('preview falls back to sample apps when nothing is pinned', async () => {
  const tiles = await app(undefined, []).TVApps.list();
  assert.ok(tiles.length > 0);
  assert.ok(tiles.every(tile => tile.sample && tile.icon.startsWith('data:image/svg+xml')));
});

test('installed pinned apps survive show=false; self and hidden system apps are excluded', async () => {
  const { api } = tv({ apps: [
    { id: 'TvHomeLnch.Home', show: true },
    { id: 'system.hidden', show: false },
    { id: 'org.tizen.netflix-app', name: 'Old name', iconPath: '/old.png', show: false },
    { id: 'other.visible', name: 'Other', iconPath: '/other.png', show: true }
  ] });
  const tiles = await api.list();
  assert.deepEqual(Array.from(tiles, tile => tile.id), [
    'org.tizen.netflix-app', 'other.visible', 'input.hdmi1', 'input.hdmi2'
  ]);
  assert.equal(tiles[0].name, 'Netflix');
  assert.equal(tiles[0].icon, 'css/app-icons/netflix.png');
  assert.equal(tiles[1].icon, 'file:///other.png');
});

test('regular apps launch by exact installed ID', async () => {
  const { api, calls } = tv();
  await api.launch('example.iptv');
  assert.deepEqual(calls, ['example.iptv']);
});

test('HDMI selection uses the matching connected source and then hides Home', async () => {
  const { api, calls } = tv();
  await api.launch('input.hdmi2');
  assert.deepEqual(calls, [{ type: 'HDMI', number: 2 }, 'hide']);
});

test('disconnected HDMI leaves Home visible', async () => {
  const { api, calls } = tv({ sources: [{ type: 'HDMI', number: 2 }] });
  await assert.rejects(api.launch('input.hdmi1'), /Game console is not connected/);
  assert.deepEqual(calls, []);
});

test('source lookup and switch errors leave Home visible', async () => {
  for (const options of [{ sourceError: new Error('lookup failed') }, { switchError: new Error('switch failed') }]) {
    const { api, calls } = tv(options);
    await assert.rejects(api.launch('input.hdmi1'), /failed/);
    assert.ok(!calls.includes('hide'));
  }
});

test('app inventory errors propagate', async () => {
  await assert.rejects(tv({ listError: { message: 'access denied' } }).api.list(), /access denied/);
});


test('Android uses native installed inventory and exact app launch IDs', async () => {
  const window = app();
  const calls = [];
  window.AndroidTV = { listApps: () => JSON.stringify([{id:'real.app',name:'Real app'}]), launch: id => { calls.push(id); return id === 'real.app'; } };
  const apps = await window.TVApps.list();
  assert.equal(apps[0].id, 'real.app');
  await window.TVApps.launch('real.app');
  await assert.rejects(window.TVApps.launch('missing.app'), /launch failed/);
  assert.deepEqual(calls, ['real.app','missing.app']);
});
