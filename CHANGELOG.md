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
- Export a novel's downloaded chapters as an EPUB from the novel screen's menu; it runs in the background with a progress notification.
- Downloaded chapters keep their images, so they read fully offline.
- Each novel can have its own reading mode, scroll or paged, from the reader's settings.
- Read chapters aloud with the system's text to speech; the reader follows the paragraph being spoken and carries on into the next chapter.
- Add your own TrueType or OpenType font files to read in.
- Scroll mode reads on into the next chapter without stopping, which can be turned off in the reader settings.

### Changed
- Tracker searches on AniList, MyAnimeList, Kitsu, MangaUpdates, Shikimori and Bangumi find the novel instead of its manga adaptations.
- Shikimori is reached at its new shikimori.io address.
- The text reader shows the chapter transition, loading and error screens the image readers had.
- Related titles are off by default, so opening a novel doesn't send a burst of searches to its site.
