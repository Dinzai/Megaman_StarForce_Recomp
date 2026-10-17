"""Bind-pose Nitro geometry decoder. Unsupported commands fail explicitly.

Format reference: scurest/nsbmd_docs (reverse-engineered specification).
No gameplay, animation tracks, billboard approximation, or game asset bytes are embedded.
"""
import struct
from leo_assets import blocks

class UnsupportedModel(ValueError): pass

def read(data,fmt,off):
    n=struct.calcsize(fmt)
    if off<0 or off+n>len(data):raise ValueError('Truncated model structure')
    return struct.unpack_from(fmt,data,off)

def names(data,base,expected):
    _,count,size=read(data,'<BBH',base)
    if base+size>len(data):raise ValueError('Invalid name-list size')
    at=base+12+4*count
    width,_=read(data,'<HH',at);at+=4
    if width!=expected or at+count*(width+16)>base+size:raise ValueError('Invalid name-list layout')
    return [(data[at+count*width+16*i:at+count*width+16*(i+1)].split(b'\0')[0].decode('ascii'),
             data[at+width*i:at+width*(i+1)]) for i in range(count)]

def identity():return [[float(i==j) for j in range(4)] for i in range(4)]
def multiply(a,b):return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def transform(m,v):return [sum(m[i][k]*v[k] for k in range(3))+m[i][3] for i in range(3)]
def scale(s):
    m=identity()
    for i in range(3):m[i][i]=s[i]
    return m

def bone(data,off):
    flags,m0=read(data,'<Hh',off);p=off+4;t=[0.,0.,0.];r=identity();s=[1.,1.,1.]
    if not flags&1:t=[v/4096 for v in read(data,'<3i',p)];p+=12
    if flags&8:
        a,b=[v/4096 for v in read(data,'<2h',p)];p+=4
        form=(flags>>4)&15
        if form>8:raise ValueError('Invalid pivot rotation')
        row,col=form%3,form//3
        r=[[0.]*4 for _ in range(4)];r[3][3]=1
        r[row][col]=-1. if flags&256 else 1.
        remaining_rows=[i for i in range(3) if i!=row];remaining_cols=[i for i in range(3) if i!=col]
        vals=[[a,-a if flags&512 else a],[b,-b if flags&1024 else b]]
        for i,ri in enumerate(remaining_rows):
            for j,cj in enumerate(remaining_cols):r[ri][cj]=vals[i][j]
    elif not flags&2:
        v=[m0/4096]+[v/4096 for v in read(data,'<8h',p)];p+=16
        for i in range(3):
            for j in range(3):r[i][j]=v[j*3+i]
    if not flags&4:s=[v/4096 for v in read(data,'<3i',p)]
    result=multiply(r,scale(s))
    for i in range(3):result[i][3]=t[i]
    return result

def signed(value,bits):return value-(1<<bits) if value&(1<<(bits-1)) else value

class Geometry:
    COUNTS={0:0,0x14:1,0x1b:3,0x20:1,0x21:1,0x22:1,0x23:2,0x24:1,
            0x25:1,0x26:1,0x27:1,0x28:1,0x40:1,0x41:0}
    def __init__(self):
        self.matrix=identity();self.stack={};self.position=[0.,0.,0.];self.uv=[0.,0.]
        self.color=[1.,1.,1.,1.];self.commands=set()
    def draw(self,data):
        cursor=0;mode=None;vertices=[];triangles=[]
        def emit():
            if mode is None:raise ValueError('Vertex outside primitive')
            vertices.append(transform(self.matrix,self.position)+self.uv+self.color)
            n=len(vertices)
            if mode==0 and n%3==0:triangles.extend(vertices[-3:])
            elif mode==1 and n%4==0:
                a,b,c,d=vertices[-4:];triangles.extend([a,b,c,a,c,d])
            elif mode==2 and n>=3:
                a,b,c=vertices[-3:];triangles.extend([b,a,c] if n%2==0 else [a,b,c])
            elif mode==3 and n>=4 and n%2==0:
                a,b,c,d=vertices[-4:];triangles.extend([a,b,d,a,d,c])
        while cursor<len(data):
            ops=read(data,'<4B',cursor);cursor+=4
            for op in ops:
                self.commands.add(op)
                if op not in self.COUNTS:raise UnsupportedModel(f'GX opcode 0x{op:02x}')
                count=self.COUNTS[op];args=read(data,'<'+'I'*count,cursor);cursor+=4*count
                if op==0:continue
                if op==0x14:
                    slot=args[0]&31
                    if slot not in self.stack:raise UnsupportedModel(f'Uninitialized matrix slot {slot}')
                    self.matrix=self.stack[slot]
                elif op==0x1b:self.matrix=multiply(self.matrix,scale([signed(v,32)/4096 for v in args]))
                elif op==0x20:self.color=[((args[0]>>s)&31)/31 for s in [0,5,10]]+[1.]
                elif op==0x21:pass # Unlit preview; normal-based DS lighting is not reproduced.
                elif op==0x22:self.uv=[signed(args[0]&65535,16)/16,signed(args[0]>>16,16)/16]
                elif op==0x40:
                    if mode is not None:raise ValueError('Nested primitive')
                    mode=args[0]&3;vertices=[]
                elif op==0x41:
                    if mode is not None and ((mode==0 and len(vertices)%3) or (mode in (1,3) and len(vertices)%(4 if mode==1 else 2))):
                        raise ValueError('Incomplete primitive')
                    mode=None
                else:
                    value=args[0]
                    if op==0x23:self.position=[signed(value&65535,16)/4096,signed(value>>16,16)/4096,signed(args[1]&65535,16)/4096]
                    elif op==0x24:self.position=[signed((value>>s)&1023,10)/64 for s in [0,10,20]]
                    elif op==0x28:self.position=[self.position[i]+signed((value>>(i*10))&1023,10)/4096 for i in range(3)]
                    else:
                        indices={0x25:(0,1),0x26:(0,2),0x27:(1,2)}[op]
                        self.position=list(self.position)
                        self.position[indices[0]]=signed(value&65535,16)/4096
                        self.position[indices[1]]=signed(value>>16,16)/4096
                    emit()
        if mode is not None:raise ValueError('Unterminated primitive')
        return triangles

