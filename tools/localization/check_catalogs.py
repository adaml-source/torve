"""Check complete locale coverage and formatter contracts without network/build dependencies."""
from pathlib import Path
import argparse,collections,json,re,sys,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
LANGUAGES=('en','de','es','fr','it','pt','tr')
FORMAT=re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?(lld|ld|@|[bBhHsScCdoxXeEfgGaA])')
def signature(value):
    index=0;result=[]
    for match in FORMAT.finditer(value.replace('%%','')):
        if match[1]:position=int(match[1])
        else:index+=1;position=index
        conversion=match[2]
        kind='string' if conversion in ['s','S','@'] else 'float' if conversion in ['f','e','E','g','G','a','A'] else 'integer' if conversion in ['d','o','x','X','ld','lld'] else conversion.lower()
        result.append((position,kind))
    return sorted(result)
def android(part,language):
    rows={}
    for path in sorted((ROOT/'androidApp/src'/part/'res'/('values' if language=='en' else 'values-'+language)).glob('*.xml')):
        for node in ET.parse(path).getroot().findall('string'):
            if node.get('translatable')=='false':continue
            key=node.attrib['name'];value=''.join(node.itertext())
            if key in rows:raise ValueError('Duplicate resource '+str(path)+': '+key)
            rows[key]=value
    return rows
def apple(language):
    path=ROOT/'iosApp/appleShared/Resources'/(language+'.lproj')/'Localizable.strings'
    pairs=re.findall(r'^"((?:\\.|[^"\\])*)"\s*=\s*"((?:\\.|[^"\\])*)";',path.read_text(encoding='utf-8'),re.M)
    result={}
    for key,value in pairs:
        key=json.loads('"'+key+'"');value=json.loads('"'+value+'"')
        if key in result:raise ValueError('Duplicate Apple resource: '+key)
        result[key]=value
    return result
def check():
    errors=[];coverage={}
    for name,reader in [('android-main',lambda l:android('main',l)),('android-tv',lambda l:android('tv',l)),('apple',apple)]:
        english=reader('en');coverage[name]={'en':len(english)}
        for language in LANGUAGES[1:]:
            rows=reader(language);coverage[name][language]=len(rows)
            for key,value in english.items():
                if key not in rows:errors.append([name,language,key,'missing'])
                elif signature(value)!=signature(rows[key]):errors.append([name,language,key,'formatter arguments differ',value,rows[key]])
                elif FORMAT.sub('',value).replace('%%','%').count('%')!=FORMAT.sub('',rows[key]).replace('%%','%').count('%'):
                    errors.append([name,language,key,'literal percentage signs differ',value,rows[key]])
    return {'languages':LANGUAGES,'coverage':coverage,'errors':errors,'passed':not errors}
if __name__=='__main__':
    args=argparse.ArgumentParser();args.add_argument('--report',type=Path);options=args.parse_args();result=check()
    if options.report:options.report.parent.mkdir(parents=True,exist_ok=True);options.report.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True,indent=2));raise SystemExit(0 if result['passed'] else 1)
