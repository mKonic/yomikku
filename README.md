# Yomikku

A light-novel reader for Android 8.0 and newer. Novels come from Kotlin extension APKs
([mKonic/yomikku-extensions](https://github.com/mKonic/yomikku-extensions)) and from local EPUB and TXT files.
Chapters render as native Android text, in paged or scrolling mode.

Yomikku is a fork of [Komikku](https://github.com/mKonic/komikku), which is based on
[Mihon](https://github.com/mihonapp/mihon) and TachiyomiSY. The image reader was replaced with a text reader.

## Download

[Releases](https://github.com/mKonic/yomikku/releases)

## Building

```sh
./gradlew :app:assembleDebug
```

JDK 17 or newer, with the Android SDK path in `local.properties`. `-Pabis=arm64-v8a` builds a single ABI.

## Disclaimer

The developers of this application have no affiliation with the content providers available, and this
application hosts no content.

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
