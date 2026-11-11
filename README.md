
## Design notes

- **Index, not database.** Photo metadata is read from MediaStore and held in memory. Room stores only app-owned data such as favourites, so the phone's media is never copied.
- **Memory first.** The index keeps only the fields needed for the grid. Heavy metadata is loaded when an item is opened.
- **Deletion semantics.** Moving to Bin removes an item from the device. When backup exists, it is also removed from the backup. Each step asks for consent through Android's system dialogs.

## Roadmap

| Phase | Goal | State |
|---|---|---|
| G0 | Stabilise the prototype and remove mock data | Done |
| G1 | Gallery-first architecture, seams for media source and delete handler | Done |
| G2 | Welcome screen, permissions, MediaStore index | Done |
| G3 | Real timeline grid (day, month, year) | Next |
| G4 | Viewer and video player on real media | Planned |
| G5 | System-based trash, delete and restore | Planned |
| G6 | Favourites sync, search | Planned |
| N1+ | Raspberry Pi backend, encrypted sync, family accounts | Planned |

## Privacy

PiPhotos does not send your photos anywhere in the local-only build. When the backup feature is added, photos will be encrypted on the device before upload, and the server will not be able to read them.

## Contributing

This is a personal project in active development, so the structure may change quickly. Issues and suggestions are welcome.

