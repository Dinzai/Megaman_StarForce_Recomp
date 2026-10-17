# Visual mods, schema 1

The current API changes the **standalone voxel preview**, not Leo gameplay. JSON packages are data-only and cannot load executable code, access files or use the network. One active visual mod is supported; installing another atomically replaces it.

```json
{
  "schema": 1,
  "id": "example.deep-cyan",
  "name": "Deep Cyan",
  "game": "leo",
  "type": "voxel-preview",
  "render": { "depth": 4, "accent": "#20CCFF", "grid": true }
}
```

Maximum input: 16 KiB. Name: 1–64 characters. Depth: finite 0–8. Accent: six-digit RGB hex. Invalid schema, game, type or rendering values are rejected before saving. `id` is descriptive metadata, not a script entry point. Drag the preview horizontally to rotate its view.

The bundled dramatic-voxel package extrudes a procedural test sprite. Future work must connect decoded Leo sprites/models to a renderer interface before a mod can alter the actual game. This schema intentionally makes no claims about hooks that do not exist.
