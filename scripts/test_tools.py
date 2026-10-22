"""Device safety and profile regressions; no ADB commands are executed."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import configure
import tv

class ToolTests(unittest.TestCase):
    def setUp(self):
        self.profile=json.loads((configure.ROOT/'profiles/example.json').read_text(encoding='utf-8'))

    def test_official_feed_cannot_be_reused_for_another_club(self):
        self.profile['team']['name']='النصر'
        with self.assertRaises(ValueError): configure.validate(self.profile)

    def test_other_team_and_timezone_generate_consistent_sources(self):
        self.profile['city'].update(name='London',latitude=51.5072,longitude=-0.1276,timezone='Europe/London')
        self.profile['team'].update(name='Liverpool',provider='espn',id='364',leagues=['eng.1'])
        self.profile['panels']['showWeather']=False
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for target in ['app/src/main/java/tv/customhome/launcher/dashboard','app/src/main/assets/site/js']:
                (root/target).mkdir(parents=True)
            result=configure.generate(self.profile,root)
            java=(root/'app/src/main/java/tv/customhome/launcher/dashboard/DashboardProfile.java').read_text(encoding='utf-8')
            self.assertIn('Europe/London',java)
            self.assertIn('"364"',java)
            self.assertFalse(result['defaults']['showWeather'])
            self.assertEqual(result['favoriteTeam']['name'],'Liverpool')

    def test_bad_coordinates_and_feed_paths_are_rejected(self):
        for value in [float('nan'),91,float('inf')]:
            p=copy.deepcopy(self.profile);p['city']['latitude']=value
            with self.assertRaises(ValueError): configure.validate(p)
        p=copy.deepcopy(self.profile);p['team'].update(provider='espn',id='817',leagues=['../bad'])
        with self.assertRaises(ValueError): configure.validate(p)

    def test_wrong_hardware_never_mutates(self):
        commands=[]
        def run(args):
            commands.append(args)
            return 'ACTUAL'
        device=tv.Television('TV:5555','OTHER',run)
        with self.assertRaises(RuntimeError):device.verify_tv()
        self.assertEqual(len(commands),1)
        self.assertIn('getprop',commands[0][-1])

    def test_core_home_and_selected_apps_are_protected(self):
        for package in ['android','com.android.systemui','com.android.providers.media','com.vendor.tvinput.service','com.vendor.home','com.netflix.ninja']:
            with self.assertRaises(ValueError):
                tv.reject_critical(package,tv.PROTECTED|{'com.vendor.home','com.netflix.ninja'})

    def test_different_device_backup_is_rejected(self):
        class Fake:
            def identity(self,mutate=False):return 'OTHER'
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'state.json';tv.atomic_save(path,{'serial':'ORIGINAL'})
            with self.assertRaises(RuntimeError):tv.load_state(Fake(),path)

    def test_cleanup_journals_original_state_before_mutation(self):
        class Fake:
            state=0
            def verify_tv(self):pass
            def identity(self,mutate=False):return 'EXPECTED'
            def home(self):return 'com.vendor.home/.Home'
            def protected_packages(self):return tv.PROTECTED
            def package_state(self,p):return self.state
            def shell(self,*args):
                if args==('pm','list','packages'):return 'package:com.vendor.recommendations'
                if args[:2]==('pm','disable-user'):
                    saved=json.loads(path.read_text(encoding='utf-8'))
                    assert saved['packages']['com.vendor.recommendations']==0
                    assert saved['journal'][-1]['status']=='planned'
                    self.state=3
                return ''
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'state.json';device=Fake()
            plan={'packages':[{'package':'com.vendor.recommendations','reason':'test'}]}
            tv.cleanup(device,path,plan,apply=False)
            self.assertFalse(path.exists());self.assertEqual(device.state,0)
            tv.cleanup(device,path,plan,apply=True)
            self.assertEqual(json.loads(path.read_text(encoding='utf-8'))['journal'][-1]['status'],'done')
            self.assertEqual(device.state,3)

    def test_install_preserves_home_when_android_clears_preference(self):
        class Fake:
            current='com.vendor.home/.Home'
            def home(self):return self.current
            def adb(self,*args):
                self.current='android/com.android.internal.app.ResolverActivity'
                return 'Success'
            def shell(self,*args):
                if args[:3]==('cmd','package','set-home-activity'):
                    self.current=args[-1]
                    return 'Success'
                return 'Status: ok'
        device=Fake()
        with patch.object(tv,'inspect_apk'):
            tv.install_application(device,Path('test.apk'))
        self.assertEqual(device.current,'com.vendor.home/.Home')

    def test_install_cli_reaches_the_safe_install_flow(self):
        with patch('sys.argv',['tv.py','--device','USB_DEVICE','--serial','EXPECTED','install','--apk','test.apk']), \
                patch.object(tv,'Television'), patch.object(tv,'load_state'), \
                patch.object(tv,'install_application') as install_action:
            tv.main()
            install_action.assert_called_once()

if __name__=='__main__':unittest.main()
