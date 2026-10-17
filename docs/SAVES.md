# Star Force 1 save format (as far as known)

256 KiB flash. Found by tracing Leo's own save loader with the `sfhost --watch` hook.

| Offset | Contents |
|---|---|
| `0x0000` | Header, 0x64 bytes |
| `0x1000`–`0x1FFF` | Nintendo Wi-Fi Connection profile (`megamansfds`, game code stored reversed, five copies 0x200 apart) |
| `0x4000 + n·0xC000` | Five rotating save slots, n = 0…4 |

Header:

| Offset | Size | Meaning |
|---|---|---|
| `0x00` | 12 | magic `rr1_X061026\0`; **X = version letter**: `p` Pegasus, `l` Leo, `d` Dragon |
| `0x0E` | 2 | checksum: `~sum(bytes 0x00..0x63) & 0xFFFF`, computed with this field zeroed (code at `0x0200fd2c`) |
| `0x10` | 4 | sequence number of the newest slot |
| `0x14 + 16·n` | 16 | slot record: flash offset, data size, sequence number, checksum (`~sum(data) & 0xFFFF`) |

Leo rejects a save whose magic isn't `rr1_l061026` (header builder at `0x0200a5d0`, magic string at `0x020fa2ac`). Slot data is scrambled but carries only version-independent checksums. Changing the letter and fixing the header checksum is therefore enough to load a Pegasus or Dragon save in Leo, as tested with a complete Dragon save (Level 100, 6 Brothers). The app's **Import save** does this automatically, and also strips Action Replay `.duc` headers (500 bytes, `ARDS…`).
