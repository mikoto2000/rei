import pathlib, os, json, subprocess, threading, queue, http.server, time, sys, tempfile
root=pathlib.Path(__file__).resolve().parents[2]
if len(sys.argv)!=2: raise SystemExit('Usage (inside WSL): python3 docs/probes/codex_app_server_wsl.py /absolute/codex-0.154.0')
exe=str(pathlib.Path(sys.argv[1]).resolve())
assert subprocess.check_output([exe,'--version'],text=True).strip()=='codex-cli 0.154.0'
(root/'target').mkdir(exist_ok=True)
home=pathlib.Path(tempfile.mkdtemp(prefix='wsl-boundary-',dir=root/'target'));workspace=home/'workspace';workspace.mkdir()
config=(root/'src/main/java/dev/mikoto2000/rei/chatgpt/WslPlanAppServerLauncher.java').read_text().split('return """',1)[1].split('""";',1)[0]
config='\n'.join(line.strip() for line in config.splitlines())

config=config.replace('[permissions.rei-read.network]',f'"{exe}" = "read"\n[permissions.rei-read.network]')
requested=threading.Event(); seen=[]
class Handler(http.server.BaseHTTPRequestHandler):
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  seen.append({'path':self.path,'tools':[{'type':t.get('type'),'name':t.get('name'),'namespace':t.get('namespace')} for t in body.get('tools',[])]})
  requested.set();data=b'{"error":{"message":"Local protocol fixture refuses inference","type":"invalid_request_error"}}'
  self.send_response(400);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def log_message(self,*a):pass
server=http.server.HTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
config=config.replace('https://api.openai.com/v1',f'http://127.0.0.1:{server.server_port}/v1')
(home/'config.toml').write_text(config)

env={'PATH':'/usr/bin:/bin','HOME':str(home),'CODEX_HOME':str(home),'REI_PLAN_ACCESS_TOKEN':'dummy-local-fixture-token'}
p=subprocess.Popen([exe,'app-server','--listen','stdio://'],cwd=workspace,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
q=queue.Queue()
def read():
 for line in p.stdout:q.put(json.loads(line))
def discard():
 for line in p.stderr:pass
threading.Thread(target=read,daemon=True).start();threading.Thread(target=discard,daemon=True).start()
def send(o):p.stdin.write(json.dumps(o)+'\n');p.stdin.flush()
def req(i,m,params):
 send({'id':i,'method':m,'params':params});deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  try:v=q.get(timeout=1)
  except queue.Empty:continue
  if v.get('id')==i:return v
 raise RuntimeError('Protocol deadline')
try:
 print('initialize='+json.dumps(req(1,'initialize',{'clientInfo':{'name':'rei','version':'1.0'},'capabilities':{'experimentalApi':True}})))
 send({'method':'initialized','params':{}})
 thread=req(2,'thread/start',{'cwd':str(workspace),'model':'gpt-5.3-codex','modelProvider':'rei_plan','permissions':'rei-read','ephemeral':True,'approvalPolicy':'untrusted','approvalsReviewer':'user','dynamicTools':[{'type':'function','name':'rei_read_source','description':'Read approved snapshot only','inputSchema':{'type':'object','properties':{'path':{'type':'string'}},'required':['path'],'additionalProperties':False}}]})
 if 'error' in thread:raise RuntimeError(json.dumps(thread))
 print('restricted_thread_created=true')
 tid=thread['result']['thread']['id']
 turn=req(3,'turn/start',{'threadId':tid,'input':[{'type':'text','text':'Return one JSON review','text_elements':[]}]})
 print('turn_accepted='+str('result' in turn))
 assert requested.wait(20)
 print('local_fixture_requests='+json.dumps(seen))
 assert all(t.get('name') in ('request_user_input','rei_read_source') for request in seen for t in request['tools'])
finally:
 p.stdin.close()
 try:p.wait(timeout=5)
 except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
 server.shutdown();print('process_exit='+str(p.returncode))

(workspace/'approved.txt').write_text('approved sentinel')
(home/'outside.txt').write_text('outside sentinel, not a secret')
probe_code='''import pathlib,socket,json
result={}
result['allowed_read']=pathlib.Path('approved.txt').read_text()=='approved sentinel'
try:pathlib.Path('../outside.txt').read_text();result['outside_read_denied']=False
except (PermissionError,FileNotFoundError):result['outside_read_denied']=True
try:pathlib.Path('forbidden-write.txt').write_text('blocked');result['write_denied']=False
except (PermissionError,OSError):result['write_denied']=True
s=None
try:s=socket.socket();s.settimeout(1);s.connect(('127.0.0.1',PORT));result['network_denied']=False
except OSError:result['network_denied']=True
finally:
 if s:s.close()
print(json.dumps(result))
assert all(result.values())
'''.replace('PORT',str(server.server_port))
env.pop('REI_PLAN_ACCESS_TOKEN',None)
proof=subprocess.run([exe,'sandbox','--permission-profile','rei-read','--','/usr/bin/python3','-c',probe_code],cwd=workspace,env=env,capture_output=True,text=True,timeout=20)
print('boundary_exit='+str(proof.returncode));print('boundary_result='+proof.stdout.strip())
if proof.returncode:print('boundary_error='+proof.stderr[-500:])
assert proof.returncode==0
