# Reuse the validated NativeFormat decoder/CommonFixups parser. No target execution.
# Usage: python map-node-block-field.py /path/to/HytaleClient
# Requires Python lief+capstone; offsets are SHA-bound to the 0.7.0 arm64 build.
from pathlib import Path
import sys
if len(sys.argv) != 2: raise SystemExit('Usage: map-node-block-field.py HytaleClient')
sys.argv.append('unused') # Shared decoder requires a method argument; no method loop is executed.
exec((Path(__file__).resolve().parent.parent / 'avatar-070/map-aot-methods.py').read_text().split('for name in sys.argv[1:]:')[0])
name=0x25101a1
for ref in hits(enc(name-meta),meta,mlimit):
 for start in range(ref-5,ref):
  try:
   flags,q=dec(start);n,q=dec(q)
   if n!=name-meta or q!=ref+len(enc(name-meta)) or flags>0xffff:continue
   for inv in hits(enc(start-meta),0x28645d7,0x288ef9e):
    for k in range(inv-9,inv):
     try:
      fl,q=dec(k);owner,q=dec(q)
      if q!=inv or fl&0x3!=0 or not fl&0x20 or fl>0xff:continue
      _,q=dec(q);offset,q=dec(q);fp=fix+owner*4;ptr=fp+struct.unpack_from('<i',b,fp)[0]+0x100000000
      print('fieldrecord',hex(start),'flags',hex(flags),'map',hex(k),'tableflags',hex(fl),'owner',hex(ptr),'offset',hex(offset))
     except:pass
  except:pass
