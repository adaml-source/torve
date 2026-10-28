"""Compare compiler-emitted SwiftUI keys against every shipping language bundle."""
from pathlib import Path
import argparse,json
from check_catalogs import ROOT,LANGUAGES,apple
p=argparse.ArgumentParser();p.add_argument('stringsdata',type=Path);p.add_argument('--report',type=Path);a=p.parse_args();keys=set();files=0
for path in a.stringsdata.rglob('*.stringsdata'):
    record=json.loads(path.read_text(encoding='utf-8'));source=record.get('source','')
    if '/iosApp/' not in source:continue
    local=ROOT/'iosApp'/source.split('/iosApp/',1)[1]
    if not local.exists() or local.name.endswith(('Review.swift','Fixture.swift')):continue
    if local.read_text(encoding='utf-8').lstrip().startswith('#if DEBUG'):continue
    keys.update(r['key'] for r in record.get('tables',{}).get('Localizable',[]) if r['key']);files+=1
result={'productionCompilerFiles':files,'productionCompilerKeys':len(keys),'missing':{l:sorted(keys-apple(l).keys()) for l in LANGUAGES}}
result['passed']=bool(files) and not any(result['missing'].values())
if a.report:a.report.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=True,indent=2));raise SystemExit(0 if result['passed'] else 1)
