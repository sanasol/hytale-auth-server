# Static reconstruction of NativeAOT v10 hydrated section, no execution.
from pathlib import Path
import struct,re,sys,hashlib
if len(sys.argv) < 2:
 raise SystemExit('Usage: rehydrate-aot.py HytaleClient [output.bin]')
b=Path(sys.argv[1]).read_bytes()
if hashlib.sha256(b).hexdigest() != '61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06':
 raise SystemExit('Unsupported client build: offsets are specific to Hytale 0.7.0 macOS arm64')
output=Path(sys.argv[2] if len(sys.argv)>2 else '.temp/avatar-070/hydrated-static.bin')
output.parent.mkdir(parents=True,exist_ok=True);begin=0x2c5e1e1;end=0x348fb70;p=begin+4;dest=begin+struct.unpack_from('<i',b,begin)[0];start=dest;out=bytearray(0x10f9bb8)
def rel(p):return p+struct.unpack_from('<i',b,p)[0]
def write(data):
 global dest
 out[dest-start:dest-start+len(data)]=data;dest+=len(data)
while p<end:
 v=b[p];p+=1;cmd=v&7;n=v>>3
 if n>28:
  extra=n-28;n=int.from_bytes(b[p:p+extra],'little')+28;p+=extra
 if cmd==0:write(b[p:p+n]);p+=n
 elif cmd==1:dest+=n
 elif cmd==2:write(struct.pack('<i',rel(end+n*4)-dest))
 elif cmd==3:write(struct.pack('<Q',0x100000000+rel(end+n*4)))
 elif cmd in (4,5):
  for _ in range(n):
   r=rel(p);p+=4;write(struct.pack('<i',r-dest) if cmd==4 else struct.pack('<Q',0x100000000+r))
 else:raise ValueError((hex(p),cmd,n))
assert dest-start==len(out) and p==end, 'Hydration stream bounds mismatch'
output.write_bytes(out)
print('reconstructed',hex(start),hex(dest),len(out),'streamend',hex(p))
for ptr in [0x100046500,0x100046504,0x100046508,0x100cc3ed0,0x100c93450,0x100046528]:
 print(hex(ptr),'pointerlocations',[hex(0x100000000+start+m.start()) for m in re.finditer(re.escape(struct.pack('<Q',ptr)),out)][:15])
