#!/usr/bin/env python3
"""Inspect, install and conservatively clean one explicitly selected Android TV."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP_ID = 'tv.customhome.launcher'
HOME_COMPONENT = APP_ID + '/.MainActivity'
PACKAGE = re.compile(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+')
COMPONENT = re.compile(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.]+')
PROTECTED = {'android','com.android.systemui','com.android.settings','com.android.tv.settings',
             'com.google.android.gms','com.google.android.gsf','com.android.vending',
             'com.google.android.tungsten.setupwraith','com.android.bluetooth',
             'com.mediatek.tv.service','com.mediatek.wwtv.tvcenter',APP_ID}
PROTECTED_PREFIXES = ('com.android.providers.','com.android.networkstack','com.google.android.networkstack',
                      'com.android.permissioncontroller','com.google.android.permissioncontroller',
                      'com.android.packageinstaller','com.google.android.packageinstaller')


def execute(command):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=120)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'Command failed')
    return result.stdout.strip()


def atomic_save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix='.state-')
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


class Television:
    def __init__(self, device, expected_serial=None, runner=execute):
        if not re.fullmatch(r'[A-Za-z0-9_.:-]+', device) or device.startswith('-'):
            raise ValueError('Invalid ADB target')
        self.device, self.expected_serial, self.runner = device, expected_serial, runner

    def adb(self, *args):
        return self.runner(['adb', '-s', self.device, *map(str, args)])

    def shell(self, *args):
        output = self.adb('shell', shlex.join(map(str, args)))
        if re.search(r'(^|\n)(Error:|Exception|Security exception)', output):
            raise RuntimeError(output)
        return output

    def identity(self, mutate=False):
        serial = self.shell('getprop', 'ro.serialno') or self.shell('getprop', 'ro.boot.serialno')
        if not serial or serial == 'unknown': raise RuntimeError('Cannot establish a hardware serial')
        if mutate and (not self.expected_serial or serial != self.expected_serial):
            raise RuntimeError('Hardware serial mismatch or missing --serial. No changes made.')
        return serial

    def verify_tv(self):
        self.identity(mutate=True)
        if self.shell('pm', 'has-feature', 'android.software.leanback') != 'true':
            raise RuntimeError('Android TV capability not confirmed')
        if int(self.shell('getprop', 'ro.build.version.sdk')) < 26:
            raise RuntimeError('Android 8 or newer is required')

    def home(self):
        value = self.shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').splitlines()[-1]
        if not COMPONENT.fullmatch(value): raise RuntimeError('Could not identify current Home')
        return value

    def package_state(self, package):
        if not PACKAGE.fullmatch(package): raise ValueError('Invalid package')
        details = self.shell('dumpsys', 'package', package)
        found = re.search(r'User 0:[^\n]*\benabled=(\d+)', details)
        if not found: raise RuntimeError('Could not read exact enabled state: ' + package)
        return int(found.group(1))

    def protected_packages(self):
        packages = set(PROTECTED)
        packages.add(self.home().split('/')[0])
        rows = self.shell('cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        for line in rows.splitlines():
            if COMPONENT.fullmatch(line.strip()): packages.add(line.strip().split('/')[0])
        config = ROOT/'app/src/main/assets/site/js/config.js'
        data = json.loads(config.read_text(encoding='utf-8').split('=', 1)[1].strip().rstrip(';'))
        packages.update(app['id'] for app in data['homeApps'])
        return packages


def reject_critical(package, protected):
    if not PACKAGE.fullmatch(package): raise ValueError('Not a package name: ' + package)
    if package in protected or package.startswith(PROTECTED_PREFIXES) or 'tvinput' in package.lower():
        raise ValueError('Protected package cannot be cleaned: ' + package)


def load_state(tv, path):
    serial = tv.identity(mutate=True)
    if path.exists():
        state = json.loads(path.read_text(encoding='utf-8'))
        if state.get('serial') != serial: raise RuntimeError('Backup belongs to another television')
        return state
    state = {'serial':serial, 'previousHome':tv.home(), 'packages':{}, 'power':{}, 'journal':[]}
    atomic_save(path, state)
    return state


def cleanup(tv, path, plan, apply=False):
    tv.verify_tv()
    protected = tv.protected_packages()
    packages = [entry['package'] for entry in plan['packages']]
    for package in packages: reject_critical(package, protected)
    installed = {line.removeprefix('package:') for line in tv.shell('pm','list','packages').splitlines()}
    selected = [entry for entry in plan['packages'] if entry['package'] in installed]
    print(json.dumps({'action':'disable-user, never uninstall','candidates':selected,'apply':apply},ensure_ascii=False,indent=2))
    if not apply: return
    state = load_state(tv, path)
    for entry in selected:
        package = entry['package']; old = tv.package_state(package)
        if old in [2,3,4]: continue
        state['packages'].setdefault(package, old)
        state['journal'].append({'package':package,'action':'disable-user','status':'planned'})
        atomic_save(path, state)
        tv.shell('pm', 'disable-user', '--user', '0', package)
        if tv.package_state(package) != 3: raise RuntimeError('Disable verification failed: ' + package)
        state['journal'][-1]['status']='done'; atomic_save(path, state)


def inspect_apk(path):
    candidates=[]
    if shutil.which('aapt'): candidates.append(Path(shutil.which('aapt')))
    for name in ['ANDROID_HOME','ANDROID_SDK_ROOT']:
        if os.environ.get(name): candidates.extend(Path(os.environ[name]).glob('build-tools/*/aapt*'))
    candidates=[p for p in candidates if p.name in ['aapt','aapt.exe'] and p.is_file()]
    if not candidates: raise RuntimeError('aapt is required to verify the APK; set ANDROID_HOME')
    result=execute([str(sorted(candidates)[-1]),'dump','badging',str(path)])
    found=re.search(r"package: name='([^']+)'",result)
    if not found or found.group(1)!=APP_ID: raise RuntimeError('APK is not Custom TV Arabic')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device',required=True,help='Exact ADB target, e.g. TV_IP:PORT or USB device ID')
    parser.add_argument('--serial',help='Hardware serial reported by inspect; required for every mutation')
    parser.add_argument('--state',type=Path,default=ROOT/'.local/tv-state.json')
    commands=parser.add_subparsers(dest='command',required=True)
    commands.add_parser('inspect')
    install=commands.add_parser('install');install.add_argument('--apk',type=Path,required=True)
    commands.add_parser('make-home')
    clean=commands.add_parser('cleanup');clean.add_argument('--plan',type=Path,default=ROOT/'profiles/cleanup-conservative.json');clean.add_argument('--apply',action='store_true')
    commands.add_parser('restore')
    power=commands.add_parser('keep-awake');power.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    tv=Television(args.device,args.serial)
    try:
        if ':' in args.device: execute(['adb','connect',args.device])
        if args.command=='inspect':
            identity={key:tv.shell('getprop',key) for key in ['ro.serialno','ro.product.manufacturer','ro.product.model','ro.hardware','ro.build.version.release','ro.build.version.sdk']}
            identity['hardwareSerial']=tv.identity()
            identity['home']=tv.home();identity['leanback']=tv.shell('pm','has-feature','android.software.leanback')
            destination=args.state.parent/'inspection.json';atomic_save(destination,identity)
            (destination.parent/'packages.txt').write_text(tv.shell('pm','list','packages')+'\n', encoding='utf-8')
            (destination.parent/'disabled-packages.txt').write_text(tv.shell('pm','list','packages','-d')+'\n', encoding='utf-8')
            print(json.dumps(identity,ensure_ascii=False,indent=2));return
        tv.verify_tv()
        if args.command=='cleanup':
            cleanup(tv,args.state,json.loads(args.plan.read_text(encoding='utf-8')),args.apply);return
        if args.command=='keep-awake' and not args.apply:
            print('Preview only: disable idle sleep while powered. Use --apply only if requested.');return
        if args.command=='restore' and not args.state.is_file():
            raise RuntimeError('A saved state file is required for restoration')
        state=load_state(tv,args.state)
        if args.command=='install':
            inspect_apk(args.apk)
            if 'Success' not in tv.adb('install','-r',args.apk):raise RuntimeError('Install failed')
            started=tv.shell('am','start','-W','-n',HOME_COMPONENT)
            if 'Status: ok' not in started:raise RuntimeError(started)
            print('Installed and opened. Default Home is unchanged; verify the screen before make-home.')
        elif args.command=='make-home':
            if APP_ID in state['previousHome']:raise RuntimeError('No independent previous Home saved; stop and resolve a recovery target')
            if not tv.shell('pm','path',APP_ID).startswith('package:'):raise RuntimeError('Install and verify Custom TV first')
            previous=tv.home()
            try:
                tv.shell('cmd','package','set-home-activity','--user','0',HOME_COMPONENT)
                if not tv.home().startswith(APP_ID+'/'):raise RuntimeError('Default Home verification failed')
            except (RuntimeError, OSError, subprocess.SubprocessError):
                tv.shell('cmd','package','set-home-activity','--user','0',previous)
                raise
            tv.shell('input','keyevent','KEYCODE_HOME');print('Custom TV is the default Home.')
        elif args.command=='keep-awake':
            for table,key in [('secure','sleep_timeout'),('secure','attentive_timeout'),('global','stay_on_while_plugged_in')]:
                state['power'].setdefault(table+':'+key,tv.shell('settings','get',table,key))
            atomic_save(args.state,state)
            tv.shell('settings','put','secure','sleep_timeout','-1');tv.shell('settings','put','secure','attentive_timeout','-1');tv.shell('svc','power','stayon','true')
            for table,key,expected in [('secure','sleep_timeout','-1'),('secure','attentive_timeout','-1'),('global','stay_on_while_plugged_in','7')]:
                if tv.shell('settings','get',table,key)!=expected:raise RuntimeError('Power setting verification failed: '+key)
            print('Idle sleep disabled while powered; power button remains available.')
        elif args.command=='restore':
            for package,old in state['packages'].items():
                action={0:'default-state',1:'enable',2:'disable',3:'disable-user',4:'disable-until-used'}[old]
                tv.shell('pm',action,'--user','0',package)
                if tv.package_state(package)!=old:raise RuntimeError('Restore verification failed: '+package)
            for name,value in state['power'].items():
                table,key=name.split(':',1)
                tv.shell('settings','delete',table,key) if value=='null' else tv.shell('settings','put',table,key,value)
            previous=state['previousHome']
            if not COMPONENT.fullmatch(previous):raise RuntimeError('Invalid saved Home')
            tv.shell('cmd','package','set-home-activity','--user','0',previous)
            if tv.home()!=previous:raise RuntimeError('Home restoration failed')
            tv.shell('am','start','-W','-n',previous);print('Recorded settings and Home restored. No applications uninstalled.')
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
        parser.exit(1,str(error)+'\n')

if __name__=='__main__':main()
