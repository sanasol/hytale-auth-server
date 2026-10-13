from pathlib import Path
import struct,re,sys,bisect,lief,hashlib
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_ARM
if len(sys.argv) < 3:
 raise SystemExit('Usage: map-aot-methods.py HytaleClient GetEyes [more method names]')
p=Path(sys.argv[1]);b=p.read_bytes()
if hashlib.sha256(b).hexdigest() != '61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06':
 raise SystemExit('Unsupported client build: offsets are specific to Hytale 0.7.0 macOS arm64')
outdir=Path('.temp/avatar-070');outdir.mkdir(parents=True,exist_ok=True);meta=0x239b520;mlimit=0x25e3725;ilo,ihi=0x26a18cc,0x285d185;fix=0x2aed464;starts=sorted(lief.parse(str(p)).function_starts.functions)
def dec(p):
 v=b[p]
 for n in range(1,5):
  if not v&(1<<(n-1)):return int.from_bytes(b[p:p+n],'little')>>n,p+n
 if not v&16:return int.from_bytes(b[p+1:p+5],'little'),p+5
 raise ValueError()
def enc(v):
 for n in range(1,5):
  if v<(1<<(n*8-n)):return ((v<<n)|((1<<(n-1))-1)).to_bytes(n,'little')
 return b'\x0f'+v.to_bytes(4,'little')
def hits(x,lo,hi):
 p=lo
 while (p:=b.find(x,p,hi))>=0:yield p;p+=1
for name in sys.argv[2:]:
 for sp in hits(enc(len(name))+name.encode(),meta,mlimit):
  for ref in hits(enc(sp-meta),meta,mlimit):
   for start in range(max(meta,ref-6),ref):
    try:
     flag,q=dec(start);impl,q=dec(q);n,q=dec(q)
     if q!=ref+len(enc(sp-meta)) or impl!=0 or flag<0x80 or flag>0xffff:continue
     for inv in hits(enc(start-meta),ilo,ihi):
      invstart=next((k for k in range(inv-4,inv) if (lambda r:r[1]==inv and r[0]<0xc0 and r[0]&0x20)(dec(k))),None)
      if invstart is None:continue
      f,qe=dec(invstart)
      _,qe=dec(inv);owner,qe=dec(qe);entry,qe=dec(qe)
      fp=fix+entry*4;addr=fp+struct.unpack_from('<i',b,fp)[0]
      if not 0x4000<=addr<0x238b70c:continue
      print(name,'name',hex(sp),'metadata',hex(start),'invoke',hex(invstart),'flags',hex(f),'owner',hex(owner),'entry',hex(entry),'address',hex(0x100000000+addr))
      i=bisect.bisect_right(starts,addr)-1;z=starts[i+1];out=[name+' map '+hex(start)+' -> '+hex(0x100000000+addr)]
      for ins in Cs(CS_ARCH_ARM64,CS_MODE_ARM).disasm(b[addr:z],0x100000000+addr):out.append(f'{ins.address:x}: {ins.mnemonic} {ins.op_str}')
      Path('.temp/avatar-070/'+name+'-'+hex(addr)+'.asm').write_text('\n'.join(out))
    except:pass
