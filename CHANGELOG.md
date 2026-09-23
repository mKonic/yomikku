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
- Export a novel's downloaded chapters as an EPUB from the novel screen's menu.
- Downloaded chapters keep their images, so they read fully offline.

### Changed
- Related titles are off by default, so opening a novel doesn't send a burst of searches to its site.

### Fixed
- Covers of local novels in folders with spaces in their names now show.
- Refreshing a local novel no longer renames its folder to the book's title, which lost its chapters.
- Full-page illustrations in local EPUBs no longer show their alt text as a stray line.
