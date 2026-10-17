"""Explicit-range ARM/Thumb disassembly for research; does not infer functions or decompile."""
import argparse
from pathlib import Path
from nds import Rom, LEO_SHA256

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('rom', type=Path)
    p.add_argument('--cpu', choices=['arm9','arm7'], default='arm9')
    p.add_argument('--offset', type=lambda x:int(x,0), required=True)
    p.add_argument('--size', type=lambda x:int(x,0), required=True)
    p.add_argument('--mode', choices=['arm','thumb'], required=True)
    a=p.parse_args()
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB
    r=Rom(a.rom.read_bytes())
    if r.sha256 != LEO_SHA256: p.error('Unprofiled ROM')
    data=r.section(a.cpu)
    alignment=4 if a.mode=='arm' else 2
    if a.offset < 0 or a.size <= 0 or a.offset+a.size>len(data) or a.offset%alignment or a.size%alignment:
        p.error('Invalid or unaligned range')
    base=r.sections[a.cpu]['ram']+a.offset
    decoder=Cs(CS_ARCH_ARM, CS_MODE_ARM if a.mode=='arm' else CS_MODE_THUMB)
    consumed=0
    for ins in decoder.disasm(data[a.offset:a.offset+a.size],base):
        print(f'{ins.address:08x}  {ins.mnemonic:8} {ins.op_str}')
        consumed+=ins.size
    if consumed != a.size:
        raise SystemExit('Stopped on undecodable bytes; this range may contain data')
if __name__=='__main__': main()
