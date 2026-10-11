"""No credentials/inference: Windows 0.162.1 MXC and local tool-advertisement fixture.

python docs/probes/codex_app_server_windows.py C:/absolute/path/codex.exe
Requires native MXC support. Never configures the normal Codex home or OS policy.
The sandbox diagnostic additionally grants read access to this Python runtime.
"""
import pathlib, os, json, subprocess, threading, queue, http.server, time, sys, tempfile

if os.name != 'nt' or len(sys.argv) != 2:
    raise SystemExit('Usage on Windows: python docs/probes/codex_app_server_windows.py C:/absolute/codex.exe')
root = pathlib.Path(__file__).resolve().parents[2]
exe = pathlib.Path(sys.argv[1])
if not exe.is_absolute() or not exe.is_file() or exe.suffix.lower() != '.exe':
    raise SystemExit('Absolute native .exe required')
(root/'target').mkdir(exist_ok=True)
home = pathlib.Path(tempfile.mkdtemp(prefix='windows-mxc-probe-', dir=root/'target'))
workspace = home/'workspace'; workspace.mkdir()
platform_keys = ['SystemRoot','SystemDrive','WINDIR','ProgramFiles','ProgramFiles(x86)','ProgramW6432','ProgramData','ALLUSERSPROFILE']
env = {k:os.environ[k] for k in platform_keys if k in os.environ}
env.update({k:str(home) for k in ['CODEX_HOME','HOME','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP']})
env['PATH'] = os.environ['SystemRoot']+'/System32'
env['ComSpec'] = os.environ['SystemRoot']+'/System32/cmd.exe'
version = subprocess.check_output([str(exe),'--version'], env=env, text=True, timeout=10)
assert version.strip() == 'codex-cli 0.162.1'
source = (root/'src/main/java/dev/mikoto2000/rei/chatgpt/WslPlanAppServerLauncher.java').read_text()
config = '\n'.join(line.strip() for line in source.split('return """',1)[1].split('""";',1)[0].splitlines())
windows_source = (root/'src/main/java/dev/mikoto2000/rei/chatgpt/WindowsPlanAppServerLauncher.java').read_text()
disabled = '\n'.join(line.strip() for line in windows_source.split('DISABLED_PLUGINS = """',1)[1].split('""";',1)[0].splitlines())+'\n'
config = config.replace('[permissions.rei-read.filesystem]',disabled+'[permissions.rei-read.filesystem]')
config = config+'\n[windows]\nsandbox = "mxc"\n'
config = config.replace('[permissions.rei-read.network]',json.dumps(exe.as_posix())+' = "read"\n[permissions.rei-read.network]')
config = config.replace('inherit = "none"','inherit = "all"\ninclude_only = '+json.dumps(platform_keys+['PATH','ComSpec','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA']))
requested = threading.Event(); seen = []
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.headers.get('Authorization') == 'Bearer dummy-local-fixture-token'
        seen.append([{'type':t.get('type'),'name':t.get('name')} for t in body.get('tools',[])])
        requested.set()
        data = b'{"error":{"message":"Local fixture refuses inference","type":"invalid_request_error"}}'
        self.send_response(400); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self,*args): pass
server = http.server.HTTPServer(('127.0.0.1',0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
config = config.replace('https://api.openai.com/v1',f'http://127.0.0.1:{server.server_port}/v1')
(home/'config.toml').write_text(config)
env['REI_PLAN_ACCESS_TOKEN'] = 'dummy-local-fixture-token'
process = subprocess.Popen([str(exe),'app-server','--listen','stdio://'],cwd=workspace,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf8',creationflags=subprocess.CREATE_NO_WINDOW)
incoming = queue.Queue(maxsize=100)
def read():
    for line in process.stdout: incoming.put(json.loads(line))
def discard():
    while process.stderr.read(4096): pass
threading.Thread(target=read,daemon=True).start(); threading.Thread(target=discard,daemon=True).start()
def send(value): process.stdin.write(json.dumps(value)+'\n'); process.stdin.flush()
def request(i,method,params):
    send({'id':i,'method':method,'params':params}); deadline=time.monotonic()+20
    for _ in range(100):
        value=incoming.get(timeout=max(.001,deadline-time.monotonic()))
        if value.get('id')==i:
            assert 'error' not in value, value
            return value['result']
    raise RuntimeError('Protocol limit')
try:
    init=request(1,'initialize',{'clientInfo':{'name':'rei','version':'1.0'},'capabilities':{'experimentalApi':True}})
    assert init['platformOs']=='windows' and init['userAgent'].startswith('rei/0.162.1 ')
    send({'method':'initialized','params':{}})
    thread=request(2,'thread/start',{'cwd':str(workspace),'model':'gpt-5.3-codex','modelProvider':'rei_plan','permissions':'rei-read','ephemeral':True,'approvalPolicy':'untrusted','approvalsReviewer':'user','dynamicTools':[{'type':'function','name':'rei_read_source','description':'Read approved snapshot only','inputSchema':{'type':'object','properties':{'path':{'type':'string'}},'required':['path'],'additionalProperties':False}}]})
    assert thread['activePermissionProfile']['id']=='rei-read' and thread['sandbox']=={'type':'readOnly','networkAccess':False} and thread['instructionSources']==[]
    request(3,'turn/start',{'threadId':thread['thread']['id'],'input':[{'type':'text','text':'Return one JSON review','text_elements':[]}]})
    assert requested.wait(20)
    assert all(t['name'] in ('request_user_input','rei_read_source') for call in seen for t in call), seen
    print('restricted_thread_created=true'); print('local_fixture_tools='+json.dumps(seen))
finally:
    process.stdin.close()
    try: process.wait(timeout=5)
    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    server.shutdown()
env.pop('REI_PLAN_ACCESS_TOKEN',None)
(workspace/'approved.txt').write_text('approved sentinel'); (home/'outside.txt').write_text('outside sentinel')
config=config.replace('[permissions.rei-read.network]',json.dumps(pathlib.Path(sys.executable).parent.as_posix())+' = "read"\n[permissions.rei-read.network]')
(home/'config.toml').write_text(config)
code='''import pathlib,socket,json
r={}
r['allowedRead']=pathlib.Path('approved.txt').read_text()=='approved sentinel'
try:pathlib.Path('../outside.txt').read_text();r['outsideReadDenied']=False
except (PermissionError,FileNotFoundError):r['outsideReadDenied']=True
try:pathlib.Path('forbidden.txt').write_text('blocked');r['writeDenied']=False
except OSError:r['writeDenied']=True
s=None
try:s=socket.socket();s.settimeout(1);s.connect(('1.1.1.1',80));r['networkDenied']=False
except OSError as e:r['networkDenied']=e.winerror==10013
finally:
 if s:s.close()
print(json.dumps(r));assert all(r.values())
'''
proof=subprocess.run([str(exe),'sandbox','--permission-profile','rei-read','--',sys.executable,'-I','-c',code],cwd=workspace,env=env,capture_output=True,text=True,timeout=25,creationflags=subprocess.CREATE_NO_WINDOW)
print('boundary_exit='+str(proof.returncode)); print('boundary_result='+proof.stdout.strip())
assert proof.returncode==0, 'Native boundary probe failed'
