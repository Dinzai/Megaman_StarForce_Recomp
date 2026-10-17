"""Explicit-range disassembly of decompressed main sections or individual overlays."""
import argparse
from pathlib import Path
from capstone import Cs,CS_ARCH_ARM,CS_MODE_ARM,CS_MODE_THUMB
from nds import Rom,LEO_SHA256
from leo_code import main_images,overlay_image

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('rom',type=Path)
    p.add_argument('--cpu',choices=['arm9','arm7'],default='arm9')
    p.add_argument('--section',type=int,default=0)
    p.add_argument('--overlay',type=int,help='Overlay ID; excludes main sections')
    p.add_argument('--offset',type=lambda x:int(x,0),required=True,help='Offset in the decoded image')
    p.add_argument('--size',type=lambda x:int(x,0),required=True)
    p.add_argument('--mode',choices=['arm','thumb'],required=True)
    a=p.parse_args();rom=Rom(a.rom.read_bytes())
    if rom.sha256!=LEO_SHA256:p.error('Unprofiled ROM')
    if a.overlay is None:
        images=main_images(rom,a.cpu)
        if not 0<=a.section<len(images):p.error('Invalid main section')
        image=images[a.section]
    else:
        record=next((o for o in rom.overlays[a.cpu] if o['id']==a.overlay),None)
        if record is None:p.error('Unknown overlay')
        image=overlay_image(rom,record)
    align=4 if a.mode=='arm' else 2
    if a.offset<0 or a.size<=0 or a.offset+a.size>len(image.data) or a.offset%align or a.size%align:
        p.error('Invalid or unaligned range')
    decoder=Cs(CS_ARCH_ARM,CS_MODE_ARM if a.mode=='arm' else CS_MODE_THUMB)
    consumed=0
    for ins in decoder.disasm(image.data[a.offset:a.offset+a.size],image.address+a.offset):
        print(f'{ins.address:08x}  {ins.mnemonic:8} {ins.op_str}');consumed+=ins.size
    if consumed!=a.size:raise SystemExit('Undecodable bytes encountered; range may contain data')
if __name__=='__main__':main()
