"""Bundle small public artwork thumbnails for the reviewed artists; no NovelAI calls."""
import hashlib, json, pathlib, re, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT=pathlib.Path(__file__).resolve().parents[1]
TARGET=ROOT/'app/src/main/assets/artist-lab'
CACHE=ROOT/'.tooling/artist-lab/previews'
CACHE.mkdir(parents=True,exist_ok=True)
(TARGET/'previews').mkdir(exist_ok=True)
review=json.loads((TARGET/'curated-artists.json').read_text(encoding='utf-8'))
def fetch(url):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':'https://www.pixiv.net/','Accept':'application/json,image/*'})
 return urllib.request.urlopen(req,timeout=25).read()
def get_json(url,key):
 path=CACHE/(key+'.json')
 if path.exists():return json.loads(path.read_text(encoding='utf-8'))
 data=json.loads(fetch(url));path.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8');return data
cached_works={}
for path in [ROOT/'.tooling/modern-artist-review/expanded/candidates.json',ROOT/'.tooling/modern-artist-review/painting/visual-candidates.json']:
 if not path.exists():continue
 for a in json.loads(path.read_text(encoding='utf-8')):
  for w in a.get('works',[]):
   cached_works[str(w['id'])]={'id':str(w['id']),'userId':w.get('user_id'),'xRestrict':w.get('rating',w.get('x_restrict')),
    'aiType':w.get('ai',w.get('ai_type')),'url':w.get('thumbnail'),'title':w.get('title','')}
skip_title=re.compile(r'R.?18|nsfw|hentai|bunny|バニー|水着|泳装|泳裝|おっぱい|裸|下着|bikini|swimsuit|sexy|succubus|サキュバス|お品書|お品書き|告知|通販|commission',re.I)
def acceptable(work,uid):
 return str(work.get('userId'))==uid and work.get('xRestrict')==0 and work.get('aiType')!=2 and not skip_title.search(work.get('title',''))
def thumb(work):
 url=work.get('url')
 if not url:
  urls=work.get('urls') or {};url=urls.get('small') or urls.get('thumb_mini')
 if not url or not url.startswith('https://i.pximg.net/'):return None
 # 只请求服务端 250px 预览，不下载原分辨率作品。
 return re.sub(r'/c/[^/]+/','/c/250x250_80_a2/',url)
def build(artist):
 uid=str(artist['pixiv_id']);tag='artist: '+artist['danbooru_tag'].replace('_',' ')
 ids=[]
 for url in artist['visual_review']['sample_urls']:
  match=re.search(r'/artworks/(\d+)',url)
  if match:ids.append(match[1])
 previews=[];used=set()
 def add(work):
  if len(previews)>=2 or str(work['id']) in used or not acceptable(work,uid):return
  url=thumb(work)
  if not url:return
  stem=hashlib.sha256(tag.encode()).hexdigest()[:16]+'-'+str(len(previews))+'.jpg'
  path=TARGET/'previews'/stem
  data=fetch(url)
  assert data[:2]==b'\xff\xd8' or data[:4]==b'RIFF', 'Unexpected preview format'
  assert len(data)<180_000, 'Preview too large'
  path.write_bytes(data);used.add(str(work['id']))
  previews.append({'asset':stem,'sourceUrl':'https://www.pixiv.net/artworks/'+str(work['id'])})
 for wid in ids:
  work=cached_works.get(wid)
  if not work:
   try:work=get_json('https://www.pixiv.net/ajax/illust/'+wid,'work-'+wid)['body']
   except Exception:continue
  try:add(work)
  except Exception:continue
 if len(previews)<2:
  body=get_json(f'https://www.pixiv.net/ajax/user/{uid}/profile/top','top-'+uid)['body']
  for field in ['illusts','manga']:
   works=body.get(field,{})
   for work in (works.values() if isinstance(works,dict) else works):
    try:add(work)
    except Exception:continue
    if len(previews)>=2:break
 assert previews, 'No public representative thumbnail: '+tag
 return {'tag':tag,'name':artist['display_name'],'previews':previews}
entries={}
with ThreadPoolExecutor(max_workers=3) as pool:
 futures={pool.submit(build,a):a['danbooru_tag'] for a in review['artists']}
 for f in as_completed(futures):
  entry=f.result();entries[entry['tag']]=entry
  print('preview',futures[f],len(entry['previews']),flush=True)
ordered=[entries['artist: '+a['danbooru_tag'].replace('_',' ')] for a in review['artists']]
raw=(json.dumps(ordered,ensure_ascii=False,indent=2)+'\n').encode()
(TARGET/'previews.json').write_bytes(raw)
source=json.loads((TARGET/'source.json').read_text(encoding='utf-8'))
source['previews_sha256']=hashlib.sha256(raw).hexdigest()
source['preview_images']=sum(len(a['previews']) for a in ordered)
(TARGET/'source.json').write_text(json.dumps(source,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'artists':len(ordered),'images':source['preview_images'],'bytes':sum(p.stat().st_size for p in (TARGET/'previews').glob('*.jpg'))}))
