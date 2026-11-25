# Content format: playlists and custom guides

Playlists and custom guides are plain JSON files bundled in the app assets.
No code changes are needed to add one: drop the files in place and rebuild.

## Playlists — `app/src/main/assets/playlists/<name>.json`

Every `.json` file in `assets/playlists/` is loaded at startup and listed on
the Playlists screen, in filename order.

```json
{
  "id": "london-2026",
  "title": "London",
  "subtitle": "A walking tour of London's great sights, day by day",
  "stops": [
    {
      "id": "tower-of-london",
      "dayLabel": "Wednesday",
      "kind": "custom",
      "title": "Tower of London",
      "subtitle": "Fortress, palace and prison on the Thames",
      "guideId": "tower-of-london"
    },
    {
      "id": "city-of-london-walk",
      "dayLabel": "Wednesday",
      "kind": "official",
      "title": "City of London Walk",
      "subtitle": "Rick Steves audio tour of the historic Square Mile",
      "officialMatch": "city of london"
    }
  ]
}
```

Fields:

- `id`, `title` — required. `subtitle` — optional.
- `stops` — in display order. The screen groups consecutive stops by
  `dayLabel`, so keep each day's stops together.
- Each stop: `id` (unique within the playlist), `dayLabel`, `title` are
  required; `subtitle` and `note` (a short italic aside, e.g. an
  exterior-viewing note) are optional.
- `kind: "custom"` — the stop opens a custom guide; set `guideId` to the
  guide's file name without `.json`.
- `kind: "official"` — the stop links to a downloaded/catalog Rick Steves
  tour. `officialMatch` is a lowercase substring matched against tour titles
  (e.g. `"westminster"`). Keep it specific enough to match exactly one tour.
  If nothing matches, the stop shows the match text as a hint instead of a
  link.

Playlists contain locations and tour information only — no booking
references, prices, personal times, or other private details.

## Custom guides — `app/src/main/assets/guides/<guideId>.json`

A stop whose guide file is missing is tolerated: the guide screen says the
guide is not available yet and the rest of the playlist keeps working.

```json
{
  "id": "tower-of-london",
  "title": "Tower of London",
  "subtitle": "Fortress, palace and prison on the Thames",
  "area": "London",
  "dayLabel": "Wednesday",
  "estimatedMinutes": 150,
  "location": {"name": "Tower of London", "latitude": 51.5081, "longitude": -0.0759},
  "heroImage": "images/tower-of-london/hero.jpg",
  "intro": "Opening text shown under the title.",
  "practical": ["Practical tip, one per string."],
  "mapNote": "How to find the entrance, in words.",
  "chapters": [
    {
      "title": "Welcome",
      "image": "images/tower-of-london/01.jpg",
      "caption": "The White Tower from Tower Green",
      "intro": "Optional short lead-in paragraph, spoken first in the chapter.",
      "subchapters": [
        {
          "title": "The Middle Tower",
          "text": "Narration for this one element. This is what text-to-speech reads aloud.",
          "directions": "Optional walking directions, shown in italic.",
          "image": "images/tower-of-london/02.jpg",
          "caption": "Optional caption for this subchapter's image."
        },
        {
          "title": "Tower Green",
          "text": "Narration for the next element, played right after the previous subchapter."
        }
      ]
    }
  ],
  "sources": ["https://example.org/source"]
}
```

Fields:

- `id` (must match the file name and the stop's `guideId`) and `title` are
  required; everything else is optional and degrades gracefully.
- `estimatedMinutes` — how long the visit takes, not the audio length.
- `heroImage`, chapter `image` and subchapter `image` paths are relative to
  `assets/guides/` (so `images/<guideId>/hero.jpg` means
  `assets/guides/images/<guideId>/hero.jpg`). Missing images are simply not
  shown.
- Chapters are made of subchapters. A chapter has a `title`, an optional
  chapter-level `image`/`caption`, an optional `intro` (a short lead-in
  paragraph) and a `subchapters` array. Each subchapter is one element of
  the visit — a room, an object, a story — with a `title`, the narration
  `text`, and optional `directions` (shown in italic), `image` and
  `caption`. All subchapter fields except the narration itself may be
  omitted and default to empty.
- Chapters play in array order — write them in real visit order. Within a
  chapter, playback is the `intro` first (when non-blank), then each
  subchapter's `text` in order. Subchapter `text` is what text-to-speech
  reads aloud, so keep subchapters self-contained and write them to be
  heard, not read. On the guide screen each subchapter is nested under its
  chapter and has its own play button; the chapter's play button plays the
  whole chapter (intro, then subchapters), and "Listen to the whole guide"
  plays every chapter in order.
- Legacy chapters still parse: a chapter with a chapter-level `text` field
  and no `subchapters` is read as a chapter with no intro and a single
  untitled subchapter holding that `text` (and the chapter's `directions`).
  Prefer the subchapter shape for new content.
- The guide screen is built from blocks — header, practical notes, map note,
  then one block per chapter with its subchapters nested inside — so
  content authors only need this JSON; new block types are added in code,
  not in the format.
