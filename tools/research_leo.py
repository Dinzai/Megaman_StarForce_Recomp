"""Reproducible Leo analysis and local asset pack generation. No ROM data is uploaded."""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import zipfile
import ndspy.soundArchive
from nds import Rom,LEO_SHA256
from sfarchive import Archive
from leo_code import main_images,overlay_image,init_targets,explore
from leo_assets import NITRO_MAGICS,blocks,textures

def digest(data):return hashlib.sha256(data).hexdigest()
def write_json(path,value):path.write_text(json.dumps(value,indent=2)+'\n')

def analyze(rom,out,export=False):
    out.mkdir(parents=True,exist_ok=False)
    main=main_images(rom)
    overlay_report=[];overlay_images=[]
    for record in rom.overlays['arm9']:
        image=overlay_image(rom,record);overlay_images.append(image)
        overlay_report.append(dict(**record,decoded_sha256=digest(image.data),
                                   initializer_targets=init_targets(image,record)))
        if export:
            dest=out/'code';dest.mkdir(exist_ok=True)
            (dest/(image.name+'.bin')).write_bytes(image.data)
    main_report=[]
    for image in main:
        main_report.append(dict(name=image.name,ram=image.address,size=len(image.data),bss=image.bss_size,sha256=digest(image.data)))
        if export:(out/'code'/(image.name+'.bin')).write_bytes(image.data)
    # Identify load conflicts rather than mapping every overlay simultaneously.
    conflicts=[]
    for i,a in enumerate(overlay_images):
        for b in overlay_images[i+1:]:
            if a.address<b.address+len(b.data)+b.bss_size and b.address<a.address+len(a.data)+a.bss_size:
                conflicts.append([a.name,b.name])
    flow=explore(main,[(rom.sections['arm9']['entry'],False)])
    # Instructions are local analysis output, never part of the public summary.
    write_json(out/'startup-flow.json',flow)
    write_json(out/'overlays.json',dict(main=main_report,overlays=overlay_report,conflicts=conflicts))
    archives=[];kinds=Counter();entry_count=0;compressed_count=0
    texture_manifest=[];texture_errors=[]
    with zipfile.ZipFile(out/'leo-textures.zip','w',zipfile.ZIP_DEFLATED) as pack:
        for fid,path in rom.names.items():
            if not path.startswith('datbin/') or path.endswith('.sdat'):continue
            archive=Archive(rom.file(fid));items=[]
            for entry in archive.entries:
                data=archive.decode(entry.index);entry_count+=1;compressed_count+=entry.compressed
                kind=data[:4].decode('ascii') if data[:4] in NITRO_MAGICS else 'unknown'
                kinds[kind]+=1
                item=dict(index=entry.index,offset=entry.offset,stored_size=entry.stored_size,
                          size=len(data),compressed=entry.compressed,kind=kind,sha256=digest(data))
                if data[:4] in NITRO_MAGICS:
                    item['blocks']=[dict(type=name,size=len(block)) for name,block in blocks(data)]
                items.append(item)
                if export:
                    folder=out/'assets'/str(fid);folder.mkdir(parents=True,exist_ok=True)
                    (folder/f'{entry.index:04d}.bin').write_bytes(data)
                if data[:4] not in (b'BMD0',b'BTX0'):continue
                try:
                    for metadata,image in textures(data):
                        metadata.update(archive=path,file_id=fid,entry=entry.index)
                        if image is None:
                            texture_errors.append(metadata);continue
                        name=f"textures/{fid}_{entry.index}_{metadata['index']}.png"
                        buffer=io.BytesIO();image.save(buffer,format='PNG');payload=buffer.getvalue()
                        metadata.update(path=name,sha256=digest(payload))
                        pack.writestr(name,payload);texture_manifest.append(metadata)
                except (ValueError,IndexError,AssertionError) as exc:
                    texture_errors.append(dict(file_id=fid,entry=entry.index,error=str(exc)))
            archives.append(dict(file_id=fid,path=path,entries=items))
        pack.writestr('manifest.json',json.dumps(dict(schema=1,game='leo',rom_sha256=rom.sha256,
                       textures=texture_manifest),indent=2))
    sound=ndspy.soundArchive.SDAT(rom.file(next(i for i,p in rom.names.items() if p.endswith('sound_data.sdat'))))
    audio={key:len(getattr(sound,key)) for key in ['sequences','sequenceArchives','banks','waveArchives','streams']}
    write_json(out/'archives.json',archives)
    write_json(out/'texture-issues.json',texture_errors)
    summary=dict(rom_sha256=rom.sha256,main_sections=main_report,
                 overlays=len(overlay_report),compressed_overlays=sum(bool(o['flags']&1) for o in overlay_report),
                 overlay_conflict_pairs=len(conflicts),archive_count=len(archives),asset_entries=entry_count,
                 compressed_assets=compressed_count,asset_kinds=dict(kinds),decoded_textures=len(texture_manifest),
                 texture_issues=len(texture_errors),audio=audio,
                 startup=dict(instructions=flow['instruction_count'],edges=len(flow['edges']),
                              unresolved_stops=len(flow['stops']),limit_reached=flow['limit_reached'],
                              io_literal_references=sum(l['io_range'] for l in flow['literals'])))
    write_json(out/'summary.json',summary)
    return summary

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('rom',type=Path);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--export-binaries',action='store_true',help='Also write decompressed code and asset binaries locally')
    a=p.parse_args();rom=Rom(a.rom.read_bytes())
    if rom.sha256!=LEO_SHA256:p.error('Only the profiled Leo ROM is supported')
    print(json.dumps(analyze(rom,a.out,a.export_binaries),indent=2))
if __name__=='__main__':main()
