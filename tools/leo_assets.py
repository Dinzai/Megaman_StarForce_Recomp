"""Nitro container metadata and texture decoding through ndspy's public API."""
import struct
import ndspy.texture
import ndspy.color
ndspy.color.prepareLUTs()

NITRO_MAGICS={b'BMD0',b'BTX0',b'BCA0',b'BTP0',b'BTA0',b'BMA0',b'BVA0'}

def blocks(data):
    if len(data)<16 or data[:4] not in NITRO_MAGICS:raise ValueError('Not a supported Nitro container')
    magic,bom,version,size,header,count=struct.unpack_from('<4sHHIHH',data)
    if bom!=0xfeff or size!=len(data) or header!=16 or 16+count*4>len(data):
        raise ValueError('Invalid Nitro container header')
    result=[]
    for i in range(count):
        off=struct.unpack_from('<I',data,16+i*4)[0]
        if off<16+count*4 or off+8>len(data):raise ValueError('Invalid Nitro block offset')
        length=struct.unpack_from('<I',data,off+4)[0]
        if length<8 or off+length>len(data):raise ValueError('Invalid Nitro block size')
        result.append((data[off:off+4].decode('ascii'),data[off:off+length]))
    return result

def texture_container(data):
    tex=next((block for name,block in blocks(data) if name=='TEX0'),None)
    if tex is None:return None
    # Public NSBTX API accepts a single TEX0 block in a standard container.
    wrapped=struct.pack('<4sHHIHHI',b'BTX0',0xfeff,1,20+len(tex),16,1,20)+tex
    return ndspy.texture.NSBTX(wrapped)

def textures(data):
    container=texture_container(data)
    if container is None:return
    palettes=dict(container.palettes)
    for index,(name,texture) in enumerate(container.textures):
        palette=None;palette_name=None;selection='direct-color'
        if texture.format!=ndspy.texture.TextureFormat.A1BGR5:
            # No guessing among multiple palettes: use an explicit conventional
            # name match, or a single available palette. Both are reported.
            candidates=[n for n in (name,name+'_pl') if n in palettes]
            if len(candidates)==1:
                palette_name=candidates[0];selection='name-match'
            elif len(palettes)==1:
                palette_name=next(iter(palettes));selection='single-palette'
            else:
                yield dict(index=index,name=name,width=texture.width,height=texture.height,
                           format=texture.format.name,status='unresolved-palette'),None
                continue
            palette=palettes[palette_name]
        metadata=dict(index=index,name=name,width=texture.width,height=texture.height,
                      format=texture.format.name,palette=palette_name,palette_selection=selection,status='decoded')
        # ndspy 4.2's renderers expect packed RGB555 colors while
        # Palette.colors exposes ColorTuple values. Adapt via public APIs.
        packed=[ndspy.color.pack(*color) for color in palette.colors] if palette else None
        image=ndspy.texture.renderTextureDataAsImage(texture.data1,texture.data2,texture.format,
                texture.width,texture.height,packed,texture.isColor0Transparent)
        yield metadata,image
