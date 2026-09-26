# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed ` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [Unreleased]
### Added
- Sponsor button on the More screen, linking to Ko-fi.

## [v1.0.1] - 2026-09-24
### Fixed
- Syncing no longer restores the whole library again and again when nothing changed. Novels were matched by title and author as well as source and address, a synced novel's original title was never saved, and a chapter whose state matched but whose version was newer was skipped, so each device kept finding the other's library changed. A sync also restored every kind of data whatever the sync settings said.
- Syncing a large library no longer runs out of memory while uploading, and when memory does run out the sync says so instead of staying on "running".
- Covers downloaded without a disk cache no longer come out partly drawn. The cover colours were read from the same download the image was being decoded from, and each reader took part of it.
- The updates and lock screen widgets no longer fail when they have only text to show (no recent updates, or the app locked).
- Library search from a source name's long press menu finds that source's novels. It searched for the name as shown, which can carry the source's language, and no novel's source matched it.
- Scrolling through a source no longer leaves every novel it passed watching the database for the rest of the visit.

## [v1.0.0]
First release: a light-novel reader built on Komikku, with a native text reader, EPUB import and export, and Kotlin extensions ported from LNReader's plugins.

### Added
- Export a novel's downloaded chapters as an EPUB from the novel screen's menu; it runs in the background with a progress notification.
- Downloaded chapters keep their images, so they read fully offline.
- Each novel can have its own reading mode, scroll or paged, from the reader's settings.
- Read chapters aloud with the system's text to speech; the reader follows the paragraph being spoken and carries on into the next chapter.
- Add your own TrueType or OpenType font files to read in.
- Scroll mode reads on into the next chapter without stopping, which can be turned off in the reader settings.

### Changed
- Local EPUBs are split into the chapters their table of contents lists, joining or splitting files as needed, and pages with no text are left out.
- Tracker searches on AniList, MyAnimeList, Kitsu, MangaUpdates, Shikimori and Bangumi find the novel instead of its manga adaptations.
- Shikimori is reached at its new shikimori.io address.
- The text reader shows the chapter transition, loading and error screens the image readers had.
- Related titles are off by default, so opening a novel doesn't send a burst of searches to its site.
