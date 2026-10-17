"""Decompressed Leo load images and bounded control-flow exploration.

This is analysis tooling, not a CPU executor or decompiler. Direct control-flow
is followed; indirect branches end exploration and are reported explicitly.
"""
from dataclasses import dataclass
import struct
import ndspy.code
import ndspy.codeCompression
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB, CS_GRP_JUMP, CS_GRP_CALL, CS_GRP_RET
from capstone.arm import ARM_OP_IMM, ARM_OP_REG, ARM_OP_MEM, ARM_REG_PC, ARM_REG_LR, ARM_CC_AL, ARM_CC_INVALID

@dataclass
class Image:
    name: str
    address: int
    data: bytes
    bss_size: int=0
    def contains(self,address):return self.address<=address<self.address+len(self.data)

class LoadMap:
    """Guest load-image model: BSS zeroing and overlay lifetime, no hardware execution."""
    def __init__(self):self.images={}
    def load(self,image):
        start=image.address;end=start+len(image.data)+image.bss_size
        if start<0 or image.bss_size<0 or end>0x100000000:raise ValueError('Invalid load range')
        for other in self.images.values():
            if start<other.address+len(other.data)+other.bss_size and other.address<end:
                raise ValueError(f'Load conflicts with {other.name}; unload it first')
        if image.name in self.images:raise ValueError('Image already loaded')
        self.images[image.name]=image
    def unload(self,name):del self.images[name]
    def read(self,address,size):
        if size<0:raise ValueError('Negative read size')
        for image in self.images.values():
            offset=address-image.address
            if 0<=offset and offset+size<=len(image.data)+image.bss_size:
                chunk=image.data[offset:offset+size]
                return chunk+b'\0'*(size-len(chunk))
        raise ValueError('Unmapped guest memory (hardware is not emulated)')

def main_images(rom,cpu='arm9'):
    source=rom.sections[cpu]
    code=ndspy.code.MainCodeFile(rom.section(cpu),source['ram'])
    return [Image(f'{cpu}_{i}',s.ramAddress,bytes(s.data),s.bssSize) for i,s in enumerate(code.sections)]

def overlay_image(rom,record):
    raw=rom.file(record['file_id'])
    if record['flags']&1:
        if len(raw)!=record['compressed_size']:raise ValueError('Compressed overlay size mismatch')
        raw=ndspy.codeCompression.decompress(raw)
    if len(raw)!=record['size']:raise ValueError('Overlay RAM size mismatch')
    return Image(f"overlay_{record['id']:03d}",record['ram'],bytes(raw),record['bss'])

def init_targets(image,record):
    start,end=record['init_start'],record['init_end']
    if start==end:return []
    off=start-image.address;size=end-start
    if off<0 or size<0 or size%4 or off+size>len(image.data):
        raise ValueError('Invalid static initializer table')
    return [v[0] for v in struct.iter_unpack('<I',image.data[off:off+size]) if v[0]]

def explore(images,seeds,max_instructions=20000):
    decoders={False:Cs(CS_ARCH_ARM,CS_MODE_ARM),True:Cs(CS_ARCH_ARM,CS_MODE_THUMB)}
    for d in decoders.values():d.detail=True
    queue=list(seeds);visited=set();calls=[];stops=[];literals=[];instructions=[]
    def locate(address):return next((im for im in images if im.contains(address)),None)
    while queue and len(visited)<max_instructions:
        pc,thumb=queue.pop();pc&=~1
        while len(visited)<max_instructions:
            key=(pc,thumb)
            if key in visited:break
            im=locate(pc)
            if im is None:
                stops.append(dict(address=pc,thumb=thumb,reason='outside loaded images'));break
            if pc%(2 if thumb else 4):
                stops.append(dict(address=pc,thumb=thumb,reason='unaligned target'));break
            offset=pc-im.address
            ins=next(decoders[thumb].disasm(im.data[offset:offset+4],pc,count=1),None)
            if ins is None:
                stops.append(dict(address=pc,thumb=thumb,reason='invalid instruction'));break
            visited.add(key)
            instructions.append(dict(address=pc,thumb=thumb,image=im.name,mnemonic=ins.mnemonic,operands=ins.op_str))
            # PC-relative literal loads are evidence of a referenced constant,
            # not proof of an MMIO access or of a function pointer.
            if ins.mnemonic.startswith('ldr') and len(ins.operands)>1:
                op=ins.operands[1]
                if op.type==ARM_OP_MEM and op.mem.base==ARM_REG_PC:
                    base=((pc+4)&~3) if thumb else pc+8
                    literal=base+op.mem.disp;owner=locate(literal)
                    if owner and literal+4<=owner.address+len(owner.data):
                        value=struct.unpack_from('<I',owner.data,literal-owner.address)[0]
                        literals.append(dict(instruction=pc,literal=literal,value=value,
                                             io_range=0x04000000<=value<0x05000000))
            conditional=ins.cc not in (ARM_CC_AL,ARM_CC_INVALID)
            is_call=ins.group(CS_GRP_CALL)
            is_jump=ins.group(CS_GRP_JUMP)
            if is_call or is_jump:
                target=ins.operands[0].imm if ins.operands and ins.operands[0].type==ARM_OP_IMM else None
                if target is not None:
                    mode=not thumb if ins.mnemonic.startswith('blx') else thumb
                    queue.append((target&~1,mode))
                    calls.append(dict(source=pc,target=target&~1,thumb=mode,kind='call' if is_call else 'branch'))
                else:
                    stops.append(dict(address=pc,thumb=thumb,reason='indirect call' if is_call else 'indirect branch',instruction=ins.mnemonic+' '+ins.op_str))
                if not is_call and not conditional:break
            # Return and computed-PC writes are barriers, including LDM/POP {...pc}.
            writes_pc=any(o.type==ARM_OP_REG and o.reg==ARM_REG_PC for o in ins.operands)
            if not (is_call or is_jump) and (ins.group(CS_GRP_RET) or
                    (writes_pc and ins.mnemonic.startswith(('pop','ldm'))) or
                    (ins.operands and ins.operands[0].type==ARM_OP_REG and ins.operands[0].reg==ARM_REG_PC)):
                stops.append(dict(address=pc,thumb=thumb,reason='return/computed PC'))
                if not conditional:break
            pc+=ins.size
    return dict(instruction_count=len(instructions),limit_reached=len(visited)>=max_instructions,
                edges=calls,stops=stops,literals=literals,instructions=instructions)
