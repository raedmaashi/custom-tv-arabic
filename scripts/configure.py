#!/usr/bin/env python3
"""Generate app configuration from a local profile, without touching a TV."""
import argparse
import json
import re
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]

def validate(profile):
    city, prayer, team = profile['city'], profile['prayer'], profile['team']
    if not city['name'].strip() or not team['name'].strip():
        raise ValueError('City and team names must be non-empty')
    if not -90 <= float(city['latitude']) <= 90 or not -180 <= float(city['longitude']) <= 180:
        raise ValueError('Invalid coordinates')
    ZoneInfo(city['timezone'])
    if not isinstance(prayer['method'], int) or not 0 <= prayer['method'] <= 23:
        raise ValueError('Invalid AlAdhan method')
    if not prayer['label'].strip():
        raise ValueError('Describe the prayer calculation method')
    if team['provider'] not in ['alhilal', 'espn']:
        raise ValueError('Supported fixture providers: alhilal, espn')
    if team['provider'] == 'alhilal' and team['name'] != 'الهلال':
        raise ValueError('The official Al Hilal adapter must only be used for Al Hilal')
    if team['provider'] == 'espn':
        if not re.fullmatch(r'\d+', str(team.get('id', ''))):
            raise ValueError('Resolve an ESPN team ID from its live teams endpoint')
        if not team.get('leagues') or any(not re.fullmatch(r'[a-z0-9.]+', league) for league in team['leagues']):
            raise ValueError('Provide verified ESPN league slugs')
    for app in profile.get('apps', []):
        if not re.fullmatch(r'[a-zA-Z_][\w]*(?:\.[a-zA-Z_][\w]*)+', app['id']):
            raise ValueError('Invalid Android package name')
    for key in ['showPrayers', 'showMatches', 'showWeather']:
        if not isinstance(profile.get('panels', {}).get(key, True), bool):
            raise ValueError('Panel values must be booleans')
    return profile

def generate(profile, root=ROOT):
    validate(profile)
    city, prayer, team = profile['city'], profile['prayer'], profile['team']
    quote = lambda value: json.dumps(str(value), ensure_ascii=False)
    constants = {'CITY': city['name'], 'TIMEZONE': city['timezone'], 'PRAYER_LABEL': prayer['label'],
                 'TEAM_NAME': team['name'], 'PROVIDER': team['provider'], 'TEAM_ID': team.get('id', '')}
    java = 'package tv.customhome.launcher.dashboard;\n\npublic final class DashboardProfile {\n'
    for key, value in constants.items():
        java += '    public static final String ' + key + ' = ' + quote(value) + ';\n'
    java += f'    public static final double LATITUDE = {float(city["latitude"])};\n'
    java += f'    public static final double LONGITUDE = {float(city["longitude"])};\n'
    java += f'    public static final int PRAYER_METHOD = {prayer["method"]};\n'
    java += '    public static final String[] LEAGUES = {' + ', '.join(quote(v) for v in team.get('leagues', [])) + '};\n'
    java += '    private DashboardProfile() { }\n}\n'
    (root/'app/src/main/java/tv/customhome/launcher/dashboard/DashboardProfile.java').write_text(java, encoding='utf-8')
    defaults = {'language':'ar-SA','unit':'C','clock':'24','scenery':'auto','effects':False,'hidden':[],
                'showPrayers':True,'showMatches':True,'showWeather':True}
    defaults.update(profile.get('panels', {}))
    config = {'location':{'name':city['name'],'latitude':city['latitude'],'longitude':city['longitude']},
              'locale':'ar-SA','timezone':city['timezone'],'prayerMethodLabel':prayer['label'],
              'favoriteTeam':{'name':team['name'],'sourceLabel':'موقع الهلال الرسمي' if team['provider']=='alhilal' else 'ESPN · حسب الدوريات المختارة'},
              'weatherRefreshMinutes':15,'homeApps':profile.get('apps', []),
              'favoriteOrder':['youtube','netflix','osn','apple','disney','amazon'],'defaults':defaults}
    (root/'app/src/main/assets/site/js/config.js').write_text('window.TV_HOME_CONFIG = '+json.dumps(config,ensure_ascii=False,indent=2)+';\n', encoding='utf-8')
    return config

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    args = parser.parse_args()
    generate(json.loads(args.profile.read_text(encoding='utf-8')))
    print('Profile generated. Existing installed preferences are preserved on APK updates.')

if __name__ == '__main__':
    main()
