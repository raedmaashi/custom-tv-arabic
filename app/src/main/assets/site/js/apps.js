// Installed apps via the Tizen Application API; a sample list when previewed in a desktop browser.
(function () {
  'use strict';

  var isTizen = typeof window.tizen !== 'undefined' && window.tizen.application;
  var homeApps = window.TV_HOME_CONFIG.homeApps;

  function tr(key, values) {
    if (window.TVI18n) return window.TVI18n.text(key, values);
    return key.replace('{name}', values ? values.name : '{name}');
  }

  function selfId() {
    try { return tizen.application.getCurrentApplication().appInfo.id; } catch (e) { return ''; }
  }

  function iconUrl(p) {
    if (!p) return '';
    return p.charAt(0) === '/' ? 'file://' + p : p;
  }

  function sampleIcon(name, fg, bg) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="216"><rect width="400" height="216" fill="' + bg +
      '"/><text x="200" y="126" font-family="Helvetica, Arial" font-weight="700" font-size="46" text-anchor="middle" fill="' +
      fg + '">' + name + '</text></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }

  var SAMPLE = [
    ['sample.netflix', 'Netflix', '#e50914', '#000000'], ['sample.youtube', 'YouTube', '#ff0033', '#ffffff'],
    ['sample.prime', 'Prime Video', '#ffffff', '#0f79f3'], ['sample.apple', 'Apple TV', '#ffffff', '#1c1c1e'],
    ['sample.disney', 'Disney+', '#ffffff', '#0b2a43'], ['sample.max', 'Max', '#ffffff', '#002be7'],
    ['sample.spotify', 'Spotify', '#1ed760', '#121212'], ['sample.browser', 'Browser', '#ffffff', '#2b5bd7'],
    ['sample.livetv', 'Live TV', '#ffffff', '#3a3f4b'], ['sample.player', 'Media Player', '#ffffff', '#c2410c'],
    ['sample.manual', 'User Guide', '#ffffff', '#334155']
  ];

  function list() {
    if (window.AndroidTV) return Promise.resolve(JSON.parse(window.AndroidTV.listApps()));
    if (!isTizen) {
      if (homeApps.length) return Promise.resolve(homeApps.map(function (a) {
        return { id: a.id, name: a.name, icon: a.icon || sampleIcon(a.name, "#ffffff", "#17273f"), source: a.source, sample: true };
      }));
      return Promise.resolve(SAMPLE.map(function (s) {
        return { id: s[0], name: s[1], icon: sampleIcon(s[1], s[2], s[3]), sample: true };
      }));
    }
    return new Promise(function (resolve, reject) {
      tizen.application.getAppsInfo(function (infos) {
        var me = selfId();
        var apps = infos.filter(function (a) {
          return a.id !== me && (a.show || homeApps.some(function (h) { return h.id === a.id; }));
        }).map(function (a) {
          var home = homeApps.filter(function (h) { return h.id === a.id; })[0];
          return { id: a.id, name: home ? home.name : a.name, icon: home ? home.icon : iconUrl(a.iconPath) };
        });
        resolve(apps.concat(homeApps.filter(function (a) { return a.source; })));
      }, function (err) { reject(new Error(err && err.message || 'Could not read installed apps')); });
    });
  }

  function launch(id) {
    if (window.AndroidTV) return window.AndroidTV.launch(id) ? Promise.resolve() : Promise.reject(new Error(tr('launch failed')));
    if (!isTizen) return Promise.reject(new Error(tr('Preview mode: apps only open on the TV')));
    return new Promise(function (resolve, reject) {
      var input = homeApps.filter(function (a) { return a.id === id && a.source; })[0];
      if (input) {
        tizen.systeminfo.getPropertyValue('VIDEOSOURCE', function (video) {
          var source = video.connected.filter(function (s) {
            return s.type === input.source.type && s.number === input.source.number;
          })[0];
          if (!source) { reject(new Error(tr('{name} is not connected', { name: input.name }))); return; }
          try {
            tizen.tvwindow.setSource(source, function () {
              try { tizen.application.getCurrentApplication().hide(); resolve(); } catch (e) { reject(e); }
            }, reject, 'MAIN');
          } catch (e) { reject(e); }
        }, reject);
        return;
      }
      tizen.application.launch(id, resolve, function (err) { reject(new Error(err && err.message || 'launch failed')); });
    });
  }

  function exit() {
    if (window.AndroidTV) return;
    if (isTizen) { try { tizen.application.getCurrentApplication().exit(); } catch (e) { /* already exiting */ } }
  }

  window.TVApps = { list: list, launch: launch, exit: exit, isTizen: !!isTizen, isAndroid: !!window.AndroidTV };
})();
