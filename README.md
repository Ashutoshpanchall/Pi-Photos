
## Design notes

- **Index, not database.** Photo metadata is read from MediaStore and held in memory. Room stores only app-owned data such as favourites, so the phone's media is never copied.
- **Memory first.** The index keeps only the fields needed for the grid. Heavy metadata is loaded when an item is opened.
- **Deletion semantics.** Moving to Bin removes an item from the device. When backup exists, it is also removed from the backup. Each step asks for consent through Android's system dialogs.

## Status

| Area | State |
|---|---|
| Gallery: timeline grid, full-screen viewer, video, favourites | Implemented |
| Backup to your own Raspberry Pi (pairing, resumable uploads, per-device folders) | Implemented, waiting for on-device verification |
| Several users on one node (owner and members, quotas, invites) | Implemented, waiting for on-device verification |
| Performance pass (tooling, startup, grid) | Tooling added; measured results pending (see `docs/PERF.md`) |
| Encryption at rest (client-side or on the node) | Not implemented |

## Privacy

Without a storage node, PiPhotos does not send your photos anywhere.

If you set up backup to your own Raspberry Pi:

- Photos travel over TLS. The app pins the node's certificate fingerprint when you pair.
- Photos are **stored unencrypted** on the node's disk, under `users/<user>/devices/<device>/<folder>/`. The node can read them: it hashes them for duplicate detection, lists them and serves them back.
- Anyone with access to the node (its owner, or someone who takes its SD card or disk) can read the files. Use full-disk encryption on the node if that matters to you.
- Encryption at rest is not implemented.

## Contributing

This is a personal project in active development, so the structure may change quickly. Issues and suggestions are welcome.

