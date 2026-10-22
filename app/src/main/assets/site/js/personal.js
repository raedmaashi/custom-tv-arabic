(function () {
  'use strict';
  var data = {}, matchKey = '';
  document.getElementById('prayerTitle').textContent = 'مواقيت الصلاة · ' + window.TV_HOME_CONFIG.location.name;
  document.getElementById('matchTitle').textContent = window.TV_HOME_CONFIG.favoriteTeam.name + ' · المباراة القادمة';
  var $ = function (id) { return document.getElementById(id); };
  var dateFormat = new Intl.DateTimeFormat('ar-SA-u-ca-gregory-nu-latn', {
    timeZone: window.TV_HOME_CONFIG.timezone, weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false
  });
  var updatedFormat = new Intl.DateTimeFormat('ar-SA-u-ca-gregory-nu-latn', {
    timeZone: window.TV_HOME_CONFIG.timezone, day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false
  });
  function text(tag, value, className) {
    var element = document.createElement(tag);
    element.textContent = value;
    if (className) element.className = className;
    return element;
  }
  function logo(url, name) {
    var wrap = document.createElement('div'); wrap.className = 'club-crest'; wrap.dataset.club = name || '';
    var fallback = text('span', (name || '').replace(/^ال/, '').slice(0, 2)); wrap.appendChild(fallback);
    if (!/^https:\/\/(images\.ctfassets\.net|contentfulproxy\.stadion\.io|a\.espncdn\.com)\//.test(url || '')) return wrap;
    var image = document.createElement('img'); image.alt = 'شعار ' + name;
    image.onload = function () { fallback.hidden = true; };
    image.onerror = function () { image.remove(); fallback.hidden = false; };
    image.referrerPolicy = 'no-referrer'; image.src = url; wrap.appendChild(image);
    return wrap;
  }
  function club(name, url) {
    var node = document.createElement('div'); node.className = 'club';
    node.appendChild(logo(url, name)); node.appendChild(text('div', name, 'club-name')); return node;
  }
  function source(at, error, label, hours) {
    if (!at) return error ? 'تعذّر التحديث · بانتظار الاتصال' : 'جارٍ الاتصال بالمصدر…';
    var stale = error || Date.now() - at >= hours * 3600000;
    return (stale ? 'نسخة محفوظة' : label) + ' · تحديث ' + updatedFormat.format(new Date(at));
  }
  function renderPrayers() {
    var title = 'جارٍ تحميل المواقيت…';
    if (data.prayerReady && data.nextAt) {
      var minutes = Math.max(1, Math.ceil((data.nextAt - Date.now()) / 60000));
      var remaining = minutes >= 60 ? Math.floor(minutes / 60) + ' س و' + (minutes % 60) + ' د' : minutes + ' دقيقة';
      title = data.nextPrayer + (data.tomorrow ? ' غدًا' : '') + ' بعد ' + remaining;
    } else if (data.prayerError) title = 'تعذّر تحميل مواقيت اليوم';
    else if (data.prayerReady) title = 'بانتظار مواقيت الصلاة التالية';
    $('nextPrayer').textContent = title;
    var row = $('prayerTimes'); row.textContent = '';
    (data.prayers || []).forEach(function (prayer) {
      var cell = document.createElement('div');
      cell.className = 'prayer-time' + (!data.tomorrow && data.nextPrayer === prayer.name ? ' next' : '');
      cell.appendChild(text('span', prayer.name)); cell.appendChild(text('b', prayer.time)); row.appendChild(cell);
    });
    $('prayerSource').textContent = source(data.prayerUpdated, data.prayerError, window.TV_HOME_CONFIG.prayerMethodLabel, 24);
  }
  function renderMatches() {
    var matches = data.matches || [], key = JSON.stringify([matches, data.fixtureUpdated, data.fixtureError]);
    if (key !== matchKey) {
      matchKey = key;
      var main = $('matchMain'), following = $('matchFollowing'); main.textContent = ''; following.textContent = '';
      if (matches.length) {
        var first = matches[0];
        main.appendChild(club(first.home, first.homeLogo));
        var meta = document.createElement('div'); meta.className = 'match-meta';
        meta.appendChild(text('div', first.pending ? 'الموعد قيد التحديد' : dateFormat.format(new Date(first.kickoff))));
        meta.appendChild(text('div', first.competition, 'competition')); main.appendChild(meta);
        main.appendChild(club(first.away, first.awayLogo));
        matches.slice(1, 3).forEach(function (match) {
          var item = document.createElement('div'); item.className = 'following-fixture';
          item.appendChild(logo(match.homeLogo, match.home)); item.appendChild(logo(match.awayLogo, match.away));
          item.appendChild(text('span', match.home + ' × ' + match.away, 'pair'));
          item.appendChild(text('time', match.pending ? 'قيد التحديد' : dateFormat.format(new Date(match.kickoff))));
          following.appendChild(item);
        });
      } else main.appendChild(text('h2', data.fixtureError ? 'تعذّر تحميل المباريات' : data.fixtureUpdated ? 'لا توجد مباراة قادمة معلنة' : 'جارٍ تحميل المباريات…'));
    }
    $('matchSource').textContent = source(data.fixtureUpdated, data.fixtureError, window.TV_HOME_CONFIG.favoriteTeam.sourceLabel, 3);
  }
  function update(value) { data = value || {}; renderPrayers(); renderMatches(); }
  window.TVPersonal = { update: update };
  if (window.AndroidTV) {
    try { update(JSON.parse(window.AndroidTV.dashboard())); } catch (error) { update({ prayerError: true, fixtureError: true }); }
  } else {
    fetch('preview/dashboard.json').then(function (response) { if (!response.ok) throw new Error('Preview unavailable'); return response.json(); }).then(update).catch(function () {
      $('nextPrayer').textContent = 'تظهر مواقيت ' + window.TV_HOME_CONFIG.location.name + ' على التلفزيون';
      $('matchMain').textContent = 'تظهر مباريات ' + window.TV_HOME_CONFIG.favoriteTeam.name + ' على التلفزيون';
    });
  }
  setInterval(renderPrayers, 60000);
})();
