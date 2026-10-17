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

def lz11_decode(data,expected=None):
    if len(data)<4 or data[0]!=0x11:raise ValueError('Expected LZ11 header')
    size=int.from_bytes(data[1:4],'little');cursor=4
    if size==0:
        if len(data)<8:raise ValueError('Truncated extended LZ11 header')
        size=int.from_bytes(data[4:8],'little');cursor=8
    if size>MAX_DECODED or (expected is not None and size!=expected):raise ValueError('LZ11 size mismatch')
    out=bytearray()
    def take():
        nonlocal cursor
        if cursor>=len(data):raise ValueError('Truncated LZ11')
        b=data[cursor];cursor+=1;return b
    while len(out)<size:
        flags=take()
        for bit in range(7,-1,-1):
            if len(out)==size:break
            if not flags&(1<<bit):out.append(take());continue
            a=take();kind=a>>4;b=take()
            if kind==0:
                c=take();length=((a&15)<<4 | b>>4)+0x11;distance=((b&15)<<8 | c)+1
            elif kind==1:
                c,d=take(),take();length=((a&15)<<12 | b<<4 | c>>4)+0x111;distance=((c&15)<<8 | d)+1
            else:length=kind+1;distance=((a&15)<<8 | b)+1
            if distance>len(out) or len(out)+length>size:raise ValueError('Invalid LZ11 reference')
            for _ in range(length):out.append(out[-distance])
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
        if records[-1][0]!=len(data) or records[-1][1] not in (0,0xffff):raise ValueError('Missing EOF marker')
        self.eof_marker=records[-1][1]
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
        return (lz11_decode if entry.payload[:1]==b'\x11' else lz10_decode)(entry.payload,entry.decoded_size) if entry.compressed else entry.payload

def build(entries,eof_marker=0xffff):
    """Serialize an archive from (payload, decoded_size, compressed) tuples.

    Payloads are stored as given; compressed payloads must already be LZ10.
    Each payload starts on a 4-byte boundary, matching the original layout.
    """
    if eof_marker not in (0,0xffff):raise ValueError("Invalid EOF marker")
    table_len=(len(entries)+1)*8
    out=bytearray(table_len);records=[]
    for payload,decoded_size,compressed in entries:
        if decoded_size>MAX_DECODED:raise ValueError('Entry too large')
        while len(out)%4:out.append(0)
        records.append((len(out),decoded_size|(0x80000000 if compressed else 0)))
        out+=payload
    while len(out)%4:out.append(0)
    records.append((len(out),eof_marker))
    for i,(offset,flags) in enumerate(records):struct.pack_into('<II',out,i*8,offset,flags)
    return bytes(out)

def replace_entries(data,replacements):
    """Return a copy of archive `data` with decoded replacements stored uncompressed.

    `replacements` maps entry index -> new decoded bytes. Untouched entries keep
    their original stored bytes, so unchanged compressed data is preserved.
    """
    archive=Archive(data);rows=[]
    for e in archive.entries:
        if e.index in replacements:
            new=bytes(replacements[e.index]);rows.append((new,len(new),False))
        else:
            rows.append((e.payload,e.decoded_size,e.compressed))
    unknown=set(replacements)-{e.index for e in archive.entries}
    if unknown:raise ValueError(f'No such archive entries: {sorted(unknown)}')
    return build(rows,archive.eof_marker)

