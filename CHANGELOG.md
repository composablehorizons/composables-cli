# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `init` can now add modules in existing Gradle Projects.

## [0.3.0] - 2025-12-9

### Added
- NEW `target` command. You can now add new targets in existing Compose Multiplatform projects.

### Changed
- All `init` options are now optional when creating a new app.

## [0.2.0] - 2025-12-7

### Added
- You can now select the platforms when creating a new app.  

## [0.1.1] - 2025-12-6

### Fixed
- Fix a bug where gradle-wrapper.jar is not copied to the new app

## [0.1.0] - 2025-12-6

### Added 
- Add the option to create compose apps for all targets using `composables init composeApp`
