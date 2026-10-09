"""Combine public read speech with deterministic synthetic diagnostic variants, never microphone audio."""
import pathlib,json,shutil,hashlib,argparse
import numpy as np
import soundfile as sf
parser=argparse.ArgumentParser();parser.add_argument('--public',default='target/voice-phase5-unique-fixtures');parser.add_argument('--synthetic',default='target/voice-phase5-fixtures');parser.add_argument('--output',default='target/voice-phase5-final-suite');args=parser.parse_args()
source=pathlib.Path(args.public);synthetic_source=pathlib.Path(args.synthetic);root=pathlib.Path(args.output);root.mkdir(parents=True,exist_ok=True)
public=json.loads((source/'fixtures.json').read_text(encoding='utf8'))
items=public['fixtures'].copy()
for item in items:shutil.copyfile(source/item['file'],root/item['file'])
synthetic=json.loads((synthetic_source/'synthetic.json').read_text(encoding='utf-8-sig'))
for item in synthetic[:4]:shutil.copyfile(synthetic_source/item['file'],root/item['file']);items.append(item)
a,rate=sf.read(synthetic_source/'synthetic-first.wav',dtype='float32');b,rate2=sf.read(synthetic_source/'synthetic-second.wav',dtype='float32');assert rate==rate2==16000
for gap in [600,900,1500]:
    name='synthetic-pause-'+str(gap)+'.wav';samples=np.concatenate([a,np.zeros(gap*16,dtype='float32'),b]);sf.write(root/name,samples,16000,subtype='PCM_16')
    items.append(dict(file=name,reference=synthetic[4]['reference']+synthetic[5]['reference'],category='synthetic-thought-pause-'+str(gap),source='Haruka concatenation with fixed gap; not spontaneous speech'))
clean,rate=sf.read(synthetic_source/'synthetic-short.wav',dtype='float32');rng=np.random.default_rng(20261010)
for name,samples,reference,category in [('synthetic-background.wav',np.clip(clean+rng.normal(0,.01,len(clean)),-1,1),synthetic[0]['reference'],'synthetic-background-white-noise'),('silence.wav',np.zeros(48000,dtype='float32'),'','silence'),('noise.wav',rng.normal(0,.01,48000),'','white-noise')]:
    sf.write(root/name,samples,16000,subtype='PCM_16');items.append(dict(file=name,reference=reference,category=category,source='deterministic synthetic; seed 20261010'))
for item in items:
    samples,rate=sf.read(root/item['file']);assert rate==16000 and samples.ndim==1
    item['seconds']=len(samples)/rate;item['sha256']=hashlib.sha256((root/item['file']).read_bytes()).hexdigest()
(root/'fixtures.json').write_text(json.dumps(dict(public_source=public,fixtures=items),ensure_ascii=False,indent=2),encoding='utf8')
(root/'fixtures.tsv').write_text('\n'.join(x['file']+'\t'+x['reference'] for x in items)+'\n',encoding='utf8')
print('Suite',len(items),'clips',sum(i['seconds'] for i in items),'seconds')