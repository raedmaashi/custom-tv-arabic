#!/usr/bin/env python3
"""Import locally obtained Thmanyah Display files into a private build; never download or redistribute them."""
import argparse
from pathlib import Path
import shutil
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,required=True,help='Files personally downloaded from https://font.thmanyah.com')
args=parser.parse_args()
css=[]
files=[]
for name,weight in [('Regular',400),('Medium',500),('Bold',700)]:
    candidates=list(args.directory.rglob('thmanyahserifdisplay-'+name+'.woff2'))
    if len(candidates)!=1:parser.error('Provide a directory with exactly one '+name+' WOFF2')
    files.append((candidates[0],weight))
destination=ROOT/'app/src/main/assets/site/fonts';destination.mkdir(parents=True,exist_ok=True)
for file,weight in files:
    shutil.copy2(file,destination/file.name)
    css.append("@font-face { font-family: 'Local Display'; src: url('../fonts/"+file.name+"') format('woff2'); font-weight: "+str(weight)+"; font-display: swap; }")
css.append(":root { --font: 'Local Display', Arial, sans-serif; }")
p=ROOT/'app/src/main/assets/site/css/personal.css'
s=p.read_text(encoding='utf-8');marker='/* PRIVATE LOCAL FONT */'
if marker in s:s=s.split(marker)[0]
p.write_text(s+'\n'+marker+'\n'+'\n'.join(css)+'\n', encoding='utf-8')
print('Imported for this local build. Raw font files are gitignored. Do not publish an APK containing them without permission.')
