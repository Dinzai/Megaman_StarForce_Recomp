"""Export supported Leo bind-pose models for the Android renderer.

External texture archive selection is inferred from archive grouping: the first
following BTX0 before the next BMD0. Material texture/palette names are explicit.
"""
import argparse,hashlib,io,json,zipfile
from collections import Counter
from pathlib import Path
import ndspy.texture,ndspy.color
from nds import Rom,LEO_SHA256
from sfarchive import Archive
from leo_assets import texture_container
from leo_models import decode_models,UnsupportedModel

def export(rom,out):
    if rom.sha256!=LEO_SHA256:raise ValueError('Unprofiled ROM')
    if out.exists():raise FileExistsError(out)
    models=[];textures=[];rejected=[]
    with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED) as pack:
        for fid in (661,669):
            archive=Archive(rom.file(fid))
            for entry in archive.entries:
                data=archive.decode(entry.index)
                if data[:4]!=b'BMD0':continue
                try:decoded=list(decode_models(data))
                except (ValueError,IndexError) as exc:
                    rejected.append(dict(file_id=fid,entry=entry.index,reason=str(exc)));continue
                texdata=texture_container(data);binding='embedded'
                if texdata is None:
                    binding='first-following-texture-archive (inferred)'
                    for later in archive.entries[entry.index+1:]:
                        candidate=archive.decode(later.index)
                        if candidate[:4]==b'BMD0':break
                        if candidate[:4]==b'BTX0':texdata=texture_container(candidate);break
                texdict=dict(texdata.textures) if texdata else {};paldict=dict(texdata.palettes) if texdata else {}
                for mi,(name,model) in enumerate(decoded):
                    unresolved=[]
                    for mati,material in enumerate(model['materials']):
                        material['image']=None
                        tex=texdict.get(material['texture']);pal=paldict.get(material['palette'])
                        if tex is None or (pal is None and tex.format!=ndspy.texture.TextureFormat.A1BGR5):
                            unresolved.append(mati);continue
                        colors=[ndspy.color.pack(*c) for c in pal.colors] if pal else None
                        try:image=ndspy.texture.renderTextureDataAsImage(tex.data1,tex.data2,tex.format,tex.width,tex.height,colors,tex.isColor0Transparent)
                        except (ValueError,IndexError) as exc:
                            material['texture_error']=str(exc);unresolved.append(mati);continue
                        buffer=io.BytesIO();image.save(buffer,format='PNG');png=buffer.getvalue()
                        path=f'textures/{fid}_{entry.index}_{mi}_{mati}.png';pack.writestr(path,png)
                        textures.append(dict(path=path,name=material['texture'],file_id=fid,entry=entry.index,
                            format=tex.format.name,palette_selection='model-material-binding',sha256=hashlib.sha256(png).hexdigest()))
                        material.update(image=path,width=tex.width,height=tex.height)
                    model.update(name=name,texture_archive_selection=binding,unresolved_materials=unresolved)
                    path=f'models/{fid}_{entry.index}_{mi}.json'
                    payload=json.dumps(model,separators=(',',':'),allow_nan=False).encode();pack.writestr(path,payload)
                    models.append(dict(path=path,name=name,file_id=fid,entry=entry.index,
                          triangles=sum(len(d['vertices'])//27 for d in model['draws']),
                          unresolved_materials=unresolved,sha256=hashlib.sha256(payload).hexdigest()))
        manifest=dict(schema=2,game='leo',rom_sha256=rom.sha256,textures=textures,models=models)
        pack.writestr('manifest.json',json.dumps(manifest,separators=(',',':')))
    report=dict(models=len(models),triangles=sum(m['triangles'] for m in models),texture_images=len(textures),
                models_with_unresolved_materials=sum(bool(m['unresolved_materials']) for m in models),
                skipped=len(rejected),skip_reasons=dict(Counter(v['reason'] for v in rejected)),
                caveat='Bind pose; external texture archive grouping is inferred, not traced in-game')
    out.with_suffix('.report.json').write_text(json.dumps(dict(summary=report,rejected=rejected),indent=2)+'\n')
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('rom',type=Path);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();print(json.dumps(export(Rom(a.rom.read_bytes()),a.out),indent=2))
if __name__=='__main__':main()
