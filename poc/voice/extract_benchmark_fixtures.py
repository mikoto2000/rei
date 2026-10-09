"""Fixed google/fleurs Japanese validation subset, CC-BY-4.0; no microphone.
Requires pyarrow and soundfile. Conversion preserves separate recordings of one sentence.
"""
import pathlib, hashlib, json, io, argparse
import pyarrow.parquet as pq
import soundfile as sf
REVISION='3dfbbb3b3cfb7d48550c24defda9876bbb73f8ac'
DATASET_SHA='4636065a2eb7d1bb07007d3193092eb1856cf101360831c61d6dfa611e579071'
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def extract_rows(rows,root,limit):
    if not 1<=limit<=200:raise ValueError('Invalid fixture count')
    root.mkdir(parents=True,exist_ok=True);selected=[]
    for index,row in enumerate(rows):
        original=row['audio']['bytes'];audio,rate=sf.read(io.BytesIO(original),dtype='float32')
        if rate!=16000 or audio.ndim!=1:raise ValueError('Unexpected public audio format')
        seconds=len(audio)/16000
        if not 3<=seconds<=18:continue
        name=f"fleurs-{index:04d}-{row['id']}.wav";dest=root/name
        sf.write(dest,audio,rate,subtype='PCM_16')
        selected.append(dict(id=str(row['id']),row_index=index,file=name,reference=row['raw_transcription'],seconds=seconds,sha256=digest(dest),original_sha256=hashlib.sha256(original).hexdigest(),category='public-read-japanese'))
        if len(selected)==limit:break
    return selected
def main():
    parser=argparse.ArgumentParser();parser.add_argument('parquet');parser.add_argument('output');args=parser.parse_args()
    source=pathlib.Path(args.parquet);root=pathlib.Path(args.output)
    if digest(source)!=DATASET_SHA:raise ValueError('Dataset SHA mismatch')
    selected=extract_rows(pq.read_table(source).to_pylist(),root,20)
    (root/'fixtures.json').write_text(json.dumps(dict(source='google/fleurs',revision=REVISION,split='validation',license='CC-BY-4.0',selection='first 20 rows with 3-18 second mono 16kHz; float WAV converted to PCM16; row index makes recordings unique',fixtures=selected),ensure_ascii=False,indent=2),encoding='utf8')
    (root/'fixtures.tsv').write_text('\n'.join(x['file']+'\t'+x['reference'] for x in selected)+'\n',encoding='utf8')
    print('Extracted',len(selected),'distinct recordings; seconds=',sum(x['seconds'] for x in selected))
if __name__=='__main__':main()