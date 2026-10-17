# Leo research milestone 0.3

This build adds an Android OpenGL model explorer, not gameplay execution.

## Measured results on profiled US Leo

- 195 bind-pose models exported from archives 661 and 669: 25,581 triangles and 576 texture images.
- 154 model entries excluded explicitly: 130 use SBC 0x07, 16 restore an unknown bone matrix, 7 use SBC 0x0c, and 1 uses SBC 0x08.
- 17 exported models have unresolved material images; those parts render with vertex colors. Mega Man `pl000` has resolved material images.
- Model transforms, matrix-stack references, weighted skin matrices, triangle/quad primitives and strips are decoded. Texture and palette names come from material bindings. External texture archive selection is inferred from archive adjacency, not confirmed through a running game.
- Startup analysis now resolves literal-loaded BX/BLX targets. The branch at `0x02000914` switches from ARM to Thumb at `0x02000c28`, via literal `0x02000c29` at `0x02000934`. This is static control-flow evidence, not an execution trace.
- With a 60,000-instruction budget, traversal from the ARM9 entry discovers 29,690 instructions and 4,821 edges without reaching the budget. These counts are neither function counts nor proof of complete coverage. Returns, computed jumps and unresolved hardware references remain barriers.

## AYN Thor test

1. Install the 0.3 research APK from the successful Android workflow artifact.
2. Copy `leo-models.zip` to the device. Select Leo, then **Import Leo asset pack (.zip)**.
3. Open **Browse 3D models**. Find `pl000` for Mega Man. Drag to orbit, pinch to zoom, or tap the viewer then use the D-pad.
4. Switch models and background/resume the app. Report missing geometry, texture orientation problems, crashes, and the file/entry/name shown in the selector.

The model pack replaces the installed texture pack and includes 576 material-bound images. Older schema-1 texture packs still import; they have no model list. ROM import and visual-mod settings are separate. APK behavior still needs physical-device verification.

## Reproduce

```sh
python -m pip install -r requirements-research.txt
python tools/export_models.py /path/to/leo.nds --out /new/path/leo-models.zip
python -m unittest discover -s tests -v
```

The exporter writes a rejection report beside the pack. It requires the exact profiled ROM and refuses to overwrite output. Format reference: https://github.com/scurest/nsbmd_docs. No game assets or original executable bytes are committed.

## Limits and next implementation work

Models are static bind poses. Normal-based lighting, material texture transforms, animated visibility, billboards, environment mapping and transparent polygon sorting are unfinished. The explorer does not reproduce DS rasterization exactly. Geometry was visually checked with a software preview; Android build/lint checks do not establish pixel parity on Thor.

The next runtime milestone is decoding and binding skeletal animation tracks to `pl000`, then establishing field/battle state transitions and the DS services their code calls. Save parsing, dialogue/event execution, audio, battles and progression remain unfinished. The voxel study remains a separate rendering prototype, not an in-game geometry replacement.
