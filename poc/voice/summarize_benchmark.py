"""Aggregate benchmark TSV without combining public CER and synthetic diagnostics."""
import pathlib,csv,json,base64,math,statistics,sys,unicodedata
root=pathlib.Path('target'); output=[]
for path in sorted(list(root.glob('benchmark-main-*-t*-p*-s*.tsv'))+list(root.glob('benchmark-calibration-*-t*-p*-s*.tsv'))+list(root.glob('benchmark-tuned-*-t*-p*-s*.tsv'))+list(root.glob('benchmark-commands-*-t*-p*-s*.tsv'))+list(root.glob('benchmark-utf8*-*-t*-p*-s*.tsv'))):
    rows=list(csv.DictReader(path.open(encoding='utf8'),delimiter='\t'))
    if not rows:continue
    process=path.with_suffix('.process.json')
    if not process.exists():continue
    meta=json.loads(process.read_text(encoding='utf-8-sig'))
    def text(row,key):return base64.b64decode(row[key]).decode('utf8')
    for name,subset in [('public-read-japanese',[r for r in rows if r['file'].startswith('fleurs-')]),('synthetic',[r for r in rows if not r['file'].startswith('fleurs-')])]:
        if not subset:continue
        spoken=[r for r in subset if text(r,'reference64')]
        estimates=[float(r['endToAdmissionMs']) for r in spoken if math.isfinite(float(r['endToAdmissionMs']))]
        def normalized(s):return "".join(c for c in unicodedata.normalize("NFKC",s) if unicodedata.category(c)[0] not in "PZ" and not c.isspace())
        characterCount=sum(len(normalized(text(r,"reference64"))) for r in spoken)
        errorCount=sum(round(float(r["cer"])*len(normalized(text(r,"reference64")))) for r in spoken)
        latencies=sorted(float(r['asrMs']) for r in spoken)
        rtfs=[float(r['asrMs'])/(1000*float(r['audioSeconds'])) for r in spoken]
        def percentile(values,q):return sorted(values)[math.ceil(len(values)*q)-1] if values else None
        terms={"synthetic-technical.wav":["Java","Spring Boot","Whisper","Gateway","GitHub"],"synthetic-files.wav":["pom.xml","README.md","src","VoiceCommand.java"],"synthetic-commands.wav":["git status","mvn test","git diff"]}
        exactTerms=[(term,term in text(r,"hypothesis64")) for r in subset for term in terms.get(r["file"],[])]
        output.append(dict(byteSafe=all(r.get("byteSafe")=="true" for r in rows),exactTermsCorrect=sum(hit for term,hit in exactTerms),exactTermsTotal=len(exactTerms),label=path.name,threads=meta['threads'],tailFrames=meta['tail'],silenceMs=meta['silenceMs'],profile=rows[0]['profile'],group=name,clips=len(subset),spoken=len(spoken),corpusCer=errorCount/characterCount if characterCount else None,referenceCharacters=characterCount,characterErrors=errorCount,meanCer=statistics.mean(float(r['cer']) for r in spoken) if spoken else None,
            rtfMean=statistics.mean(rtfs) if rtfs else None,rtfP95=percentile(rtfs,.95),asrP50Ms=percentile(latencies,.5),asrP95Ms=percentile(latencies,.95),
            endToFilteredEstimateP50Ms=percentile(estimates,.5),endToFilteredEstimateP95Ms=percentile(estimates,.95),missingUtterances=sum(int(r['segments'])==0 for r in spoken),
            splitUtterances=sum(int(r['segments'])>1 for r in spoken),unintendedSends=sum(int(r['segments']) for r in subset if not text(r,'reference64')),
            cpuPerWall=statistics.mean(float(r['cpuMs'])/float(r['asrMs']) for r in spoken) if spoken else None,process=meta))
(root/'phase5-benchmark-summary.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf8')
for row in output:print(json.dumps({k:v for k,v in row.items() if k!='process'},ensure_ascii=False))