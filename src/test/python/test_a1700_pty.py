#!/usr/bin/env python3
"""Test harness only: Java CLI on a real Linux PTY with synthetic login and PMU payload fixtures.
PTYs cannot implement 7E1 electrically. The test stty shim verifies the requested 7E1 flags,
then configures only PTY-compatible raw mode. Real RS485/parity remain a hardware test.
"""
from pathlib import Path
import json, os, pty, select, shutil, signal, subprocess, tempfile, time

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / 'src/test/resources/a1700'

def frame(command, payload=None):
    body = command.encode() + (b'\x02' + payload.encode() if payload is not None else b'') + b'\x03'
    checksum=0
    for value in body: checksum ^= value
    return b'\x01' + body + bytes([checksum])

def steps_for(name):
    requests=[]
    for line in (FIXTURES/(name+'.tsv')).read_text().splitlines():
        if line.startswith('#'): continue
        _,op,register,argument,reply = line.split('\t')
        requests.append((frame(op,register+'('+argument+')'),bytes.fromhex(reply)))
    steps=[(b'/?005!\r\n',b'/GEC5090100301113@000\r\n'),
           (b'\x06051\r\n',frame('P0','(1020304050607080)')),
           (frame('P2','(0000000000000000)'),b'\x06')]
    steps+=requests[:2]
    steps.append((frame('P2','(99BBEE3287ED64EC)'),b'\x06'))
    steps+=requests[2:]
    steps.append((frame('B0'),b''))
    return steps

def test(mode):
    with tempfile.TemporaryDirectory(prefix='a1700-pty-') as temporary:
        temp=Path(temporary); master,slave=pty.openpty(); tty=os.ttyname(slave)
        key=temp/'synthetic.key';key.write_bytes(bytes.fromhex('0102030405060708'));key.chmod(0o600)
        shim=temp/'stty'; real_stty=shutil.which('stty')
        shim.write_text('#!/usr/bin/env python3\nimport os,sys\na=sys.argv[1:]\n'
                        'assert a[0]=="-F" and "cs7" in a and "parenb" in a and "-parodd" in a and "-echo" in a\n'
                        'a=["cs8" if x=="cs7" else "-parenb" if x=="parenb" else x for x in a]\n'
                        'os.execv('+repr(real_stty)+', ["stty"]+a)\n')
        shim.chmod(0o700)
        group='all' if mode=='all' else 'current'
        steps=[]
        for name in (['current','instantaneous','historical','profile-short'] if mode=='all' else ['current']): steps += steps_for(name)
        if mode!='all':
            index=next(i for i,(tx,_) in enumerate(steps) if b'507001(40)' in tx)
            steps=steps[:index+1]+[(frame('B0'),b'')]
            steps[index]=(steps[index][0],steps[index][1][:5])
        output=temp/'output'; stdout=temp/'stdout.txt'
        env=dict(os.environ,PATH=str(temp)+os.pathsep+os.environ['PATH'])
        cmd=['java','-cp',str(ROOT/'build/a1700-classes')+':'+str(ROOT/'build/libs-all/jrxtx-1.0.1.jar'),
             'org.openmuc.j62056.app.A1700Reader','--port',tty,'--key',str(key),'--group',group,
             '--output',str(output),'--delay-ms','0','--timeout-ms','1200','--signon-attempts','1']
        with stdout.open('w') as stream:
            process=subprocess.Popen(cmd,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
            try:
                for index,(tx,rx) in enumerate(steps):
                    got=b'';deadline=time.monotonic()+8
                    while len(got)<len(tx):
                        if process.poll() is not None: raise AssertionError('Java exited early: '+stdout.read_text())
                        if time.monotonic()>deadline: raise AssertionError('No request '+repr(tx)+': '+stdout.read_text())
                        if select.select([master],[],[],0.05)[0]: got+=os.read(master,len(tx)-len(got))
                    assert got==tx,(mode,index,tx,got)
                    # Deliberately fragment responses, including checksum boundaries.
                    for part in range(0,len(rx),7):
                        os.write(master,rx[part:part+7]);time.sleep(.001)
                    if mode=='interrupt' and b'507001(40)' in tx:
                        time.sleep(.05);process.send_signal(signal.SIGINT)
                code=process.wait(timeout=8)
                result_path=next(output.glob('*/result.json')); report=json.loads(result_path.read_text())
                if mode=='all':
                    assert code==0,(code,stdout.read_text())
                    assert len(report['results'])==4
                    for result in report['results']: assert result['transferStatus']=='complete' and result['sessionError'] is None
                    assert report['results'][0]['data']['totals'][0]['value']==27186.688
                    assert len(report['results'][3]['data']['intervals'])==33
                else:
                    assert code!=0
                    assert report['status']=='failed_or_partial'
                    assert report['results'][0]['transferStatus']=='partial'
                    assert '667001' in report['results'][0]['rawBlocks']
                    assert '507001' not in report['results'][0]['rawBlocks']
                    if mode=='interrupt': assert report['results'][0]['metadata']['cancelled'] is True
                assert (result_path.stat().st_mode & 0o777)==0o600
                wire=result_path.parent/'wire.log'; assert (wire.stat().st_mode & 0o777)==0o600
                assert str(tty) in wire.read_text()
                if mode!='all':
                    assert '<STX>(886 [PARTIAL/INVALID]' in wire.read_text()
                    assert '<SOH>B0<ETX>' in wire.read_text()
                saved=ROOT/'build/a1700-test-results'/('pty-'+mode)
                shutil.copytree(result_path.parent,saved,dirs_exist_ok=True)
                print('PASS PTY:',mode,'exit',code,'JSON, wire log and raw/decoded data verified')
            finally:
                if process.poll() is None: process.kill();process.wait()
                os.close(master);os.close(slave)

if __name__=='__main__':
    for scenario in ('all','timeout','interrupt'):test(scenario)
