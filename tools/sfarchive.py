"""Leo's offset/length archives, inferred from the profiled ROM.

Each 8-byte entry is (offset, decoded_length | compressed_flag).
The first payload offset gives the table length. The last table record
is an EOF marker (offset == file length, length == 0xffff).
"""
from dataclasses import dataclass
import struct

MAX_DECODED = 32 * 1024 * 1024

def lz10_decode(data, expected=None):
    """Bounded Nintendo LZ10 decoder; rejects truncated and invalid references."""
    if len(data)<4 or data[0]!=0x10:
        raise ValueError('Expected LZ10 header')
    size=int.from_bytes(data[1:4],'little')
    if size>MAX_DECODED or (expected is not None and size!=expected):
        raise ValueError('LZ10 decoded size mismatch or limit exceeded')
    out=bytearray(); cursor=4
    def take():
        nonlocal cursor
        if cursor>=len(data): raise ValueError('Truncated LZ10 stream')
        b=data[cursor];cursor+=1;return b
    while len(out)<size:
        flags=take()
        for bit in range(7,-1,-1):
            if len(out)==size: break
            if flags & (1<<bit):
                a,b=take(),take();length=(a>>4)+3;distance=((a&15)<<8|b)+1
                if distance>len(out) or len(out)+length>size:
                    raise ValueError('Invalid LZ10 backreference')
                for _ in range(length):out.append(out[-distance])
            else:out.append(take())
    # Container records are padded to a 4-byte boundary; do not consume it.
    return bytes(out)

@dataclass(frozen=True)
class Entry:
    index: int
    offset: int
    stored_size: int
    decoded_size: int
    compressed: bool
    payload: bytes

class Archive:
    def __init__(self,data):
        if len(data)<16:raise ValueError('Archive too short')
        first=struct.unpack_from('<I',data)[0]
        if first<16 or first%8 or first>len(data):raise ValueError('Invalid archive table')
        records=list(struct.iter_unpack('<II',data[:first]))
        if records[-1]!=(len(data),0xffff):raise ValueError('Missing EOF marker')
        self.entries=[]
        for index,(offset,flags) in enumerate(records[:-1]):
            end=records[index+1][0];size=flags&0x7fffffff;compressed=bool(flags>>31)
            if offset<first or end<offset or end>len(data) or size>MAX_DECODED:
                raise ValueError('Invalid archive range')
            if not compressed and offset+size>end:raise ValueError('Overlapping payload')
            payload=data[offset:end] if compressed else data[offset:offset+size]
            self.entries.append(Entry(index,offset,end-offset,size,compressed,payload))
    def decode(self,index):
        entry=self.entries[index]
        return lz10_decode(entry.payload,entry.decoded_size) if entry.compressed else entry.payload