def parse_model(data):
    size,sbc,mat,mesh,inv=read(data,'<5I',0)
    if size!=len(data) or not 64<=sbc<=mat<=mesh<=len(data):raise ValueError('Invalid model offsets')
    bones=[bone(data,64+int.from_bytes(v,'little')) for _,v in names(data,64,4)]
    materials=[]
    for name,v in names(data,mat+4,4):
        at=mat+int.from_bytes(v,'little');_,msize,diffuse,specular,poly,unknown,params=read(data,'<HH5I',at)
        w,h=read(data,'<HH',at+32)
        materials.append(dict(name=name,width=w,height=h,texture=None,palette=None,
                              repeat_s=bool(params&(1<<16)),repeat_t=bool(params&(1<<17)),
                              mirror_s=bool(params&(1<<18)),mirror_t=bool(params&(1<<19)),
                              alpha=((poly>>16)&31)/31))
    for field,offset in zip(['texture','palette'],read(data,'<2H',mat)):
        if not offset:continue
        for name,record in names(data,mat+offset,4):
            ids,count,_=struct.unpack('<HBB',record)
            # Offsets in the pairing dictionary point from MaterialList start.
            for idx in read(data,'<'+'B'*count,mat+ids):
                if idx>=len(materials):raise ValueError('Invalid material binding')
                materials[idx][field]=name
    shapes=[]
    for name,v in names(data,mesh,4):
        at=mesh+int.from_bytes(v,'little');_,_,off,length=read(data,'<4I',at)
        if at+off+length>len(data):raise ValueError('Shape outside model')
        shapes.append((name,data[at+off:at+off+length]))
    g=Geometry();p=sbc;material=None;draws=[];sbc_ops=set();parents={}
    def take(n):
        nonlocal p
        if p+n>mat:raise ValueError('SBC beyond command region')
        v=list(data[p:p+n]);p+=n;return v
    while p<mat:
        opcode=take(1)[0];op=opcode&31;sbc_ops.add(opcode)
        if op==0:continue
        if op==1:break
        if op==2:take(2) # Node visibility metadata; no animated visibility in bind pose.
        elif op==3:
            slot=take(1)[0]
            if slot not in g.stack:raise UnsupportedModel('SBC restore before store')
            g.matrix=g.stack[slot]
        elif op==4:
            material=take(1)[0]
            if material>=len(materials):raise ValueError('Invalid material index')
        elif op==5:
            idx=take(1)[0]
            if idx>=len(shapes) or material is None:raise ValueError('Invalid shape draw')
            name,commands=shapes[idx];verts=g.draw(commands)
            if verts:draws.append(dict(shape=name,material=material,vertices=[v for vertex in verts for v in vertex]))
        elif op==6:
            idx,parent,flags=take(3);parents[idx]=parent
            if idx>=len(bones):raise ValueError('Invalid bone')
            if flags&1:raise UnsupportedModel('Segment scale compensation')
            if opcode&64:
                slot=take(1)[0]
                if slot not in g.stack:raise UnsupportedModel('Bone restore before store')
                g.matrix=g.stack[slot]
            g.matrix=multiply(g.matrix,bones[idx])
            if opcode&32:g.stack[take(1)[0]]=g.matrix
        elif op==9:
            slot,count=take(2);acc=[[0.]*4 for _ in range(4)]
            for _ in range(count):
                source,index,weight=take(3)
                if source not in g.stack:raise UnsupportedModel('Skinning restore before store')
                values=read(data,'<12i',inv+index*84);matrix=identity()
                for row in range(3):
                    for col in range(4):matrix[row][col]=values[col*3+row]/4096
                term=multiply(g.stack[source],matrix)
                for row in range(4):
                    for col in range(4):acc[row][col]+=term[row][col]*weight/256
            g.matrix=acc;g.stack[slot]=acc
        elif op==11:
            factor=read(data,'<i',32 if opcode&32 else 28)[0]/4096
            g.matrix=multiply(g.matrix,scale([factor]*3))
        else:raise UnsupportedModel(f'SBC opcode 0x{opcode:02x}')
    else:raise ValueError('SBC end missing')
    if not draws:raise UnsupportedModel('No supported geometry')
    return dict(materials=materials,draws=draws,bones=len(bones),parents=parents,
                sbc_opcodes=sorted(sbc_ops),gx_opcodes=sorted(g.commands),mode='bind-pose')

def decode_models(container):
    for kind,block in blocks(container):
        if kind!='MDL0':continue
        for name,offset in names(block,8,4):
            start=int.from_bytes(offset,'little');size=read(block,'<I',start)[0]
            if start+size>len(block):raise ValueError('Model outside MDL0')
            yield name,parse_model(block[start:start+size])
