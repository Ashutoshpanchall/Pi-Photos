# PiPhotos

Privacy-first, high-performance Android photo gallery and private self-hosted backup node paired with private local vaults.

---

## Storage Node Quick Setup

PiPhotos includes a storage node that backs up photos over TLS to your Raspberry Pi, Linux, macOS, or Windows computer.

### One-Command Setup

**macOS / Linux / Raspberry Pi**:
```bash
curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh | sh
```

**Windows (PowerShell)**:
```powershell
irm https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.ps1 | iex
```

See [docs/SERVER_SETUP.md](docs/SERVER_SETUP.md) for detailed platform setup notes, firewall configuration, pairing, and diagnostics.

---

## Design Notes

- **Index, not database.** Photo metadata is read from MediaStore and held in memory. Room stores only app-owned data such as favourites, so the phone's media is never copied.
- **Memory first.** The index keeps only the fields needed for the grid. Heavy metadata is loaded when an item is opened.
- **Deletion semantics.** Moving to Bin removes an item from the device. When backup exists, it is also removed from the backup. Each step asks for consent through Android's system dialogs.
- **120Hz smooth scrolling.** Background thread item diffing with zero disk or network I/O on the main thread.

## Status

| Area | State |
|---|---|
| Gallery: timeline grid, full-screen viewer, video, favourites | Implemented |
| Backup to your own Raspberry Pi / PC (one-command setup, pairing, resumable uploads) | Implemented ([docs/SERVER_SETUP.md](docs/SERVER_SETUP.md)) |
| Several users on one node (owner and members, quotas, invites) | Implemented |
| Performance pass (tooling, startup, grid) | Tooling added; measured results pending (see `docs/PERF.md`) |
| Encryption at rest (client-side or on the node) | Not implemented |

## Privacy

Without a storage node, PiPhotos does not send your photos anywhere.

If you set up backup to your own storage node:

- Photos travel over TLS. The app pins the node's certificate fingerprint when you pair.
- Photos are stored on the node's disk under `users/<user>/devices/<device>/<folder>/`. The node hashes them for duplicate detection, lists them and serves them back.
- Anyone with access to the node (its owner, or someone who takes its SD card or disk) can read the files. Use full-disk encryption on the node if that matters to you.

## Contributing

This is a personal project in active development. Issues and suggestions are welcome.
